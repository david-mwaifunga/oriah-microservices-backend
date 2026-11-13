package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.another;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static com.oriah.inventory.service.StockTestData.ring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A received consignment line becomes stock with its cost stamped on it: one lot per slice, the count, and a
 * ledger row per lot, all in one transaction. The worked example is asserted to the ngwee, and the Part 4
 * invariant (what the lots are worth is exactly what the shipment cost, landed) is checked over INVENTORY's lots,
 * not procurement's allocation.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class StockReceiptTest {

    private static final String MONDAY = "2026-11-09T08:00:00.123456Z";
    private static final String THURSDAY = "2026-11-12T10:00:00.000001Z";

    @Autowired
    private StockService stock;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        StockTestData.wipe(jdbc);
    }

    private List<Map<String, Object>> lots(UUID variant) {
        return jdbc.queryForList("""
                SELECT slice_position, qty_received, qty_remaining, unit_cost, received_at, fifo_tiebreak, kind, sku, variant_title,
                       supplier_name, is_estimated, revision, currency
                  FROM cost_lot WHERE variant_id = ? ORDER BY received_at, fifo_tiebreak, slice_position""", variant);
    }

    private BigDecimal worth(UUID variant) {
        return jdbc.queryForObject("SELECT coalesce(sum(qty_received * unit_cost), 0) FROM cost_lot WHERE variant_id = ?",
                BigDecimal.class, variant);
    }

    private int onHand(UUID variant) {
        return jdbc.queryForObject("SELECT on_hand FROM inventory WHERE variant_id = ?", Integer.class, variant);
    }

    @Test
    @DisplayName("the ring line becomes two lots, 4 x K1,528.99 and 3 x K1,528.98, and 7 are on hand")
    void theRingLine() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot ring = ring(variant, MONDAY);

        stock.applyReceipt(ring, context(5001));

        List<Map<String, Object>> lots = lots(variant);
        assertThat(lots).hasSize(2);
        assertThat(lots.get(0)).containsEntry("slice_position", 1).containsEntry("qty_received", 4).containsEntry("qty_remaining", 4)
                .containsEntry("kind", "SELLABLE").containsEntry("sku", "RING-1").containsEntry("supplier_name", "Dubai Gold")
                .containsEntry("is_estimated", false).containsEntry("revision", 1).containsEntry("currency", "ZMW")
                .containsEntry("fifo_tiebreak", 1L);
        assertThat((BigDecimal) lots.get(0).get("unit_cost")).isEqualByComparingTo("1528.99");
        assertThat(lots.get(1)).containsEntry("slice_position", 2).containsEntry("qty_received", 3);
        assertThat((BigDecimal) lots.get(1).get("unit_cost")).isEqualByComparingTo("1528.98");
        assertThat(onHand(variant)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE variant_id = ?", Integer.class, variant)).isZero();
        assertThat(worth(variant)).as("the lots are worth what the shipment cost, landed").isEqualByComparingTo("10702.90");
    }

    @Test
    @DisplayName("the box line becomes 110 x K78.99 and 90 x K78.98, and packaging says so")
    void theBoxLine() {
        UUID variant = UUID.randomUUID();

        stock.applyReceipt(box(variant, MONDAY), context(5002));

        List<Map<String, Object>> lots = lots(variant);
        assertThat(lots).extracting(l -> l.get("qty_received")).containsExactly(110, 90);
        assertThat(lots).allSatisfy(l -> assertThat(l).containsEntry("kind", "PACKAGING"));
        assertThat(worth(variant)).isEqualByComparingTo("15797.10");
        assertThat(jdbc.queryForObject("SELECT kind FROM inventory WHERE variant_id = ?", String.class, variant)).isEqualTo("PACKAGING");
    }

    @Test
    @DisplayName("the ledger has a receipt action and one row per lot, adding up to what each lot holds")
    void theLedgerSaysSo() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot ring = ring(variant, MONDAY);

        stock.applyReceipt(ring, context(5003));

        Map<String, Object> action = jdbc.queryForMap("SELECT * FROM stock_action WHERE variant_id = ?", variant);
        assertThat(action).containsEntry("id", ring.consignmentLineId()).containsEntry("kind", "RECEIPT")
                .containsEntry("reason", "CONSIGNMENT_RECEIVED").containsEntry("qty", 7).containsEntry("actor", ACTOR);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT qty_delta, role, reason, cost_method, cost_basis, unit_cost FROM stock_movement WHERE variant_id = ? ORDER BY id", variant);
        assertThat(rows).extracting(r -> r.get("qty_delta")).containsExactly(4, 3);
        assertThat(rows).allSatisfy(r -> assertThat(r).containsEntry("role", "ORIGINAL").containsEntry("reason", "CONSIGNMENT_RECEIVED")
                .containsEntry("cost_method", "FIFO").containsEntry("cost_basis", "RECEIVED_AT"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cost_lot l WHERE l.qty_remaining <>
                       (SELECT coalesce(sum(m.qty_delta), 0) FROM stock_movement m WHERE m.lot_id = l.id)""", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a later receipt of the same variant adds its lots behind the older ones: oldest first, count adds up")
    void twoReceiptsAreOrderedOldestFirst() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot first = ring(variant, MONDAY);
        ReceiptSnapshot second = another(first, THURSDAY, 1);

        stock.applyReceipt(first, context(5004));
        stock.applyReceipt(second, context(5010));

        assertThat(onHand(variant)).isEqualTo(14);
        assertThat(stock.heldLots(variant)).extracting(StockService.HeldLot::consignmentLineId)
                .containsExactly(first.consignmentLineId(), first.consignmentLineId(), second.consignmentLineId(), second.consignmentLineId());
    }

    @Test
    @DisplayName("an OLDER receipt that arrives after a newer one still sorts first: arrival order does not decide FIFO")
    void arrivalOrderDoesNotDecideFifo() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot older = ring(variant, MONDAY);
        ReceiptSnapshot newer = another(older, THURSDAY, 1);

        stock.applyReceipt(newer, context(5010));
        stock.applyReceipt(older, context(5004));

        assertThat(stock.heldLots(variant).get(0).consignmentLineId()).isEqualTo(older.consignmentLineId());
    }

    @Test
    @DisplayName("two lines of ONE receipt are ordered by where they sit in the consignment, whatever order they arrive in")
    void linesOfOneReceiptFollowTheirPosition() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot lineOne = ring(variant, MONDAY);
        ReceiptSnapshot lineTwo = another(lineOne, MONDAY, 2);

        stock.applyReceipt(lineTwo, context(5006));
        stock.applyReceipt(lineOne, context(5005));

        assertThat(stock.heldLots(variant)).extracting(StockService.HeldLot::consignmentLineId)
                .startsWith(lineOne.consignmentLineId());
    }

    @Test
    @DisplayName("an event written before linePosition existed is keyed by its outbox id instead")
    void anOlderEventUsesItsOutboxId() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot old = new ReceiptSnapshot(UUID.randomUUID(), "C-000001", UUID.randomUUID(), variant, "RING-1", "R", "R - 1",
                "SELLABLE", null, null, MONDAY, true, "ZMW", 1, new BigDecimal("5.00"), new BigDecimal("0.00"), new BigDecimal("5.00"),
                List.of(new ReceiptSnapshot.Slice(1, new BigDecimal("5.00"))), null, null, null, null);

        stock.applyReceipt(old, context(7777));

        assertThat(lots(variant).get(0)).containsEntry("fifo_tiebreak", 7777L);
    }

    @Test
    @DisplayName("opening stock has no supplier and is an estimate, and every lot says so")
    void openingStock() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot opening = new ReceiptSnapshot(UUID.randomUUID(), "C-000001", UUID.randomUUID(), variant, "RING-1", null, "R - 1",
                "SELLABLE", null, null, MONDAY, true, "ZMW", 3, new BigDecimal("300.00"), new BigDecimal("0.00"), new BigDecimal("300.00"),
                List.of(new ReceiptSnapshot.Slice(3, new BigDecimal("100.00"))), 1, null, null, null);

        stock.applyReceipt(opening, context(1));

        assertThat(lots(variant).get(0)).containsEntry("is_estimated", true).containsEntry("supplier_name", null);
        assertThat(jdbc.queryForObject("SELECT is_estimated FROM stock_movement WHERE variant_id = ?", Boolean.class, variant)).isTrue();
        assertThat(jdbc.queryForObject("SELECT supplier_id FROM cost_lot WHERE variant_id = ?", UUID.class, variant)).isNull();
    }

    @Test
    @DisplayName("a receipt that breaks its own promises is refused and leaves nothing behind")
    void aWrongEventLeavesNothing() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot ring = ring(variant, MONDAY);
        ReceiptSnapshot wrong = new ReceiptSnapshot(ring.consignmentId(), ring.consignmentReference(), ring.consignmentLineId(), variant,
                ring.sku(), ring.productTitle(), ring.variantTitle(), ring.kind(), ring.supplierId(), ring.supplierName(), MONDAY, false,
                "ZMW", 7, ring.goodsTotal(), ring.sharedCost(), ring.landedTotal(),
                List.of(new ReceiptSnapshot.Slice(4, new BigDecimal("1528.99")), new ReceiptSnapshot.Slice(2, new BigDecimal("1528.98"))),
                1, null, null, null);

        assertThatThrownBy(() -> stock.applyReceipt(wrong, context(1))).isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_lot", Integer.class)).isZero();
    }

    @Test
    @DisplayName("the same line received twice is refused, so a repeat can never double the stock")
    void theSameLineTwice() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot ring = ring(variant, MONDAY);
        stock.applyReceipt(ring, context(1));

        assertThatThrownBy(() -> stock.applyReceipt(ring, context(2))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already");

        assertThat(onHand(variant)).isEqualTo(7);
        assertThat(lots(variant)).hasSize(2);
    }

    @Test
    @DisplayName("a variant is goods or packaging for good: a receipt that says otherwise is refused")
    void aKindThatDisagrees() {
        UUID variant = UUID.randomUUID();
        stock.applyReceipt(ring(variant, MONDAY), context(1));
        ReceiptSnapshot asBox = box(variant, THURSDAY);

        assertThatThrownBy(() -> stock.applyReceipt(asBox, context(2))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kind");
        assertThat(onHand(variant)).isEqualTo(7);
    }

    @Test
    @DisplayName("stock whose count disagrees with its lots is never built on: the receipt is refused")
    void aRowThatDisagreesWithItsLots() {
        UUID variant = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory (variant_id, on_hand, reserved, kind) VALUES (?, 5, 0, 'SELLABLE')", variant);

        assertThatThrownBy(() -> stock.applyReceipt(ring(variant, MONDAY), context(1))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disagree");
        assertThat(onHand(variant)).isEqualTo(5);
        assertThat(lots(variant)).isEmpty();
    }

    @Test
    @DisplayName("two lines of one NEW variant arriving at the same moment both apply: neither loses to a race to create the row")
    void twoReceiptsRacingForANewVariant() throws Exception {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot a = ring(variant, MONDAY);
        ReceiptSnapshot b = another(a, THURSDAY, 1);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> fa = pool.submit(() -> { go.await(); stock.applyReceipt(a, context(10)); return null; });
            Future<?> fb = pool.submit(() -> { go.await(); stock.applyReceipt(b, context(11)); return null; });
            go.countDown();
            fa.get(60, TimeUnit.SECONDS);
            fb.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(onHand(variant)).isEqualTo(14);
        assertThat(lots(variant)).hasSize(4);
        assertThat(worth(variant)).isEqualByComparingTo("21405.80");
    }
}
