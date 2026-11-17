package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.events.ReceiptSnapshot;
import com.oriah.inventory.events.ReceiptSnapshot.Slice;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService.Line;
import com.oriah.inventory.service.ReturnedPieceService.Decision;
import com.oriah.inventory.service.SaleReversalService.Piece;
import com.oriah.inventory.service.SaleReversalService.Request;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What becomes of a piece a customer sent back (Phase 12), once somebody has looked at it. Until then it sits in the lot it left, counted
 * and not for sale. Then, once and for good:
 *
 * <ul>
 *   <li><b>fine</b>: it is ordinary stock again. Nothing moves and no cost is recognised;</li>
 *   <li><b>not sellable</b>: it is written off, out of the lot it went back to, at THAT lot's cost.</li>
 * </ul>
 *
 * The stock here: a ring with two lots, the older lot A of ONE ring at K62.00 and lot B of five at K65.50; a sale of two rings took lot A's
 * ring and one of lot B's, and both came back: the first to lot B, the second to lot A.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ReturnedPieceServiceTest {

    private static final Instant SOLD_ON = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant BACK_ON = Instant.parse("2026-11-12T09:00:00Z");
    private static final Instant LOOKED_AT = Instant.parse("2026-11-12T09:30:00Z");
    private static final String ORDER_SYSTEM = "user:sa-order";

    @Autowired
    private ReturnedPieceService pieces;
    @Autowired
    private SaleReversalService reversals;
    @Autowired
    private ReservationService reservations;
    @Autowired
    private BoxCommitmentService commitments;
    @Autowired
    private StockService stock;
    @Autowired
    private InventoryRepository inventories;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID ring;
    private UUID product;
    private UUID fromB;
    private UUID fromA;

    @BeforeEach
    void twoRingsCameBack() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        ring = UUID.randomUUID();
        UUID box = UUID.randomUUID();
        stock.applyReceipt(rings(ring, 1, "62.00", "2026-11-08T08:00:00Z"), context(1));
        stock.applyReceipt(rings(ring, 5, "65.50", "2026-11-09T08:00:00Z"), context(2));
        stock.applyReceipt(box(box, "2026-11-09T09:00:00.123456Z"), context(3));
        product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        UUID order = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        Line sale = new Line(UUID.randomUUID(), ring, product, 2, UUID.randomUUID(), null);
        reservations.reserve(reservation, order, List.of(sale), null, ACTOR);
        reservations.consume(reservation, SOLD_ON, ACTOR);
        fromB = UUID.randomUUID();
        fromA = UUID.randomUUID();
        reversals.reverse(new Request(UUID.randomUUID(), "RETURN", order, null, BACK_ON, List.of(), List.of(),
                List.of(new Piece(fromB, sale.saleLineId(), ring), new Piece(fromA, sale.saleLineId(), ring))), ORDER_SYSTEM);
    }

    private static ReceiptSnapshot rings(UUID variant, int qty, String cost, String receivedAt) {
        BigDecimal total = new BigDecimal(cost).multiply(BigDecimal.valueOf(qty));
        return new ReceiptSnapshot(UUID.randomUUID(), "C-000061", UUID.randomUUID(), variant, "RING-M", "Aurelia Ring", "Aurelia Ring - M", "SELLABLE",
                UUID.randomUUID(), "Dubai Gold", receivedAt, false, "ZMW", qty, total, new BigDecimal("0.00"), total,
                List.of(new Slice(qty, new BigDecimal(cost))), 1, null, null, null);
    }

    private Inventory row() {
        return inventories.findById(ring).orElseThrow();
    }

    private List<String> lots() {
        return jdbc.queryForList("""
                SELECT unit_cost || ' x ' || qty_remaining FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL
                 ORDER BY received_at, fifo_tiebreak, slice_position""", String.class, ring);
    }

    private static UUID named(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private int costEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_cost_event", Integer.class);
    }

    private void assertConsistent() {
        var report = stock.reconcile(null);
        assertThat(report.countMismatches()).isEmpty();
        assertThat(report.ledgerMismatches()).isEmpty();
        assertThat(report.quarantineMismatches()).isEmpty();
    }

    @Test
    @DisplayName("fine: the piece is ordinary stock again. Nothing moves, no cost is recognised, and the next sale may take it")
    void fineIsReleasedAndMovesNoCost() {
        assertThat(lots()).containsExactly("62.00 x 1", "65.50 x 5");
        assertThat(row().free()).isEqualTo(4);
        int announced = costEvents();

        Decision done = pieces.decide(fromA, "RELEASED", null, LOOKED_AT, ORDER_SYSTEM);

        assertThat(done.replay()).isFalse();
        assertThat(done.outcome()).isEqualTo("RELEASED");
        assertThat(done.occurredAt()).isEqualTo(LOOKED_AT);
        assertThat(lots()).as("nothing moved").containsExactly("62.00 x 1", "65.50 x 5");
        assertThat(row().onHand()).isEqualTo(6);
        assertThat(row().quarantined()).isEqualTo(1);
        assertThat(row().free()).isEqualTo(5);
        UUID release = named("piece-release:" + fromA);
        assertThat(jdbc.queryForMap("SELECT kind, reason, qty, assesses_action_id, actor FROM stock_action WHERE id = ?", release))
                .containsEntry("kind", "RELEASE").containsEntry("reason", "RETURN").containsEntry("qty", 1).containsEntry("assesses_action_id", fromA)
                .containsEntry("actor", ORDER_SYSTEM);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE action_id = ?", Integer.class, release)).isZero();
        assertThat(costEvents()).as("finance is told nothing").isEqualTo(announced);
        assertThat(jdbc.queryForMap("SELECT outcome, outcome_action_id, outcome_actor FROM returned_piece WHERE piece_id = ?", fromA))
                .containsEntry("outcome", "RELEASED").containsEntry("outcome_action_id", release).containsEntry("outcome_actor", ORDER_SYSTEM);

        // Oldest first again: the next sale takes the released ring out of lot A; lot B's ring that is still waiting stays where it is.
        Line next = new Line(UUID.randomUUID(), ring, product, 5, UUID.randomUUID(), null);
        UUID hold = UUID.randomUUID();
        reservations.reserve(hold, UUID.randomUUID(), List.of(next), null, ACTOR);
        reservations.consume(hold, LOOKED_AT.plusSeconds(60), ACTOR);
        assertThat(lots()).containsExactly("62.00 x 0", "65.50 x 1");
        assertConsistent();
    }

    @Test
    @DisplayName("not sellable: the piece is written off out of the lot it went back to, at that lot's cost (K62.00 for lot A's, K65.50 for lot B's)")
    void writtenOffAtTheLotItWentBackTo() {
        int announced = costEvents();

        Decision done = pieces.decide(fromA, "WRITTEN_OFF", null, LOOKED_AT, ORDER_SYSTEM);

        assertThat(done.outcome()).isEqualTo("WRITTEN_OFF");
        assertThat(lots()).as("lot A's ring is gone; lot B still holds the other returned ring").containsExactly("62.00 x 0", "65.50 x 5");
        UUID exit = named("piece-exit:" + fromA);
        assertThat(jdbc.queryForMap("SELECT kind, reason, qty, assesses_action_id FROM stock_action WHERE id = ?", exit))
                .containsEntry("kind", "RETURN_EXIT").containsEntry("reason", "WRITE_OFF_RETURNED").containsEntry("qty", 1).containsEntry("assesses_action_id", fromA);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT reason, role, qty_delta, unit_cost, occurred_at FROM stock_movement WHERE action_id = ?", exit);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("reason", "WRITE_OFF_RETURNED").containsEntry("role", "ORIGINAL").containsEntry("qty_delta", -1);
            assertThat((BigDecimal) row.get("unit_cost")).isEqualByComparingTo("62.00");
            assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(LOOKED_AT);
        });
        assertThat(row().onHand()).isEqualTo(5);
        assertThat(row().quarantined()).isEqualTo(1);
        assertThat(row().free()).isEqualTo(4);
        assertThat(costEvents()).as("the write-off is announced to finance").isEqualTo(announced + 1);

        pieces.decide(fromB, "WRITTEN_OFF", null, null, ORDER_SYSTEM);
        assertThat(lots()).containsExactly("62.00 x 0", "65.50 x 4");
        assertThat((BigDecimal) jdbc.queryForObject("SELECT unit_cost FROM stock_movement WHERE action_id = ?", BigDecimal.class, named("piece-exit:" + fromB)))
                .isEqualByComparingTo("65.50");
        assertThat(row().quarantined()).isZero();
        assertConsistent();
    }

    @Test
    @DisplayName("a returned piece that was written off is not taken back through the ordinary write-off door")
    void aWrittenOffReturnCannotBeTakenBack() {
        pieces.decide(fromA, "WRITTEN_OFF", null, LOOKED_AT, ORDER_SYSTEM);

        assertThatThrownBy(() -> stock.takeBack(UUID.randomUUID(), named("piece-exit:" + fromA), 1, null, "found it after all", ACTOR))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RETURN_EXIT");
        assertThat(row().onHand()).isEqualTo(5);
    }

    @Test
    @DisplayName("a piece is decided once: the same answer again is a repeat and changes nothing, another answer is refused, and a piece nobody put back is not found")
    void onceOnly() {
        pieces.decide(fromA, "RELEASED", null, LOOKED_AT, ORDER_SYSTEM);

        Decision again = pieces.decide(fromA, "RELEASED", null, LOOKED_AT.plusSeconds(600), ORDER_SYSTEM);

        assertThat(again.replay()).isTrue();
        assertThat(again.occurredAt()).as("what was recorded, not what the repeat said").isEqualTo(LOOKED_AT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_action WHERE assesses_action_id = ?", Integer.class, fromA)).isEqualTo(1);
        assertThatThrownBy(() -> pieces.decide(fromA, "WRITTEN_OFF", null, LOOKED_AT, ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("piece-outcome-recorded"));
        assertThatThrownBy(() -> pieces.decide(UUID.randomUUID(), "RELEASED", null, LOOKED_AT, ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("returned-piece-not-found"));
        assertThat(row().quarantined()).isEqualTo(1);
        assertConsistent();
    }

    @Test
    @DisplayName("a decision is dated no earlier than the piece came back and not in the future; an answer nobody knows is refused")
    void whenAndWhat() {
        assertThatThrownBy(() -> pieces.decide(fromA, "RELEASED", null, BACK_ON.minusSeconds(1), ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("invalid-occurred-at"));
        assertThatThrownBy(() -> pieces.decide(fromA, "RELEASED", null, TestClockConfiguration.T0.plusSeconds(3600), ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("invalid-occurred-at"));
        assertThatThrownBy(() -> pieces.decide(fromA, "MELTED", null, LOOKED_AT, ORDER_SYSTEM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pieces.decide(fromA, "RELEASED", UUID.randomUUID(), LOOKED_AT, ORDER_SYSTEM)).isInstanceOf(IllegalArgumentException.class);
        assertThat(row().quarantined()).as("nothing was decided").isEqualTo(2);
    }
}
