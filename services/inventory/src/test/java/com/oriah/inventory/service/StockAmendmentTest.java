package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.amended;
import static com.oriah.inventory.service.StockTestData.amendmentContext;
import static com.oriah.inventory.service.StockTestData.context;
import static com.oriah.inventory.service.StockTestData.sevenRingsAsKeyed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A received line's cost is corrected: the courier's charge was keyed K800 and was really K8,000. The shipment is not
 * edited (it never is): procurement records a new revision and says so, and inventory re-lays the NEW unit costs over
 * the line's units, so that every sale and write-off that already drained those units is re-cut at its own date, and
 * what is still held is re-valued. Nothing reaches expenses: the cost simply moves to where it belongs.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class StockAmendmentTest {

    private static final String RECEIVED = "2026-02-10T08:00:00.000001Z";
    private static final String CORRECTED_AT = "2026-11-13T09:00:00Z";
    private static final java.time.Instant MARCH = java.time.Instant.parse("2026-03-05T09:30:00Z");
    private static final java.time.Instant APRIL = java.time.Instant.parse("2026-04-20T15:00:00Z");

    /** K8,000 shared: 5 x K2,142.86 + 2 x K2,142.85 = K15,000.00. */
    private static final List<Slice> EIGHT_THOUSAND = List.of(new Slice(5, new BigDecimal("2142.86")), new Slice(2, new BigDecimal("2142.85")));

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

    /** 7 rings received in February at K800 of courier; 3 sold in March; 1 written off in April; 3 held. */
    private ReceiptSnapshot theShop(UUID variant) {
        ReceiptSnapshot line = sevenRingsAsKeyed(variant, RECEIVED);
        stock.applyReceipt(line, context(5001));
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(3);
        inventories.saveAndFlush(inv);
        stock.consume(variant, 3, UUID.randomUUID(), MARCH, ACTOR);
        stock.writeOff(UUID.randomUUID(), variant, 1, StockReason.WRITE_OFF_BROKEN, APRIL, null, ACTOR);
        return line;
    }

    private ReceiptSnapshot correctTo8000(ReceiptSnapshot line, int revision, boolean estimated) {
        return amended(line, revision, "the courier's bill was K8,000, not K800", CORRECTED_AT, "8000.00", "15000.00", EIGHT_THOUSAND, estimated);
    }

    /** What the books recognise in one month for one reason: the cost of the units that left in it, over ALL rows, so reversals net. */
    private BigDecimal cost(UUID variant, String reason, String month) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(-qty_delta * unit_cost), 0) FROM stock_movement
                 WHERE variant_id = ? AND reason = ? AND to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM') = ?""",
                BigDecimal.class, variant, reason, month);
    }

    private BigDecimal held(UUID variant) {
        return jdbc.queryForObject("SELECT coalesce(sum(qty_remaining * unit_cost), 0) FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL",
                BigDecimal.class, variant);
    }

    @Test
    @DisplayName("K800 corrected to K8,000 after 3 sold in March and 1 written off in April: each month moves by exactly its own units, and nothing reaches expenses")
    void thePlansCase() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot line = theShop(variant);
        assertThat(cost(variant, "SALE", "2026-03")).isEqualByComparingTo("3342.87");                  // 3 x 1,114.29
        assertThat(cost(variant, "WRITE_OFF_BROKEN", "2026-04")).isEqualByComparingTo("1114.29");
        assertThat(held(variant)).isEqualByComparingTo("3342.84");                                      // 3 x 1,114.28

        stock.applyAmendment(correctTo8000(line, 2, false), amendmentContext(6000, CORRECTED_AT));

        // March's sale: 3 units, now at K2,142.86 each. April's write-off: 1 unit, K2,142.86. What is held: 1 x 2,142.86 + 2 x 2,142.85.
        assertThat(cost(variant, "SALE", "2026-03")).as("March, netted over the reversal and its replacement").isEqualByComparingTo("6428.58");
        assertThat(cost(variant, "WRITE_OFF_BROKEN", "2026-04")).isEqualByComparingTo("2142.86");
        assertThat(held(variant)).isEqualByComparingTo("6428.56");
        // The corrected landed total, to the ngwee: what went out in March and April plus what is held.
        assertThat(cost(variant, "SALE", "2026-03").add(cost(variant, "WRITE_OFF_BROKEN", "2026-04")).add(held(variant)))
                .isEqualByComparingTo("15000.00");
        // Each month moved by exactly its own units' difference: March +3,085.71, April +1,028.57; held +3,085.72; together +7,200.00.
        assertThat(cost(variant, "SALE", "2026-03").subtract(new BigDecimal("3342.87"))).isEqualByComparingTo("3085.71");
        assertThat(cost(variant, "WRITE_OFF_BROKEN", "2026-04").subtract(new BigDecimal("1114.29"))).isEqualByComparingTo("1028.57");
        // Nothing is an expense: no other reason carries any cost but the lot transfers, which are not events in the shop.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_movement WHERE variant_id = ? AND reason NOT IN ('CONSIGNMENT_RECEIVED', 'SALE', 'WRITE_OFF_BROKEN', 'COST_CORRECTED')""",
                Integer.class, variant)).isZero();
        assertThat(stock.reconcile(variant).countMismatches()).isEmpty();
        assertThat(stock.reconcile(variant).ledgerMismatches()).isEmpty();
        assertThat(inventories.findById(variant).orElseThrow().onHand()).as("a correction of cost never moves the count").isEqualTo(3);
    }

    @Test
    @DisplayName("the old lots are kept and marked replaced, the new lots are the line's lots now, and the ledger says what happened")
    void theLotsAndTheLedger() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot line = theShop(variant);

        stock.applyAmendment(correctTo8000(line, 2, false), amendmentContext(6000, CORRECTED_AT));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_lot WHERE variant_id = ?", Integer.class, variant)).as("2 old + 2 new").isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT slice_position, qty_received, qty_remaining, unit_cost, revision FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL ORDER BY slice_position", variant))
                .extracting(r -> r.get("qty_received") + "x" + r.get("unit_cost") + "r" + r.get("revision") + "left" + r.get("qty_remaining"))
                .containsExactly("5x2142.86r2left1", "2x2142.85r2left2");
        assertThat(jdbc.queryForList("SELECT qty_remaining FROM cost_lot WHERE variant_id = ? AND superseded_at IS NOT NULL", variant))
                .allSatisfy(r -> assertThat(r.get("qty_remaining")).isEqualTo(0));
        // The two actions that drained the old lots keep their ids and dates; each has a reversal and a replacement.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE variant_id = ? AND role = 'RECUT_REVERSAL'", Integer.class, variant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE variant_id = ? AND role = 'RECUT_REPLACEMENT'", Integer.class, variant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE variant_id = ? AND role IN ('LOT_OPEN', 'LOT_CLOSE')", Integer.class, variant))
                .as("2 lots opened, 2 closed").isEqualTo(4);
        Map<String, Object> correction = jdbc.queryForMap("SELECT kind, reason, qty, note, actor FROM stock_action WHERE variant_id = ? AND kind = 'CORRECTION'", variant);
        assertThat(correction).containsEntry("reason", "COST_CORRECTED").containsEntry("qty", 7).containsEntry("note", "the courier's bill was K8,000, not K800");
    }

    @Test
    @DisplayName("a second correction works the same way: it re-cuts what the first one wrote, never what it already reversed")
    void twoCorrectionsInARow() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot line = theShop(variant);
        stock.applyAmendment(correctTo8000(line, 2, false), amendmentContext(6000, CORRECTED_AT));

        // The bill is corrected again: the courier charged K1,000 after all. Landed K8,000.00 = 5 x K1,142.86 + 2 x K1,142.85.
        ReceiptSnapshot again = amended(line, 3, "the courier's final bill: K1,000", "2026-11-14T09:00:00Z", "1000.00", "8000.00",
                List.of(new Slice(5, new BigDecimal("1142.86")), new Slice(2, new BigDecimal("1142.85"))), false);
        stock.applyAmendment(again, amendmentContext(6010, "2026-11-14T09:00:00Z"));

        assertThat(cost(variant, "SALE", "2026-03")).isEqualByComparingTo("3428.58");                   // 3 x 1,142.86
        assertThat(cost(variant, "WRITE_OFF_BROKEN", "2026-04")).isEqualByComparingTo("1142.86");
        assertThat(held(variant)).isEqualByComparingTo("3428.56");                                      // 1 x 1,142.86 + 2 x 1,142.85
        assertThat(cost(variant, "SALE", "2026-03").add(cost(variant, "WRITE_OFF_BROKEN", "2026-04")).add(held(variant)))
                .isEqualByComparingTo("8000.00");
        assertThat(stock.reconcile(variant).countMismatches()).isEmpty();
        assertThat(stock.reconcile(variant).ledgerMismatches()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL", Integer.class, variant)).isEqualTo(2);
    }

    @Test
    @DisplayName("a sale retried after the correction is still the same sale: nothing is taken twice")
    void aRetriedSaleAfterACorrection() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot line = sevenRingsAsKeyed(variant, RECEIVED);
        stock.applyReceipt(line, context(5001));
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(2);
        inventories.saveAndFlush(inv);
        UUID sale = UUID.randomUUID();
        stock.consume(variant, 2, sale, MARCH, ACTOR);
        stock.applyAmendment(correctTo8000(line, 2, false), amendmentContext(6000, CORRECTED_AT));

        Inventory again = stock.consume(variant, 2, sale, MARCH, ACTOR).orElseThrow();

        assertThat(again.onHand()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM live_movement WHERE action_id = ?", Integer.class, sale)).isEqualTo(1);
        assertThatThrownBy(() -> stock.consume(variant, 3, sale, MARCH, ACTOR)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a give-away taken back in part, then a correction: the outstanding is unchanged and a later take-back restores the right lot")
    void aTakeBackAfterACorrection() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot line = sevenRingsAsKeyed(variant, RECEIVED);
        stock.applyReceipt(line, context(5001));
        StockService.WriteOffResult gift = stock.writeOff(UUID.randomUUID(), variant, 6, StockReason.GIVEN_AWAY_PRIZE, APRIL, null, ACTOR).orElseThrow();
        stock.takeBack(UUID.randomUUID(), gift.actionId(), 2, null, null, ACTOR);

        stock.applyAmendment(correctTo8000(line, 2, false), amendmentContext(6000, CORRECTED_AT));
        StockService.TakeBackResult second = stock.takeBack(UUID.randomUUID(), gift.actionId(), 1, null, null, ACTOR).orElseThrow();

        assertThat(second.outstanding()).as("6 given, 2 back, 1 more back: 3 still out").isEqualTo(3);
        assertThat(stock.reconcile(variant).countMismatches()).isEmpty();
        assertThat(stock.reconcile(variant).ledgerMismatches()).isEmpty();
        // Everything is still worth the corrected landed total: what is out (at the given-away units' cost) plus what is held.
        assertThat(held(variant).add(jdbc.queryForObject("""
                SELECT coalesce(sum(-m.qty_delta * m.unit_cost), 0) FROM live_movement m JOIN cost_lot l ON l.id = m.lot_id
                 WHERE l.variant_id = ? AND m.reason <> 'CONSIGNMENT_RECEIVED'""", BigDecimal.class, variant)))
                .isEqualByComparingTo("15000.00");
    }

    @Test
    @DisplayName("a correction that clears the estimate says so on the new lots and on the rows they feed")
    void clearingTheEstimate() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot estimatedLine = new ReceiptSnapshot(UUID.randomUUID(), "C-000051", UUID.randomUUID(), variant, "RING-9", "R", "R - 9", "SELLABLE",
                UUID.randomUUID(), "Dubai Gold", RECEIVED, true, "ZMW", 7, new BigDecimal("7000.00"), new BigDecimal("800.00"), new BigDecimal("7800.00"),
                List.of(new Slice(4, new BigDecimal("1114.29")), new Slice(3, new BigDecimal("1114.28"))), 1, null, null, null);
        stock.applyReceipt(estimatedLine, context(5001));
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(1);
        inventories.saveAndFlush(inv);
        stock.consume(variant, 1, UUID.randomUUID(), MARCH, ACTOR);

        stock.applyAmendment(correctTo8000(estimatedLine, 2, false), amendmentContext(6000, CORRECTED_AT));

        assertThat(jdbc.queryForList("SELECT is_estimated FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL", variant))
                .allSatisfy(r -> assertThat(r.get("is_estimated")).isEqualTo(false));
        assertThat(jdbc.queryForList("SELECT is_estimated FROM live_movement WHERE variant_id = ? AND reason = 'SALE'", variant))
                .allSatisfy(r -> assertThat(r.get("is_estimated")).isEqualTo(false));
    }

    @Test
    @DisplayName("a correction that arrives BEFORE the receipt it corrects makes the stock from the corrected snapshot, and the receipt is refused as already made")
    void aCorrectionAsTheFirstEvent() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot line = sevenRingsAsKeyed(variant, RECEIVED);

        stock.applyAmendment(correctTo8000(line, 2, false), amendmentContext(6000, CORRECTED_AT));

        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(7);
        assertThat(held(variant)).isEqualByComparingTo("15000.00");
        assertThat(jdbc.queryForList("SELECT revision FROM cost_lot WHERE variant_id = ?", variant)).extracting(r -> r.get("revision")).containsOnly(2);
        assertThat(stock.reconcile(variant).countMismatches()).isEmpty();
        assertThat(stock.reconcile(variant).ledgerMismatches()).isEmpty();
        assertThatThrownBy(() -> stock.applyReceipt(line, context(5001))).isInstanceOf(IllegalStateException.class).hasMessageContaining("already");
    }

    @Test
    @DisplayName("a correction cannot change what was received: not the quantity, not the variant, and not backwards in revision")
    void whatACorrectionCannotChange() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot line = theShop(variant);
        stock.applyAmendment(correctTo8000(line, 2, false), amendmentContext(6000, CORRECTED_AT));

        ReceiptSnapshot moreUnits = new ReceiptSnapshot(line.consignmentId(), line.consignmentReference(), line.consignmentLineId(), variant, line.sku(),
                line.productTitle(), line.variantTitle(), line.kind(), line.supplierId(), line.supplierName(), line.receivedAt(), false, "ZMW", 8,
                new BigDecimal("8000.00"), new BigDecimal("8000.00"), new BigDecimal("16000.00"),
                List.of(new Slice(8, new BigDecimal("2000.00"))), 1, 3, "typo", "2026-11-14T09:00:00Z");
        assertThatThrownBy(() -> stock.applyAmendment(moreUnits, amendmentContext(6020, "2026-11-14T09:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quantity");
        assertThatThrownBy(() -> stock.applyAmendment(correctTo8000(line, 2, false), amendmentContext(6030, CORRECTED_AT)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("revision");

        assertThat(stock.reconcile(variant).countMismatches()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL", Integer.class, variant)).isEqualTo(2);
    }

    @Test
    @DisplayName("a correction that changes no cost writes nothing")
    void aCorrectionThatChangesNothing() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot line = theShop(variant);
        int rows = jdbc.queryForObject("SELECT count(*) FROM stock_movement", Integer.class);

        ReceiptSnapshot same = amended(line, 2, "looked again, it was right", CORRECTED_AT, "800.00", "7800.00",
                List.of(new Slice(4, new BigDecimal("1114.29")), new Slice(3, new BigDecimal("1114.28"))), false);
        stock.applyAmendment(same, amendmentContext(6000, CORRECTED_AT));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Integer.class)).isEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_lot WHERE variant_id = ?", Integer.class, variant)).isEqualTo(2);
    }
}
