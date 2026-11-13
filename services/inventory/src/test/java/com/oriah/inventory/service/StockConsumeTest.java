package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
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
import com.oriah.inventory.exception.InsufficientStockException;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.another;
import static com.oriah.inventory.service.StockTestData.context;
import static com.oriah.inventory.service.StockTestData.ring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A sale takes stock out of the OLDEST lots first, and the ledger says exactly which lots, at what cost, in the
 * same transaction as the count going down. One row per (sale line, lot drained): a sale line that spans two lots
 * writes two rows. A retry of the same sale is not a second sale. An older receipt that arrives after a sale
 * re-cuts the sale so that it took the older stock.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class StockConsumeTest {

    private static final String MONDAY = "2026-11-09T08:00:00.123456Z";
    private static final String THURSDAY = "2026-11-12T10:00:00.000001Z";

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

    /** The ring line received, and {@code reserve} of its 7 rings reserved. */
    private UUID ringsReserved(int reserve) {
        UUID variant = UUID.randomUUID();
        stock.applyReceipt(ring(variant, MONDAY), context(5001));
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(reserve);
        inventories.saveAndFlush(inv);
        return variant;
    }

    private List<Map<String, Object>> rows(UUID variant, String role) {
        return jdbc.queryForList("""
                SELECT m.qty_delta, m.unit_cost, m.reason, m.role, m.is_estimated, m.kind, m.cost_method, m.cost_basis, m.lot_id,
                       m.occurred_at, m.recorded_at, m.actor, m.action_id, l.slice_position
                  FROM stock_movement m JOIN cost_lot l ON l.id = m.lot_id
                 WHERE m.variant_id = ? AND m.role = ? AND m.reason <> 'CONSIGNMENT_RECEIVED' ORDER BY m.id""", variant, role);
    }

    private int remaining(UUID variant, int slice) {
        return jdbc.queryForObject("SELECT qty_remaining FROM cost_lot WHERE variant_id = ? AND slice_position = ? AND superseded_at IS NULL",
                Integer.class, variant, slice);
    }

    @Test
    @DisplayName("a sale of 5 rings takes 4 at K1,528.99 and 1 at K1,528.98: two ledger rows, the count down by 5")
    void aSaleSpansTwoLots() {
        UUID variant = ringsReserved(5);
        UUID saleLine = UUID.randomUUID();

        Inventory after = stock.consume(variant, 5, saleLine, null, ACTOR).orElseThrow();

        assertThat(after.onHand()).isEqualTo(2);
        assertThat(after.reserved()).isZero();
        assertThat(remaining(variant, 1)).isZero();
        assertThat(remaining(variant, 2)).isEqualTo(2);
        List<Map<String, Object>> rows = rows(variant, "ORIGINAL");
        assertThat(rows).extracting(r -> r.get("qty_delta")).containsExactly(-4, -1);
        assertThat(rows).extracting(r -> r.get("slice_position")).containsExactly(1, 2);
        assertThat(rows.get(0)).containsEntry("reason", "SALE").containsEntry("kind", "SELLABLE").containsEntry("is_estimated", false)
                .containsEntry("cost_method", "FIFO").containsEntry("cost_basis", "RECEIVED_AT").containsEntry("actor", ACTOR)
                .containsEntry("action_id", saleLine);
        BigDecimal cost = rows.stream().map(r -> ((BigDecimal) r.get("unit_cost")).multiply(BigDecimal.valueOf(-(Integer) r.get("qty_delta"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(cost).as("what the sale cost, from the lots it came out of").isEqualByComparingTo("7644.94");
        assertThat(jdbc.queryForMap("SELECT kind, reason, qty FROM stock_action WHERE id = ?", saleLine))
                .containsEntry("kind", "SALE").containsEntry("reason", "SALE").containsEntry("qty", 5);
    }

    @Test
    @DisplayName("stock from an older receipt is sold before stock from a newer one")
    void oldestReceiptFirst() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot older = ring(variant, MONDAY);
        ReceiptSnapshot newer = another(older, THURSDAY, 1);
        stock.applyReceipt(newer, context(5010));
        stock.applyReceipt(older, context(5004));
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(8);
        inventories.saveAndFlush(inv);

        stock.consume(variant, 8, UUID.randomUUID(), null, ACTOR);

        // 7 from the older receipt, then 1 from the newer one's dearer lot.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_movement m JOIN cost_lot l ON l.id = m.lot_id
                 WHERE m.reason = 'SALE' AND l.consignment_line_id = ?""", Integer.class, older.consignmentLineId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT sum(-m.qty_delta) FROM stock_movement m JOIN cost_lot l ON l.id = m.lot_id
                 WHERE m.reason = 'SALE' AND l.consignment_line_id = ?""", Integer.class, newer.consignmentLineId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the same sale sent again is the same sale: nothing more is taken")
    void aRetryIsNotASecondSale() {
        UUID variant = ringsReserved(5);
        UUID saleLine = UUID.randomUUID();
        stock.consume(variant, 3, saleLine, null, ACTOR);

        Inventory again = stock.consume(variant, 3, saleLine, null, ACTOR).orElseThrow();

        assertThat(again.onHand()).isEqualTo(4);
        assertThat(again.reserved()).isEqualTo(2);
        assertThat(rows(variant, "ORIGINAL")).hasSize(1);
    }

    @Test
    @DisplayName("a sale line is used once: a different quantity, or another variant, is refused")
    void aSaleLineIsUsedOnce() {
        UUID variant = ringsReserved(5);
        UUID other = ringsReserved(2);
        UUID saleLine = UUID.randomUUID();
        stock.consume(variant, 3, saleLine, null, ACTOR);

        assertThatThrownBy(() -> stock.consume(variant, 2, saleLine, null, ACTOR)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already");
        assertThatThrownBy(() -> stock.consume(other, 3, saleLine, null, ACTOR)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already");
        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(4);
        assertThat(inventories.findById(other).orElseThrow().onHand()).isEqualTo(7);
    }

    @Test
    @DisplayName("more than was reserved is refused and writes nothing")
    void beyondTheReservation() {
        UUID variant = ringsReserved(2);

        assertThatThrownBy(() -> stock.consume(variant, 3, UUID.randomUUID(), null, ACTOR))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_action WHERE kind = 'SALE'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("an unknown variant answers nothing, so the caller can say 404")
    void anUnknownVariant() {
        assertThat(stock.consume(UUID.randomUUID(), 1, UUID.randomUUID(), null, ACTOR)).isEmpty();
    }

    @Test
    @DisplayName("an estimated lot's cost is flagged as estimated on the sale it fed")
    void estimatedFollowsTheLot() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot opening = new ReceiptSnapshot(UUID.randomUUID(), "C-000001", UUID.randomUUID(), variant, "RING-1", null, "R - 1",
                "SELLABLE", null, null, MONDAY, true, "ZMW", 3, new BigDecimal("300.00"), new BigDecimal("0.00"), new BigDecimal("300.00"),
                List.of(new ReceiptSnapshot.Slice(3, new BigDecimal("100.00"))), 1, null, null, null);
        stock.applyReceipt(opening, context(1));
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(1);
        inventories.saveAndFlush(inv);

        stock.consume(variant, 1, UUID.randomUUID(), null, ACTOR);

        assertThat(rows(variant, "ORIGINAL").get(0)).containsEntry("is_estimated", true);
    }

    @Test
    @DisplayName("a sale keyed late keeps its true date and is never refused for it")
    void aLateKeyedSaleKeepsItsDate() {
        UUID variant = ringsReserved(1);
        Instant march = Instant.parse("2026-03-05T09:30:00Z");

        stock.consume(variant, 1, UUID.randomUUID(), march, ACTOR);

        assertThat(((java.sql.Timestamp) rows(variant, "ORIGINAL").get(0).get("occurred_at")).toInstant()).isEqualTo(march);
    }

    @Test
    @DisplayName("a sale dated in the future is refused")
    void aSaleInTheFuture() {
        UUID variant = ringsReserved(1);

        assertThatThrownBy(() -> stock.consume(variant, 1, UUID.randomUUID(), Instant.now().plusSeconds(3600), ACTOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future");
        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(7);
    }

    @Test
    @DisplayName("an OLDER receipt that arrives after a sale re-cuts the sale to have taken the older stock, in the sale's own month")
    void anOlderReceiptArrivesLate() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot older = ring(variant, MONDAY);
        ReceiptSnapshot newer = another(older, THURSDAY, 1);
        stock.applyReceipt(newer, context(5010));
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(3);
        inventories.saveAndFlush(inv);
        Instant march = Instant.parse("2026-03-05T09:30:00Z");
        UUID saleLine = UUID.randomUUID();
        stock.consume(variant, 3, saleLine, march, ACTOR);
        assertThat(rows(variant, "ORIGINAL")).as("the sale took the only stock there was: the newer receipt's").hasSize(1);

        stock.applyReceipt(older, context(5004));

        // The sale is now taken from the older receipt, by reversing what was written and writing what should have been.
        assertThat(rows(variant, "RECUT_REVERSAL")).hasSize(1);
        assertThat(rows(variant, "RECUT_REVERSAL").get(0)).containsEntry("reason", "SALE").containsEntry("qty_delta", 3);
        assertThat(rows(variant, "RECUT_REPLACEMENT")).hasSize(1);
        Map<String, Object> replacement = rows(variant, "RECUT_REPLACEMENT").get(0);
        assertThat(replacement).containsEntry("reason", "SALE").containsEntry("qty_delta", -3).containsEntry("action_id", saleLine);
        assertThat(((java.sql.Timestamp) replacement.get("occurred_at")).toInstant()).as("the sale's month, not the day of the re-cut").isEqualTo(march);
        assertThat(jdbc.queryForObject("""
                SELECT l.consignment_line_id FROM cost_lot l WHERE l.id = ?""", UUID.class, replacement.get("lot_id")))
                .isEqualTo(older.consignmentLineId());
        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(qty_remaining), 0) FROM cost_lot WHERE variant_id = ?", Integer.class, variant)).isEqualTo(11);
    }
}
