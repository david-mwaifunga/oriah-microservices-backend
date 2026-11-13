package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
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
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.another;
import static com.oriah.inventory.service.StockTestData.context;
import static com.oriah.inventory.service.StockTestData.ring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stock leaves with no sale (lost, stolen, broken, or given to a model, an influencer, as a gift or a prize), and some of
 * it comes back. Each takes the OLDEST lots at their cost and releases that cost into the month it happened: it is the
 * same kind of ledger row as a sale, with a reason of its own, so a report can tell them apart. Only stock nobody has
 * reserved can go. A retry is not a second write-off. A take-back returns units to the lots they came out of, newest
 * first, at the cost they left at, and only ever as many as are still out.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class StockWriteOffTest {

    private static final String MONDAY = "2026-11-09T08:00:00.123456Z";
    private static final String THURSDAY = "2026-11-12T10:00:00.000001Z";
    private static final Instant TUESDAY = Instant.parse("2026-11-10T12:00:00Z");

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

    private UUID rings() {
        UUID variant = UUID.randomUUID();
        stock.applyReceipt(ring(variant, MONDAY), context(5001));
        return variant;
    }

    private StockService.WriteOffResult writeOff(UUID variant, int qty, StockReason reason, Instant when) {
        return stock.writeOff(UUID.randomUUID(), variant, qty, reason, when, null, ACTOR).orElseThrow();
    }

    private int remaining(UUID variant, int slice) {
        return jdbc.queryForObject("SELECT qty_remaining FROM cost_lot WHERE variant_id = ? AND slice_position = ? AND superseded_at IS NULL",
                Integer.class, variant, slice);
    }

    private List<Map<String, Object>> rows(UUID variant, String reason) {
        return jdbc.queryForList("""
                SELECT m.qty_delta, m.unit_cost, m.reason, m.role, m.reverses_movement_id, m.id, m.occurred_at, l.slice_position
                  FROM stock_movement m JOIN cost_lot l ON l.id = m.lot_id
                 WHERE m.variant_id = ? AND m.reason = ? ORDER BY m.id""", variant, reason);
    }

    @Test
    @DisplayName("2 rings lost: they come out of the oldest lot at its cost, the count falls, and the ledger says why")
    void aWriteOff() {
        UUID variant = rings();

        StockService.WriteOffResult result = writeOff(variant, 2, StockReason.WRITE_OFF_LOST, TUESDAY);

        assertThat(result.onHand()).isEqualTo(5);
        assertThat(result.available()).isEqualTo(5);
        assertThat(remaining(variant, 1)).isEqualTo(2);
        List<Map<String, Object>> rows = rows(variant, "WRITE_OFF_LOST");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("qty_delta", -2).containsEntry("role", "ORIGINAL");
        assertThat((BigDecimal) rows.get(0).get("unit_cost")).isEqualByComparingTo("1528.99");
        assertThat(((Timestamp) rows.get(0).get("occurred_at")).toInstant()).isEqualTo(TUESDAY);
        assertThat(jdbc.queryForMap("SELECT kind, reason FROM stock_action WHERE id = ?", result.actionId()))
                .containsEntry("kind", "WRITE_OFF").containsEntry("reason", "WRITE_OFF_LOST");
    }

    @Test
    @DisplayName("a give-away of 6 spans two lots, and is its own kind of action")
    void aGiveAwaySpansTwoLots() {
        UUID variant = rings();

        StockService.WriteOffResult result = writeOff(variant, 6, StockReason.GIVEN_AWAY_PRIZE, null);

        assertThat(rows(variant, "GIVEN_AWAY_PRIZE")).extracting(r -> r.get("qty_delta")).containsExactly(-4, -2);
        assertThat(jdbc.queryForObject("SELECT kind FROM stock_action WHERE id = ?", String.class, result.actionId())).isEqualTo("GIVE_AWAY");
        assertThat(result.onHand()).isEqualTo(1);
    }

    @Test
    @DisplayName("only stock nobody has reserved can go: a reserved unit belongs to a customer")
    void onlyAvailableStock() {
        UUID variant = rings();
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(6);
        inventories.saveAndFlush(inv);

        assertThatThrownBy(() -> writeOff(variant, 2, StockReason.WRITE_OFF_BROKEN, null)).isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("only 1 available");
        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_action WHERE kind = 'WRITE_OFF'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a person may write off or give away only with one of those reasons: not a sale, not a correction")
    void onlyWriteOffReasons() {
        UUID variant = rings();

        assertThatThrownBy(() -> writeOff(variant, 1, StockReason.SALE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writeOff(variant, 1, StockReason.COST_CORRECTED, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writeOff(variant, 1, StockReason.TAKEN_BACK, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the same request sent again is answered again and takes nothing; the same id for something else is refused")
    void aRetryIsNotASecondWriteOff() {
        UUID variant = rings();
        UUID request = UUID.randomUUID();
        StockService.WriteOffResult first = stock.writeOff(request, variant, 2, StockReason.WRITE_OFF_LOST, null, "left at the market", ACTOR).orElseThrow();

        StockService.WriteOffResult again = stock.writeOff(request, variant, 2, StockReason.WRITE_OFF_LOST, null, "left at the market", ACTOR).orElseThrow();

        assertThat(again.actionId()).isEqualTo(first.actionId());
        assertThat(again.replay()).isTrue();
        assertThat(first.replay()).isFalse();
        assertThat(again.occurredAt()).as("the time the first one recorded, not a new now").isEqualTo(first.occurredAt());
        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(5);
        assertThatThrownBy(() -> stock.writeOff(request, variant, 3, StockReason.WRITE_OFF_LOST, null, null, ACTOR))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already");
        assertThatThrownBy(() -> stock.writeOff(request, variant, 2, StockReason.WRITE_OFF_STOLEN, null, "left at the market", ACTOR))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already");
    }

    @Test
    @DisplayName("a request id used on another variant is refused: an action belongs to one variant")
    void anActionBelongsToOneVariant() {
        UUID variant = rings();
        UUID other = rings();
        UUID request = UUID.randomUUID();
        stock.writeOff(request, variant, 1, StockReason.WRITE_OFF_LOST, null, null, ACTOR);

        assertThatThrownBy(() -> stock.writeOff(request, other, 1, StockReason.WRITE_OFF_LOST, null, null, ACTOR))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already");
        assertThat(inventories.findById(other).orElseThrow().onHand()).isEqualTo(7);
    }

    @Test
    @DisplayName("stock cannot have left before it arrived, and nothing happens in the future")
    void datesAreChecked() {
        UUID variant = rings();

        assertThatThrownBy(() -> writeOff(variant, 1, StockReason.WRITE_OFF_LOST, Instant.parse("2026-11-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("arrived");
        assertThatThrownBy(() -> writeOff(variant, 1, StockReason.WRITE_OFF_LOST, Instant.now().plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future");
        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_action WHERE kind = 'WRITE_OFF'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("an unknown variant answers nothing, so the caller can say 404")
    void anUnknownVariant() {
        assertThat(stock.writeOff(UUID.randomUUID(), UUID.randomUUID(), 1, StockReason.WRITE_OFF_LOST, null, null, ACTOR)).isEmpty();
    }

    @Test
    @DisplayName("a give-away comes back: units return to the lot they left, at the same cost, newest-drained first")
    void aTakeBack() {
        UUID variant = rings();
        StockService.WriteOffResult gift = writeOff(variant, 6, StockReason.GIVEN_AWAY_MODEL, TUESDAY);   // 4 from lot 1, 2 from lot 2

        StockService.TakeBackResult back = stock.takeBack(UUID.randomUUID(), gift.actionId(), 3, null, null, ACTOR).orElseThrow();

        // The newest-drained units go back first: both from lot 2, then one from lot 1.
        List<Map<String, Object>> rows = rows(variant, "TAKEN_BACK");
        assertThat(rows).extracting(r -> r.get("qty_delta")).containsExactly(2, 1);
        assertThat(rows).extracting(r -> r.get("slice_position")).containsExactly(2, 1);
        assertThat((BigDecimal) rows.get(0).get("unit_cost")).isEqualByComparingTo("1528.98");
        assertThat((BigDecimal) rows.get(1).get("unit_cost")).isEqualByComparingTo("1528.99");
        assertThat(rows).allSatisfy(r -> assertThat(r.get("reverses_movement_id")).isNotNull());
        assertThat(back.onHand()).isEqualTo(4);
        assertThat(remaining(variant, 1)).isEqualTo(1);
        assertThat(remaining(variant, 2)).isEqualTo(3);
        assertThat(back.outstanding()).isEqualTo(3);
        assertThat(jdbc.queryForMap("SELECT kind, reason, reverses_action_id FROM stock_action WHERE id = ?", back.actionId()))
                .containsEntry("kind", "TAKE_BACK").containsEntry("reason", "TAKEN_BACK").containsEntry("reverses_action_id", gift.actionId());
    }

    @Test
    @DisplayName("only as many as are still out can come back, however many times it is asked")
    void aTakeBackNeverExceedsWhatIsOut() {
        UUID variant = rings();
        StockService.WriteOffResult gift = writeOff(variant, 3, StockReason.GIVEN_AWAY_GIFT, null);
        stock.takeBack(UUID.randomUUID(), gift.actionId(), 2, null, null, ACTOR);

        assertThatThrownBy(() -> stock.takeBack(UUID.randomUUID(), gift.actionId(), 2, null, null, ACTOR))
                .isInstanceOf(InsufficientStockException.class).hasMessageContaining("only 1");
        stock.takeBack(UUID.randomUUID(), gift.actionId(), 1, null, null, ACTOR);

        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(7);
        assertThatThrownBy(() -> stock.takeBack(UUID.randomUUID(), gift.actionId(), 1, null, null, ACTOR))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("a lost or broken piece can be taken back too: the owner decides, and a mistaken write-off needs an undo")
    void anyWriteOffCanBeTakenBack() {
        UUID variant = rings();
        StockService.WriteOffResult lost = writeOff(variant, 1, StockReason.WRITE_OFF_LOST, null);

        stock.takeBack(UUID.randomUUID(), lost.actionId(), 1, null, "found it", ACTOR);

        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(7);
    }

    @Test
    @DisplayName("a sale is not a write-off: only a write-off or a give-away can be taken back, and an unknown one is nothing")
    void onlyWriteOffsCanBeTakenBack() {
        UUID variant = rings();
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(1);
        inventories.saveAndFlush(inv);
        UUID sale = UUID.randomUUID();
        stock.consume(variant, 1, sale, null, ACTOR);

        assertThatThrownBy(() -> stock.takeBack(UUID.randomUUID(), sale, 1, null, null, ACTOR))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("write-off or a give-away");
        assertThat(stock.takeBack(UUID.randomUUID(), UUID.randomUUID(), 1, null, null, ACTOR)).isEmpty();
    }

    @Test
    @DisplayName("a take-back is retried safely, and cannot be dated before what it takes back")
    void takeBackRetriesAndDates() {
        UUID variant = rings();
        StockService.WriteOffResult gift = writeOff(variant, 3, StockReason.GIVEN_AWAY_GIFT, TUESDAY);
        UUID request = UUID.randomUUID();
        stock.takeBack(request, gift.actionId(), 1, null, null, ACTOR);

        StockService.TakeBackResult again = stock.takeBack(request, gift.actionId(), 1, null, null, ACTOR).orElseThrow();

        assertThat(again.replay()).isTrue();
        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(5);
        assertThatThrownBy(() -> stock.takeBack(UUID.randomUUID(), gift.actionId(), 1, TUESDAY.minusSeconds(60), null, ACTOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("before");
    }

    @Test
    @DisplayName("an OLDER receipt arriving after a give-away and a take-back re-cuts BOTH, and the take-back still restores what the give-away took")
    void anOlderReceiptRecutsAGiveAwayAndItsTakeBack() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot older = ring(variant, MONDAY);
        ReceiptSnapshot newer = another(older, THURSDAY, 1);
        stock.applyReceipt(newer, context(5010));
        StockService.WriteOffResult gift = writeOff(variant, 3, StockReason.GIVEN_AWAY_INFLUENCER, null);
        StockService.TakeBackResult back = stock.takeBack(UUID.randomUUID(), gift.actionId(), 1, null, null, ACTOR).orElseThrow();

        stock.applyReceipt(older, context(5004));

        // The give-away now came out of the older receipt's lot (the verify inside the receipt proved lots == ledger == count).
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM live_movement m JOIN cost_lot l ON l.id = m.lot_id
                 WHERE m.action_id = ? AND l.consignment_line_id = ?""", Integer.class, gift.actionId(), older.consignmentLineId())).isEqualTo(1);
        // Its take-back is re-cut with it, and points at a LIVE row of the give-away.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM live_movement t JOIN live_movement g ON g.id = t.reverses_movement_id
                 WHERE t.action_id = ? AND g.action_id = ?""", Integer.class, back.actionId(), gift.actionId())).isEqualTo(1);
        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(12);
        assertThat(jdbc.queryForObject("""
                SELECT -sum(qty_delta) FROM live_movement WHERE action_id = ? OR action_id = ?""", Integer.class, gift.actionId(), back.actionId()))
                .as("3 given away, 1 taken back: 2 are still out").isEqualTo(2);
    }
}
