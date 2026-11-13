package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.events.ReceiptSnapshot;
import com.oriah.inventory.events.ReceiptSnapshot.Slice;
import com.oriah.inventory.exception.InsufficientStockException;
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * After ANY mix of receipts (some older than stock already sold), sales, write-offs, give-aways, take-backs and cost
 * corrections, against a real database: every stock count equals its lots, every lot equals its ledger, and every
 * consignment line is worth exactly its landed total, to the ngwee: what its lots still hold plus what was recognised
 * out of them. No ngwee is lost, and none is invented. (The engine is proved on its own in FifoConformerTest; this is
 * the same promise through the SQL, the locking and the invariants.)
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class StockLedgerPropertyTest {

    @Autowired
    private StockService stock;

    @Autowired
    private InventoryRepository inventories;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        StockTestData.wipe(jdbc);
    }

    private record Line(ReceiptSnapshot snapshot, int revision) {
    }

    /** One or two slices, dearer first, from a landed total in whole ngwee: the allocator's own rule. */
    private static List<Slice> slices(BigDecimal landed, int qty) {
        long ngwee = landed.movePointRight(2).longValueExact();
        long base = ngwee / qty;
        long rem = ngwee % qty;
        if (rem == 0) {
            return List.of(new Slice(qty, BigDecimal.valueOf(base, 2)));
        }
        return List.of(new Slice((int) rem, BigDecimal.valueOf(base + 1, 2)), new Slice(qty - (int) rem, BigDecimal.valueOf(base, 2)));
    }

    private static ReceiptSnapshot line(UUID variant, int position, Instant receivedAt, int qty, long goodsKwacha, long sharedNgwee) {
        BigDecimal goods = BigDecimal.valueOf(goodsKwacha).setScale(2);
        BigDecimal shared = BigDecimal.valueOf(sharedNgwee, 2);
        BigDecimal landed = goods.add(shared);
        return new ReceiptSnapshot(UUID.randomUUID(), "C-0000" + position, UUID.randomUUID(), variant, "SKU-P", "Product", "Product - P", "SELLABLE",
                UUID.randomUUID(), "Supplier", receivedAt.toString(), false, "ZMW", qty, goods, shared, landed, slices(landed, qty), position, null, null, null);
    }

    @Test
    @DisplayName("after any mix of receipts, sales, write-offs, take-backs and corrections: the books reconcile and every line is worth its landed total")
    void propertyOverRandomHistories() {
        Map<String, Integer> done = new java.util.TreeMap<>();
        int recutRows = 0;
        for (long seed = 1; seed <= 6; seed++) {
            recutRows += jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE role = 'RECUT_REVERSAL'", Integer.class);
            StockTestData.wipe(jdbc);
            Random random = new Random(seed);
            UUID[] variants = {UUID.randomUUID(), UUID.randomUUID()};
            List<Line> lines = new ArrayList<>();
            List<UUID> writeOffs = new ArrayList<>();
            Instant base = Instant.parse("2026-02-01T08:00:00Z");
            int clock = 0;
            for (int step = 0; step < 30; step++) {
                int roll = random.nextInt(10);
                UUID variant = variants[random.nextInt(2)];
                String what;
                try {
                    if (roll < 2 || lines.isEmpty()) {
                        // A receipt: usually the newest, sometimes older than what is already on the shelf.
                        Instant at = base.plusSeconds(86400L * (random.nextInt(4) == 0 ? random.nextInt(clock + 1) : ++clock));
                        ReceiptSnapshot receipt = line(variant, lines.size() + 1, at, 1 + random.nextInt(7), 100 + random.nextInt(900), random.nextInt(90000));
                        stock.applyReceipt(receipt, StockTestData.context(1000 + step));
                        lines.add(new Line(receipt, 1));
                        what = "receipt";
                    } else if (roll < 5) {
                        Inventory inv = inventories.findById(variant).orElse(null);
                        if (inv == null || inv.available() < 1) {
                            continue;
                        }
                        int qty = 1 + random.nextInt(Math.min(inv.available(), 4));
                        inv.reserve(qty);
                        inventories.saveAndFlush(inv);
                        Instant when = random.nextBoolean() ? null : base.plusSeconds(86400L * random.nextInt(clock + 1));
                        stock.consume(variant, qty, UUID.randomUUID(), when, ACTOR);
                        what = "sale";
                    } else if (roll < 6) {
                        Inventory inv = inventories.findById(variant).orElse(null);
                        if (inv == null || inv.available() < 1) {
                            continue;
                        }
                        StockReason reason = random.nextBoolean() ? StockReason.WRITE_OFF_LOST : StockReason.GIVEN_AWAY_GIFT;
                        UUID request = UUID.randomUUID();
                        stock.writeOff(request, variant, 1 + random.nextInt(Math.min(inv.available(), 3)), reason, null, null, ACTOR);
                        writeOffs.add(request);
                        what = "write-off";
                    } else if (roll < 8 && !writeOffs.isEmpty()) {
                        UUID action = writeOffs.get(random.nextInt(writeOffs.size()));
                        StockService.WriteOffRow row = stock.findWriteOff(action).orElseThrow();
                        if (row.outstanding() < 1) {
                            continue;
                        }
                        stock.takeBack(UUID.randomUUID(), action, 1 + random.nextInt(row.outstanding()), null, null, ACTOR);
                        what = "take-back";
                    } else {
                        Line target = lines.get(random.nextInt(lines.size()));
                        ReceiptSnapshot s = target.snapshot();
                        BigDecimal shared = BigDecimal.valueOf(random.nextInt(90000), 2);
                        BigDecimal landed = s.goodsTotal().add(shared);
                        int revision = target.revision() + 1;
                        ReceiptSnapshot correction = new ReceiptSnapshot(s.consignmentId(), s.consignmentReference(), s.consignmentLineId(), s.variantId(),
                                s.sku(), s.productTitle(), s.variantTitle(), s.kind(), s.supplierId(), s.supplierName(), s.receivedAt(), false, "ZMW",
                                s.quantity(), s.goodsTotal(), shared, landed, slices(landed, s.quantity()), s.linePosition(), revision, "re-keyed",
                                "2026-11-13T09:00:00Z");
                        stock.applyAmendment(correction, StockTestData.amendmentContext(2000 + step, "2026-11-13T09:00:00Z"));
                        lines.set(lines.indexOf(target), new Line(correction, revision));
                        what = "correction";
                    }
                } catch (InsufficientStockException e) {
                    continue;   // a sale or write-off asked for more than the lots could cover: refused, nothing written
                }
                done.merge(what, 1, Integer::sum);
                assertBooks(seed, step, what);
            }
        }
        recutRows += jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE role = 'RECUT_REVERSAL'", Integer.class);
        // The walks must actually have done each kind of thing, or the promise above proves nothing.
        assertThat(done).as("what the random walks did").containsKeys("receipt", "sale", "write-off", "take-back", "correction");
        assertThat(done.get("correction")).isGreaterThanOrEqualTo(10);
        assertThat(done.get("take-back")).isGreaterThanOrEqualTo(5);
        assertThat(recutRows).as("sales and write-offs that were re-cut by a correction or an older receipt").isGreaterThanOrEqualTo(10);
    }

    private void assertBooks(long seed, int step, String what) {
        String where = "seed " + seed + " step " + step + " after a " + what;
        StockService.ReconciliationReport report = stock.reconcile(null);
        assertThat(report.countMismatches()).as("every count equals its lots, " + where).isEmpty();
        assertThat(report.ledgerMismatches()).as("every lot equals its ledger, " + where).isEmpty();
        // Every line, as it stands (its current lots): worth exactly its landed total.
        for (Map<String, Object> line : jdbc.queryForList("""
                SELECT consignment_line_id, sum(qty_received * unit_cost) AS landed, sum(qty_remaining * unit_cost) AS held
                  FROM cost_lot WHERE superseded_at IS NULL GROUP BY consignment_line_id""")) {
            BigDecimal recognised = jdbc.queryForObject("""
                    SELECT coalesce(sum(-m.qty_delta * m.unit_cost), 0) FROM live_movement m JOIN cost_lot l ON l.id = m.lot_id
                     WHERE l.consignment_line_id = ? AND l.superseded_at IS NULL AND m.reason <> 'CONSIGNMENT_RECEIVED'""",
                    BigDecimal.class, line.get("consignment_line_id"));
            assertThat(((BigDecimal) line.get("held")).add(recognised)).as("line " + line.get("consignment_line_id") + " is worth its landed total, " + where)
                    .isEqualByComparingTo((BigDecimal) line.get("landed"));
        }
        // A replaced lot holds nothing, and a reversed row is reversed at most once (the index would refuse otherwise).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_lot WHERE superseded_at IS NOT NULL AND qty_remaining <> 0", Integer.class)).isZero();
    }
}
