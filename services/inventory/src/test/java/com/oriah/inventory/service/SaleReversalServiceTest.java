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

import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.events.ReceiptSnapshot;
import com.oriah.inventory.events.ReceiptSnapshot.Slice;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService.Line;
import com.oriah.inventory.service.SaleReversalService.BoxLine;
import com.oriah.inventory.service.SaleReversalService.GoodsLine;
import com.oriah.inventory.service.SaleReversalService.Piece;
import com.oriah.inventory.service.SaleReversalService.Request;
import com.oriah.inventory.service.SaleReversalService.Reversal;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A sale put back into stock (Phase 12): the order system asks, once per correction, and one transaction answers.
 *
 * <p>A paid order CANCELLED before it left the shop: its pieces and its boxes go back to the lots they came out of, at the cost they left
 * at, and are ordinary stock again; the boxes go back into the product's set-aside when that is where they came from and it still stands.
 * A parcel that came BACK: each piece goes back to its own lot at its own cost too (the newest lot its sale drained first), but it is not
 * for sale until somebody has looked at it; the box stays sold. Asking again with the same id changes nothing.
 *
 * <p>The stock here: a ring with two lots, the older lot A of ONE ring at K62.00 and lot B of five at K65.50, and 200 gift boxes.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class SaleReversalServiceTest {

    private static final Instant SOLD_ON = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant BACK_ON = Instant.parse("2026-11-12T09:00:00Z");
    private static final String ORDER_SYSTEM = "service:order";

    @Autowired
    private SaleReversalService reversals;
    @Autowired
    private ReservationService reservations;
    @Autowired
    private BoxCommitmentService commitments;
    @Autowired
    private BoxSwapService swaps;
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
    private UUID box;
    private UUID velvetBox;
    private UUID product;
    private UUID order;
    private UUID reservation;
    private Line sale;

    @BeforeEach
    void aPaidSaleOfTwoRingsThatDrainedTwoLots() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        ring = UUID.randomUUID();
        box = UUID.randomUUID();
        velvetBox = UUID.randomUUID();
        stock.applyReceipt(rings(ring, 1, "62.00", "2026-11-08T08:00:00Z"), context(1));
        stock.applyReceipt(rings(ring, 5, "65.50", "2026-11-09T08:00:00Z"), context(2));
        stock.applyReceipt(box(box, "2026-11-09T09:00:00.123456Z"), context(3));
        stock.applyReceipt(box(velvetBox, "2026-11-09T10:00:00Z"), context(4));
        product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        order = UUID.randomUUID();
        reservation = UUID.randomUUID();
        sale = new Line(UUID.randomUUID(), ring, product, 2, UUID.randomUUID(), null);
        reservations.reserve(reservation, order, List.of(sale), null, ACTOR);
        reservations.consume(reservation, SOLD_ON, ACTOR);
    }

    private static ReceiptSnapshot rings(UUID variant, int qty, String cost, String receivedAt) {
        BigDecimal total = new BigDecimal(cost).multiply(BigDecimal.valueOf(qty));
        return new ReceiptSnapshot(UUID.randomUUID(), "C-000061", UUID.randomUUID(), variant, "RING-M", "Aurelia Ring", "Aurelia Ring - M", "SELLABLE",
                UUID.randomUUID(), "Dubai Gold", receivedAt, false, "ZMW", qty, total, new BigDecimal("0.00"), total,
                List.of(new Slice(qty, new BigDecimal(cost))), 1, null, null, null);
    }

    private Inventory row(UUID variant) {
        return inventories.findById(variant).orElseThrow();
    }

    private Request cancellation(UUID id) {
        return new Request(id, "CANCELLATION", order, reservation, BACK_ON, List.of(new GoodsLine(sale.saleLineId(), ring, 2)),
                List.of(new BoxLine(sale.boxLineId(), box, product, 2)), List.of());
    }

    private Request returnOf(UUID id, UUID... pieces) {
        return new Request(id, "RETURN", order, null, BACK_ON, List.of(), List.of(),
                java.util.Arrays.stream(pieces).map(piece -> new Piece(piece, sale.saleLineId(), ring)).toList());
    }

    /** What the lots of a variant hold now, as "cost x quantity" oldest first. */
    private List<String> lots(UUID variant) {
        return jdbc.queryForList("""
                SELECT unit_cost || ' x ' || qty_remaining FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL
                 ORDER BY received_at, fifo_tiebreak, slice_position""", String.class, variant);
    }

    private List<Map<String, Object>> movementsOf(UUID action) {
        return jdbc.queryForList("SELECT reason, role, qty_delta, unit_cost FROM stock_movement WHERE action_id = ? ORDER BY id", action);
    }

    private void assertConsistent() {
        var report = stock.reconcile(null);
        assertThat(report.countMismatches()).isEmpty();
        assertThat(report.ledgerMismatches()).isEmpty();
        assertThat(report.commitmentMismatches()).isEmpty();
        assertThat(report.reservedMismatches()).isEmpty();
        assertThat(report.quarantineMismatches()).isEmpty();
    }

    // ---- a paid order cancelled before it left --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a cancellation puts the pieces and their boxes back into the lots they came out of, at the cost they left at, and on sale again")
    void aCancellationPutsGoodsAndBoxesBackToTheirLots() {
        assertThat(lots(ring)).as("the sale took the older lot's one ring and one of the newer lot").containsExactly("62.00 x 0", "65.50 x 4");

        Reversal done = reversals.reverse(cancellation(UUID.randomUUID()), ORDER_SYSTEM);

        assertThat(done.replay()).isFalse();
        assertThat(done.occurredAt()).isEqualTo(BACK_ON);
        assertThat(lots(ring)).as("each ring back in its own lot").containsExactly("62.00 x 1", "65.50 x 5");
        assertThat(row(ring).onHand()).isEqualTo(6);
        assertThat(row(ring).free()).as("on sale again at once").isEqualTo(6);
        assertThat(row(ring).quarantined()).isZero();
        assertThat(row(box).onHand()).as("the two boxes never left the shop").isEqualTo(200);
        List<Map<String, Object>> actions = jdbc.queryForList("""
                SELECT kind, reason, qty, reverses_action_id, occurred_at, actor FROM stock_action WHERE reverses_action_id IN (?, ?) ORDER BY seq""",
                sale.saleLineId(), sale.boxLineId());
        assertThat(actions).hasSize(2).allSatisfy(action -> {
            assertThat(action).containsEntry("kind", "TAKE_BACK").containsEntry("reason", "CANCELLED_BEFORE_DISPATCH").containsEntry("qty", 2)
                    .containsEntry("actor", ORDER_SYSTEM);
            assertThat(((Timestamp) action.get("occurred_at")).toInstant()).as("dated the day of the cancellation, not the day of the sale").isEqualTo(BACK_ON);
        });
        assertConsistent();
    }

    @Test
    @DisplayName("the boxes go back into the product's set-aside when that is where they came from; if it has been let go since, they are simply free")
    void boxesReturnToTheSetAsideTheyCameFrom() {
        // A second product, with ten boxes set aside; its sale takes two of them.
        UUID other = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(other, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        commitments.set(other, 10, ACTOR);
        Line second = new Line(UUID.randomUUID(), ring, other, 2, UUID.randomUUID(), null);
        UUID hold = UUID.randomUUID();
        UUID secondOrder = UUID.randomUUID();
        reservations.reserve(hold, secondOrder, List.of(second), null, ACTOR);
        reservations.consume(hold, SOLD_ON, ACTOR);
        assertThat(commitments.commitments(other, null).getFirst().quantity()).as("two of the ten went with the sale").isEqualTo(8);

        Reversal done = reversals.reverse(new Request(UUID.randomUUID(), "CANCELLATION", secondOrder, hold, BACK_ON,
                List.of(new GoodsLine(second.saleLineId(), ring, 2)), List.of(new BoxLine(second.boxLineId(), box, other, 2)), List.of()), ORDER_SYSTEM);

        assertThat(done.boxes()).singleElement().satisfies(back -> {
            assertThat(back.toSetAside()).isEqualTo(2);
            assertThat(back.toFree()).isZero();
        });
        assertThat(commitments.commitments(other, null).getFirst().quantity()).as("back in the set-aside").isEqualTo(10);
        assertThat(row(box).committed()).isEqualTo(10);
        assertThat(commitments.history(other)).extracting(BoxCommitmentService.Change::kind).contains("RETURN");

        // The first sale's boxes came from free stock: they go back to free.
        Reversal first = reversals.reverse(cancellation(UUID.randomUUID()), ORDER_SYSTEM);
        assertThat(first.boxes()).singleElement().satisfies(back -> {
            assertThat(back.toSetAside()).isZero();
            assertThat(back.toFree()).isEqualTo(2);
        });
        assertThat(row(box).onHand()).isEqualTo(200);
        assertConsistent();
    }

    @Test
    @DisplayName("after a box swap the box to put back is the one that actually went with the order: naming the first one is refused and nothing moves")
    void afterASwapTheLiveBoxIsReversed() {
        UUID replacementLine = UUID.randomUUID();
        swaps.swap(UUID.randomUUID(), order, product, sale.boxLineId(), replacementLine, velvetBox, "the customer asked for the velvet box", ACTOR);
        assertThat(row(velvetBox).onHand()).isEqualTo(198);

        assertThatThrownBy(() -> reversals.reverse(cancellation(UUID.randomUUID()), ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("box-line-not-live"));
        assertThat(row(ring).onHand()).as("nothing moved").isEqualTo(4);

        reversals.reverse(new Request(UUID.randomUUID(), "CANCELLATION", order, reservation, BACK_ON, List.of(new GoodsLine(sale.saleLineId(), ring, 2)),
                List.of(new BoxLine(replacementLine, velvetBox, product, 2)), List.of()), ORDER_SYSTEM);

        assertThat(row(velvetBox).onHand()).as("the velvet boxes are back").isEqualTo(200);
        assertThat(row(box).onHand()).as("the first boxes went back when they were swapped out").isEqualTo(200);
        assertThat(row(ring).onHand()).isEqualTo(6);
        assertConsistent();
    }

    // ---- a parcel that came back ----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("one of two pieces comes back: it goes to the NEWEST lot its sale drained, at that lot's cost, and waits there, not for sale; the box stays sold")
    void oneOfTwoGoesToTheNewestLotDrained() {
        UUID piece = UUID.randomUUID();

        reversals.reverse(returnOf(UUID.randomUUID(), piece), ORDER_SYSTEM);

        assertThat(lots(ring)).as("back to lot B (K65.50); lot A's ring is still out").containsExactly("62.00 x 0", "65.50 x 5");
        assertThat(movementsOf(piece)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("reason", "RETURN").containsEntry("role", "ORIGINAL").containsEntry("qty_delta", 1);
            assertThat((BigDecimal) row.get("unit_cost")).isEqualByComparingTo("65.50");
        });
        assertThat(row(ring).onHand()).isEqualTo(5);
        assertThat(row(ring).quarantined()).isEqualTo(1);
        assertThat(row(ring).free()).as("the four that were free before; the returned ring waits").isEqualTo(4);
        assertThat(row(box).onHand()).as("a box that went out does not come back").isEqualTo(198);
        Map<String, Object> waiting = jdbc.queryForMap("SELECT * FROM returned_piece WHERE piece_id = ?", piece);
        assertThat(waiting).containsEntry("sale_line_id", sale.saleLineId()).containsEntry("variant_id", ring).containsEntry("outcome", null);

        // The second ring comes back later: it goes to the older lot, at ITS cost.
        UUID second = UUID.randomUUID();
        reversals.reverse(returnOf(UUID.randomUUID(), second), ORDER_SYSTEM);
        assertThat(lots(ring)).containsExactly("62.00 x 1", "65.50 x 5");
        assertThat((BigDecimal) movementsOf(second).getFirst().get("unit_cost")).isEqualByComparingTo("62.00");
        assertThat(row(ring).quarantined()).isEqualTo(2);
        assertConsistent();
    }

    @Test
    @DisplayName("a returned piece that is waiting cannot be held by an order, and a later sale never takes it out of its lot")
    void aLaterSaleNeverTakesTheQuarantinedUnit() {
        UUID fromB = UUID.randomUUID(), fromA = UUID.randomUUID();
        reversals.reverse(returnOf(UUID.randomUUID(), fromB, fromA), ORDER_SYSTEM);
        assertThat(row(ring).onHand()).isEqualTo(6);
        assertThat(row(ring).free()).isEqualTo(4);

        Line greedy = new Line(UUID.randomUUID(), ring, product, 5, UUID.randomUUID(), null);
        assertThatThrownBy(() -> reservations.reserve(UUID.randomUUID(), UUID.randomUUID(), List.of(greedy), null, ACTOR))
                .as("six on the shelf, two of them waiting: five cannot be held")
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("insufficient-stock"));

        Line four = new Line(UUID.randomUUID(), ring, product, 4, UUID.randomUUID(), null);
        UUID hold = UUID.randomUUID();
        reservations.reserve(hold, UUID.randomUUID(), List.of(four), null, ACTOR);
        reservations.consume(hold, BACK_ON.plusSeconds(60), ACTOR);

        assertThat(lots(ring)).as("the sale took lot B's four free rings; the older lot's returned ring is still in ITS lot").containsExactly("62.00 x 1", "65.50 x 1");
        assertThat(row(ring).quarantined()).isEqualTo(2);
        assertConsistent();
    }

    // ---- asked once, answered the same for ever ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the same request again under the same id changes nothing and says it is a repeat; another request under that id is refused")
    void idempotentByReversalId() {
        UUID id = UUID.randomUUID();
        UUID piece = UUID.randomUUID();
        reversals.reverse(returnOf(id, piece), ORDER_SYSTEM);

        Reversal again = reversals.reverse(returnOf(id, piece), ORDER_SYSTEM);

        assertThat(again.replay()).isTrue();
        assertThat(row(ring).onHand()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_action WHERE reverses_action_id = ?", Integer.class, sale.saleLineId())).isEqualTo(1);
        assertThatThrownBy(() -> reversals.reverse(returnOf(id, UUID.randomUUID()), ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("reversal-id-used"));
    }

    @Test
    @DisplayName("never more than is still out: a third piece of a two-piece line, or a cancellation after a return, is refused and nothing moves")
    void neverMoreThanIsOut() {
        reversals.reverse(returnOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), ORDER_SYSTEM);

        assertThatThrownBy(() -> reversals.reverse(returnOf(UUID.randomUUID(), UUID.randomUUID()), ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("more-than-outstanding"));
        assertThatThrownBy(() -> reversals.reverse(cancellation(UUID.randomUUID()), ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("more-than-outstanding"));
        assertThat(row(ring).onHand()).isEqualTo(6);
        assertConsistent();
    }

    @Test
    @DisplayName("what is named must be what was sold: an unknown line, a line of another variant, a hold that was never sold, a moment before the sale")
    void whatIsNamedMustBeWhatWasSold() {
        Request unknownLine = new Request(UUID.randomUUID(), "RETURN", order, null, BACK_ON, List.of(), List.of(),
                List.of(new Piece(UUID.randomUUID(), UUID.randomUUID(), ring)));
        assertThatThrownBy(() -> reversals.reverse(unknownLine, ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("sale-line-not-found"));

        Request wrongVariant = new Request(UUID.randomUUID(), "RETURN", order, null, BACK_ON, List.of(), List.of(),
                List.of(new Piece(UUID.randomUUID(), sale.saleLineId(), box)));
        assertThatThrownBy(() -> reversals.reverse(wrongVariant, ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("not-a-sale"));

        Request noSuchHold = new Request(UUID.randomUUID(), "CANCELLATION", order, UUID.randomUUID(), BACK_ON,
                List.of(new GoodsLine(sale.saleLineId(), ring, 2)), List.of(new BoxLine(sale.boxLineId(), box, product, 2)), List.of());
        assertThatThrownBy(() -> reversals.reverse(noSuchHold, ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("reservation-not-found"));

        Request beforeTheSale = new Request(UUID.randomUUID(), "RETURN", order, null, SOLD_ON.minusSeconds(1), List.of(), List.of(),
                List.of(new Piece(UUID.randomUUID(), sale.saleLineId(), ring)));
        assertThatThrownBy(() -> reversals.reverse(beforeTheSale, ORDER_SYSTEM)).as("the moment is refused, never moved")
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("invalid-occurred-at"));

        assertThat(row(ring).onHand()).as("nothing moved").isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_reversal WHERE order_id = ?", Integer.class, order)).isZero();
    }

    @Test
    @DisplayName("a hold that was let go, not sold, cannot be cancelled as a sale")
    void aHoldThatWasNeverSold() {
        Line held = new Line(UUID.randomUUID(), ring, product, 1, UUID.randomUUID(), null);
        UUID hold = UUID.randomUUID();
        UUID heldOrder = UUID.randomUUID();
        reservations.reserve(hold, heldOrder, List.of(held), null, ACTOR);

        assertThatThrownBy(() -> reversals.reverse(new Request(UUID.randomUUID(), "CANCELLATION", heldOrder, hold, BACK_ON,
                List.of(new GoodsLine(held.saleLineId(), ring, 1)), List.of(new BoxLine(held.boxLineId(), box, product, 1)), List.of()), ORDER_SYSTEM))
                .isInstanceOf(CommitmentProblem.class);
    }

    @Test
    @DisplayName("what was reversed can be read back by its id; an id nothing was done under says so plainly")
    void readById() {
        UUID id = UUID.randomUUID();
        reversals.reverse(returnOf(id, UUID.randomUUID()), ORDER_SYSTEM);

        assertThat(reversals.find(id)).hasValueSatisfying(found -> {
            assertThat(found.kind()).isEqualTo("RETURN");
            assertThat(found.orderId()).isEqualTo(order);
            assertThat(found.occurredAt()).isEqualTo(BACK_ON);
        });
        assertThat(reversals.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("every movement of a reversal is announced to finance, with the sale it reverses")
    void announcedAsCostRowsWithWhatTheyReverse() {
        UUID piece = UUID.randomUUID();
        int before = jdbc.queryForObject("SELECT count(*) FROM stock_cost_event", Integer.class);

        reversals.reverse(returnOf(UUID.randomUUID(), piece), ORDER_SYSTEM);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_cost_event", Integer.class)).as("one cost row for the one piece").isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT reverses_action_id FROM stock_action WHERE id = ?", UUID.class, piece)).isEqualTo(sale.saleLineId());
    }

    @Test
    @DisplayName("the reconciliation says so when the count of waiting pieces and the pieces themselves disagree")
    void quarantineMismatch() {
        reversals.reverse(returnOf(UUID.randomUUID(), UUID.randomUUID()), ORDER_SYSTEM);
        assertThat(stock.reconcile(null).quarantineMismatches()).isEmpty();

        jdbc.update("UPDATE inventory SET quarantined = 0 WHERE variant_id = ?", ring);

        assertThat(stock.reconcile(null).quarantineMismatches()).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.variantId()).isEqualTo(ring);
            assertThat(mismatch.quarantined()).isZero();
            assertThat(mismatch.piecesWaiting()).isEqualTo(1);
        });
    }
}
