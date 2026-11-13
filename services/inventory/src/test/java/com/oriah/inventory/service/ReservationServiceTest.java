package com.oriah.inventory.service;

import java.time.Duration;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.inventory.MovableClock;
import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService.Line;
import com.oriah.inventory.service.ReservationService.Status;
import com.oriah.inventory.service.ReservationService.View;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An order's hold on its pieces and boxes (Phase 7). The rules, each with a test: a hold is all or nothing, and says every line that is short;
 * a box line takes from the product's set-aside first and from free after; a hold has one owner (an order holds one at a time) and a clock
 * (it lapses); it is consumed (the sale: goods and boxes drain their lots in one commit, dated to the money), released or expired exactly
 * once; and after every change what each stock row says is reserved equals what the held lines add up to.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ReservationServiceTest {

    private static final Instant T0 = TestClockConfiguration.T0;
    private static final MovableClock CLOCK = TestClockConfiguration.CLOCK;

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

    @Autowired
    private TransactionTemplate tx;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID ring;
    private UUID bracelet;
    private UUID box;
    private UUID velvetBox;
    private UUID ringProduct;
    private UUID braceletProduct;

    @BeforeEach
    void stockOnTheShelf() {
        CLOCK.set(T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        ring = UUID.randomUUID();
        bracelet = UUID.randomUUID();
        box = UUID.randomUUID();
        velvetBox = UUID.randomUUID();
        StockTestData.stock(stock, ring, 5);
        stock.applyReceipt(StockTestData.simple(bracelet, 3), context(2));
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(3));
        stock.applyReceipt(box(velvetBox, "2026-11-09T09:00:00Z"), context(4));
        ringProduct = productWithBox(box);
        braceletProduct = productWithBox(box);
    }

    private UUID productWithBox(UUID defaultBox) {
        UUID product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", defaultBox, 0, false), UUID.randomUUID(), "system:test");
        return product;
    }

    private Line ringLine(int qty) {
        return new Line(UUID.randomUUID(), ring, ringProduct, qty, UUID.randomUUID(), null);
    }

    private Line braceletLine(int qty) {
        return new Line(UUID.randomUUID(), bracelet, braceletProduct, qty, UUID.randomUUID(), null);
    }

    private View hold(UUID order, Line... lines) {
        return reservations.reserve(UUID.randomUUID(), order, List.of(lines), null, ACTOR);
    }

    private Inventory row(UUID variant) {
        return inventories.findById(variant).orElseThrow();
    }

    /** After every change: what a stock row says is reserved equals the held lines; the boxes' set-aside agrees; counts agree with lots. */
    private void assertConsistent() {
        List<UUID> off = jdbc.queryForList("""
                SELECT i.variant_id FROM inventory i
                 WHERE i.reserved <> coalesce((SELECT sum(l.quantity) FROM reservation_line l JOIN reservation r ON r.id = l.reservation_id
                                                WHERE r.status = 'HELD' AND l.variant_id = i.variant_id), 0)""", UUID.class);
        assertThat(off).as("reserved equals what the held lines add up to").isEmpty();
        for (UUID b : List.of(box, velvetBox)) {
            tx.executeWithoutResult(status -> commitments.verifyBox(b));
            Inventory r = row(b);
            assertThat(r.onHand()).as("count = free + set aside + reserved").isEqualTo(r.free() + r.committed() + r.reserved());
        }
        var report = stock.reconcile(null);
        assertThat(report.countMismatches()).isEmpty();
        assertThat(report.ledgerMismatches()).isEmpty();
        assertThat(report.commitmentMismatches()).isEmpty();
    }

    private static CommitmentProblem problem(Throwable e) {
        assertThat(e).isInstanceOf(CommitmentProblem.class);
        return (CommitmentProblem) e;
    }

    // ---------------------------------------------------------------- reserving

    @Test
    @DisplayName("an order holds its pieces and one box for each, from free, for the default ten minutes")
    void reservingHoldsGoodsAndBoxes() {
        UUID order = UUID.randomUUID();

        View held = hold(order, ringLine(2));

        assertThat(held.status()).isEqualTo(Status.HELD);
        assertThat(held.orderId()).isEqualTo(order);
        assertThat(held.heldAt()).isEqualTo(T0);
        assertThat(held.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        assertThat(held.lines()).extracting(ReservationService.LineView::kind, ReservationService.LineView::variantId, ReservationService.LineView::quantity,
                ReservationService.LineView::fromCommitted, ReservationService.LineView::fromFree)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("GOODS", ring, 2, 0, 2), org.assertj.core.groups.Tuple.tuple("BOX", box, 2, 0, 2));
        assertThat(row(ring).reserved()).isEqualTo(2);
        assertThat(row(ring).available()).isEqualTo(3);
        assertThat(row(box).reserved()).as("one box per piece").isEqualTo(2);
        assertConsistent();
    }

    @Test
    @DisplayName("a box line takes boxes from the product's set-aside first and from free after; the promise stays whole while they are held")
    void aBoxLineTakesFromThePromiseFirst() {
        commitments.set(ringProduct, 3, "user:kc-maya");

        View held = hold(UUID.randomUUID(), ringLine(5));

        var boxLine = held.lines().stream().filter(l -> l.kind().equals("BOX")).findFirst().orElseThrow();
        assertThat(boxLine.fromCommitted()).isEqualTo(3);
        assertThat(boxLine.fromFree()).isEqualTo(2);
        assertThat(row(box).committed()).as("promised and not held").isZero();
        assertThat(row(box).reserved()).isEqualTo(5);
        var record = commitments.commitments(ringProduct, null).get(0);
        assertThat(record.quantity()).isEqualTo(3);
        assertThat(record.held()).isEqualTo(3);
        assertConsistent();
    }

    @Test
    @DisplayName("a hold is all or nothing: a shortage on the last line leaves nothing held, and every short line is named")
    void aShortageLeavesNothingHeld() {
        UUID order = UUID.randomUUID();

        assertThatThrownBy(() -> hold(order, ringLine(2), braceletLine(4)))
                .satisfies(e -> {
                    CommitmentProblem p = problem(e);
                    assertThat(p.status().value()).isEqualTo(409);
                    assertThat(p.code()).isEqualTo("insufficient-stock");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> shortages = (List<Map<String, Object>>) p.properties().get("shortages");
                    assertThat(shortages).hasSize(1);
                    assertThat(shortages.get(0)).containsEntry("variantId", bracelet).containsEntry("kind", "GOODS")
                            .containsEntry("requested", 4).containsEntry("available", 3);
                });

        assertThat(row(ring).reserved()).as("the first line was not left held").isZero();
        assertThat(row(box).reserved()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE order_id = ?", Integer.class, order)).isZero();
        assertConsistent();
    }

    @Test
    @DisplayName("a refused hold names every short line, pieces and boxes alike, and says which kind each is")
    void everyShortLineIsNamed() {
        UUID tightBox = UUID.randomUUID();
        stock.applyReceipt(box(tightBox, "2026-11-09T08:00:00Z"), context(10));
        UUID tightProduct = productWithBox(tightBox);
        // Leave one box of that kind free: another order is holding the rest.
        tx.executeWithoutResult(s -> {
            Inventory b = inventories.findByIdForUpdate(tightBox).orElseThrow();
            b.reserve(b.free() - 1);
            inventories.saveAndFlush(b);
        });

        assertThatThrownBy(() -> hold(UUID.randomUUID(),
                new Line(UUID.randomUUID(), ring, tightProduct, 2, UUID.randomUUID(), null),
                braceletLine(9)))
                .satisfies(e -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> shortages = (List<Map<String, Object>>) problem(e).properties().get("shortages");
                    assertThat(shortages).extracting(m -> m.get("kind")).containsExactlyInAnyOrder("BOX", "GOODS");
                    assertThat(shortages).extracting(m -> m.get("variantId")).containsExactlyInAnyOrder(tightBox, bracelet);
                });
        assertThat(row(ring).reserved()).as("the ring line that was fine was not left held").isZero();
    }

    @Test
    @DisplayName("the same piece on two lines is one demand: 3 and 3 of 5 rings is refused as 6 wanted, 5 available")
    void sameVariantOnTwoLines() {
        assertThatThrownBy(() -> hold(UUID.randomUUID(), ringLine(3), ringLine(3)))
                .satisfies(e -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> shortages = (List<Map<String, Object>>) problem(e).properties().get("shortages");
                    assertThat(shortages).anySatisfy(m -> assertThat(m).containsEntry("variantId", ring).containsEntry("requested", 6)
                            .containsEntry("available", 5));
                });
        assertConsistent();
    }

    @Test
    @DisplayName("asking again with the same reservation id holds nothing more and answers the same hold")
    void reserveIsIdempotentByReservationId() {
        UUID id = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        List<Line> lines = List.of(ringLine(2));

        View first = reservations.reserve(id, order, lines, null, ACTOR);
        View again = reservations.reserve(id, order, lines, null, ACTOR);

        assertThat(again.reservationId()).isEqualTo(first.reservationId());
        assertThat(again.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(row(ring).reserved()).as("held once").isEqualTo(2);
        assertConsistent();
    }

    @Test
    @DisplayName("a reservation id belongs to one order: using it for another is refused")
    void aReservationIdBelongsToOneOrder() {
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(ringLine(1)), null, ACTOR);

        assertThatThrownBy(() -> reservations.reserve(id, UUID.randomUUID(), List.of(ringLine(1)), null, ACTOR))
                .satisfies(e -> assertThat(problem(e).code()).isEqualTo("reservation-id-used"));
    }

    @Test
    @DisplayName("an order holds one reservation at a time: a second one, with a new id, is refused while the first is held")
    void oneHeldReservationPerOrder() {
        UUID order = UUID.randomUUID();
        hold(order, ringLine(1));

        assertThatThrownBy(() -> hold(order, ringLine(1)))
                .satisfies(e -> {
                    assertThat(problem(e).status().value()).isEqualTo(409);
                    assertThat(problem(e).code()).isEqualTo("order-already-holds");
                });
        assertThat(row(ring).reserved()).as("the refused second hold took nothing").isEqualTo(1);
        assertConsistent();
    }

    @Test
    @DisplayName("the last pieces go to the first order; the second is refused")
    void theLastPiecesGoToOneOrder() {
        hold(UUID.randomUUID(), braceletLine(3));

        assertThatThrownBy(() -> hold(UUID.randomUUID(), braceletLine(1)))
                .satisfies(e -> assertThat(problem(e).code()).isEqualTo("insufficient-stock"));
        assertConsistent();
    }

    @Test
    @DisplayName("a product with no default box is refused by name, and nothing is held")
    void noDefaultBox() {
        UUID bare = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(bare, "SELLABLE", "ACTIVE", null, 0, false), UUID.randomUUID(), "system:test");

        assertThatThrownBy(() -> hold(UUID.randomUUID(), new Line(UUID.randomUUID(), ring, bare, 1, UUID.randomUUID(), null)))
                .satisfies(e -> {
                    assertThat(problem(e).code()).isEqualTo("no-default-box");
                    assertThat(problem(e).properties()).containsEntry("productId", bare);
                });
        assertThat(row(ring).reserved()).isZero();
    }

    @Test
    @DisplayName("a product inventory has not heard of cannot be held yet, and the refusal says so")
    void unknownProduct() {
        assertThatThrownBy(() -> hold(UUID.randomUUID(), new Line(UUID.randomUUID(), ring, UUID.randomUUID(), 1, UUID.randomUUID(), null)))
                .satisfies(e -> assertThat(problem(e).code()).isEqualTo("product-state-not-received"));
    }

    @Test
    @DisplayName("a person recording an order may name another box: the line takes that box instead of the default")
    void anotherBoxMayBeNamed() {
        View held = hold(UUID.randomUUID(), new Line(UUID.randomUUID(), ring, ringProduct, 1, UUID.randomUUID(), velvetBox));

        assertThat(held.lines()).anySatisfy(l -> assertThat(l.variantId()).isEqualTo(velvetBox));
        assertThat(row(velvetBox).reserved()).isEqualTo(1);
        assertThat(row(box).reserved()).isZero();
        assertConsistent();
    }

    @Test
    @DisplayName("a sale line that has already been sold cannot be held again")
    void aSoldLineCannotBeHeldAgain() {
        Line line = ringLine(1);
        UUID first = UUID.randomUUID();
        reservations.reserve(first, UUID.randomUUID(), List.of(line), null, ACTOR);
        reservations.consume(first, T0, ACTOR);

        assertThatThrownBy(() -> reservations.reserve(UUID.randomUUID(), UUID.randomUUID(), List.of(line), null, ACTOR))
                .satisfies(e -> assertThat(problem(e).code()).isEqualTo("sale-line-already-sold"));
    }

    @Test
    @DisplayName("the reconciliation says so when a stock row's reserved count is not what its held lines add up to")
    void reconciliationCatchesAReservedCountThatDisagrees() {
        reservations.reserve(UUID.randomUUID(), UUID.randomUUID(), List.of(ringLine(2)), null, ACTOR);
        assertThat(stock.reconcile(ring).reservedMismatches()).as("a clean hold reconciles").isEmpty();

        jdbc.update("UPDATE inventory SET reserved = reserved + 1 WHERE variant_id = ?", ring);   // a count nobody owns

        var mismatches = stock.reconcile(ring).reservedMismatches();
        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0).variantId()).isEqualTo(ring);
        assertThat(mismatches.get(0).reserved()).isEqualTo(3);
        assertThat(mismatches.get(0).sumOfHeldLines()).isEqualTo(2);
        assertThat(stock.reconcile(null).reservedMismatches()).extracting(StockService.ReservedMismatch::variantId).containsExactly(ring);
    }

    // ---------------------------------------------------------------- consuming: the sale

    @Test
    @DisplayName("consuming sells the pieces and their boxes in one commit: lots drained, actions under the order's own line ids, dated to the money")
    void consumingSellsGoodsAndBoxes() {
        Line line = ringLine(2);
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(line), null, ACTOR);
        Instant arrived = Instant.parse("2026-11-10T10:00:00Z");

        View sold = reservations.consume(id, arrived, ACTOR);

        assertThat(sold.status()).isEqualTo(Status.CONSUMED);
        assertThat(sold.endedAt()).isEqualTo(T0);
        assertThat(row(ring).onHand()).isEqualTo(3);
        assertThat(row(ring).reserved()).isZero();
        assertThat(row(box).onHand()).isEqualTo(198);
        assertThat(row(box).reserved()).isZero();
        assertThat(jdbc.queryForObject("SELECT kind || ':' || qty || ':' || variant_id FROM stock_action WHERE id = ?", String.class, line.saleLineId()))
                .isEqualTo("SALE:2:" + ring);
        assertThat(jdbc.queryForObject("SELECT kind || ':' || qty || ':' || variant_id FROM stock_action WHERE id = ?", String.class, line.boxLineId()))
                .isEqualTo("SALE:2:" + box);
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM stock_action WHERE id = ?", java.sql.Timestamp.class, line.saleLineId()).toInstant())
                .as("dated to the day the money arrived").isEqualTo(arrived);
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM stock_action WHERE id = ?", java.sql.Timestamp.class, line.boxLineId()).toInstant())
                .isEqualTo(arrived);
        assertConsistent();
    }

    @Test
    @DisplayName("consuming a second time answers the same sale and sells nothing more")
    void consumingTwiceSellsOnce() {
        Line line = ringLine(2);
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(line), null, ACTOR);
        reservations.consume(id, T0, ACTOR);

        View again = reservations.consume(id, T0, ACTOR);

        assertThat(again.status()).isEqualTo(Status.CONSUMED);
        assertThat(row(ring).onHand()).isEqualTo(3);
        assertThat(row(box).onHand()).isEqualTo(198);
        assertConsistent();
    }

    @Test
    @DisplayName("a box that came from the product's set-aside shrinks that promise when it is sold; a promise used up is closed")
    void consumingShrinksThePromise() {
        commitments.set(ringProduct, 3, "user:kc-maya");
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(ringLine(3)), null, ACTOR);

        reservations.consume(id, T0, ACTOR);

        assertThat(commitments.commitments(ringProduct, null)).as("the promise was used up").isEmpty();
        assertThat(row(box).committed()).isZero();
        assertThat(row(box).onHand()).isEqualTo(197);
        assertConsistent();
    }

    @Test
    @DisplayName("a hold that has lapsed cannot be sold, even before the sweeper has ended it, and nothing is sold")
    void consumingALapsedHoldIsRefused() {
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(ringLine(2)), null, ACTOR);
        CLOCK.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertThatThrownBy(() -> reservations.consume(id, T0, ACTOR))
                .satisfies(e -> {
                    assertThat(problem(e).status().value()).isEqualTo(409);
                    assertThat(problem(e).code()).isEqualTo("reservation-expired");
                });

        assertThat(row(ring).onHand()).as("nothing was sold").isEqualTo(5);
        assertThat(reservations.get(id).status()).as("the sweeper ends it, not the refusal").isEqualTo(Status.HELD);
    }

    @Test
    @DisplayName("a released hold cannot be sold")
    void consumingAReleasedHoldIsRefused() {
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(ringLine(2)), null, ACTOR);
        reservations.release(id, ACTOR);

        assertThatThrownBy(() -> reservations.consume(id, T0, ACTOR))
                .satisfies(e -> assertThat(problem(e).code()).isEqualTo("reservation-not-held"));
        assertThat(row(ring).onHand()).isEqualTo(5);
    }

    @Test
    @DisplayName("a sale dated in the future is refused and rolls the whole sale back; an old date is never refused")
    void theDateOfTheSale() {
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(ringLine(2)), null, ACTOR);

        assertThatThrownBy(() -> reservations.consume(id, T0.plus(Duration.ofHours(1)), ACTOR)).isInstanceOf(IllegalArgumentException.class);
        assertThat(reservations.get(id).status()).isEqualTo(Status.HELD);
        assertThat(row(ring).onHand()).isEqualTo(5);

        reservations.consume(id, Instant.parse("2025-01-01T00:00:00Z"), ACTOR);
        assertThat(reservations.get(id).status()).isEqualTo(Status.CONSUMED);
        assertConsistent();
    }

    @Test
    @DisplayName("a second reservation of the same order can sell its lines once the first has ended, but never twice")
    void aSecondReservationOfTheOrder() {
        UUID order = UUID.randomUUID();
        Line line = ringLine(1);
        UUID first = UUID.randomUUID();
        reservations.reserve(first, order, List.of(line), null, ACTOR);
        CLOCK.advance(Duration.ofMinutes(11));
        reservations.expireDue(10);
        assertThat(reservations.get(first).status()).isEqualTo(Status.EXPIRED);

        UUID second = UUID.randomUUID();
        reservations.reserve(second, order, List.of(line), null, ACTOR);
        reservations.consume(second, T0, ACTOR);

        assertThat(row(ring).onHand()).isEqualTo(4);
        assertThatThrownBy(() -> reservations.consume(first, T0, ACTOR)).satisfies(e -> assertThat(problem(e).code()).isEqualTo("reservation-not-held"));
        assertThat(row(ring).onHand()).as("sold once").isEqualTo(4);
        assertConsistent();
    }

    // ---------------------------------------------------------------- releasing

    @Test
    @DisplayName("releasing frees the pieces and the boxes and ends the reservation")
    void releasing() {
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(ringLine(2)), null, ACTOR);

        View released = reservations.release(id, ACTOR);

        assertThat(released.status()).isEqualTo(Status.RELEASED);
        assertThat(released.endedAt()).isEqualTo(T0);
        assertThat(row(ring).reserved()).isZero();
        assertThat(row(box).reserved()).isZero();
        assertThat(row(ring).available()).isEqualTo(5);
        assertConsistent();
    }

    @Test
    @DisplayName("boxes that came from a product's set-aside go back to it when the hold is released")
    void aBoxGoesBackToThePromiseItCameFrom() {
        commitments.set(ringProduct, 3, "user:kc-maya");
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(ringLine(3)), null, ACTOR);

        reservations.release(id, ACTOR);

        var record = commitments.commitments(ringProduct, null).get(0);
        assertThat(record.quantity()).isEqualTo(3);
        assertThat(record.held()).isZero();
        assertThat(row(box).committed()).isEqualTo(3);
        assertThat(row(box).reserved()).isZero();
        assertConsistent();
    }

    @Test
    @DisplayName("boxes whose promise was let go while they were held go to free when the hold is released")
    void aBoxWhosePromiseIsGoneGoesToFree() {
        commitments.set(ringProduct, 3, "user:kc-maya");
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(ringLine(3)), null, ACTOR);
        commitments.applyProduct(new ProductSnapshot(ringProduct, "SELLABLE", "DRAFT", box, 1, false), UUID.randomUUID(), "system:test");

        reservations.release(id, ACTOR);

        assertThat(commitments.commitments(ringProduct, null)).isEmpty();
        assertThat(row(box).committed()).isZero();
        assertThat(row(box).reserved()).isZero();
        assertThat(row(box).free()).isEqualTo(200);
        assertConsistent();
    }

    @Test
    @DisplayName("releasing twice releases once: another order's hold is never eaten into")
    void releasingTwiceReleasesOnce() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        reservations.reserve(a, UUID.randomUUID(), List.of(ringLine(2)), null, ACTOR);
        reservations.reserve(b, UUID.randomUUID(), List.of(braceletLine(3)), null, ACTOR);   // 3 boxes held by b, 2 by a

        reservations.release(a, ACTOR);
        View again = reservations.release(a, ACTOR);

        assertThat(again.status()).isEqualTo(Status.RELEASED);
        assertThat(row(box).reserved()).as("only b's 3 remain, the second release took nothing").isEqualTo(3);
        assertThat(row(bracelet).reserved()).isEqualTo(3);
        assertConsistent();
    }

    @Test
    @DisplayName("releasing a reservation that was sold or has expired changes nothing and is not an error")
    void releasingAnEndedReservationIsANoOp() {
        UUID sold = UUID.randomUUID();
        reservations.reserve(sold, UUID.randomUUID(), List.of(ringLine(1)), null, ACTOR);
        reservations.consume(sold, T0, ACTOR);

        assertThat(reservations.release(sold, ACTOR).status()).isEqualTo(Status.CONSUMED);
        assertThat(row(ring).onHand()).isEqualTo(4);
        assertConsistent();
    }

    @Test
    @DisplayName("releasing a reservation nobody made is a 404")
    void releasingUnknown() {
        assertThatThrownBy(() -> reservations.release(UUID.randomUUID(), ACTOR))
                .satisfies(e -> assertThat(problem(e).status().value()).isEqualTo(404));
    }

    // ---------------------------------------------------------------- extending and expiring

    @Test
    @DisplayName("a hold can be stretched, but never beyond a day from when it was made")
    void extending() {
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(ringLine(1)), null, ACTOR);
        CLOCK.advance(Duration.ofMinutes(5));

        View stretched = reservations.extend(id, CLOCK.instant().plus(Duration.ofHours(24)), ACTOR);

        assertThat(stretched.expiresAt()).as("capped at held_at + 24 h").isEqualTo(T0.plus(Duration.ofHours(24)));
        View shorter = reservations.extend(id, T0.plus(Duration.ofHours(1)), ACTOR);
        assertThat(shorter.expiresAt()).as("a shorter time never shortens a hold").isEqualTo(T0.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("a hold that has lapsed, or ended, cannot be stretched")
    void extendingALapsedHoldIsRefused() {
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(ringLine(1)), null, ACTOR);
        CLOCK.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> reservations.extend(id, CLOCK.instant().plus(Duration.ofHours(24)), ACTOR))
                .satisfies(e -> assertThat(problem(e).code()).isEqualTo("reservation-expired"));

        UUID other = UUID.randomUUID();
        reservations.reserve(other, UUID.randomUUID(), List.of(braceletLine(1)), null, ACTOR);
        reservations.release(other, ACTOR);
        assertThatThrownBy(() -> reservations.extend(other, CLOCK.instant().plus(Duration.ofHours(1)), ACTOR))
                .satisfies(e -> assertThat(problem(e).code()).isEqualTo("reservation-not-held"));
    }

    @Test
    @DisplayName("holds past their time are ended and released by the sweep; holds still in time are left alone")
    void expiring() {
        UUID lapsed = UUID.randomUUID();
        UUID stretched = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        reservations.reserve(lapsed, UUID.randomUUID(), List.of(ringLine(1)), null, ACTOR);
        reservations.reserve(stretched, UUID.randomUUID(), List.of(ringLine(1)), Duration.ofHours(1), ACTOR);
        CLOCK.advance(Duration.ofMinutes(11));
        reservations.reserve(fresh, UUID.randomUUID(), List.of(ringLine(1)), null, ACTOR);

        int ended = reservations.expireDue(10);

        assertThat(ended).isEqualTo(1);
        assertThat(reservations.get(lapsed).status()).isEqualTo(Status.EXPIRED);
        assertThat(reservations.get(lapsed).endedAt()).isEqualTo(CLOCK.instant());
        assertThat(reservations.get(stretched).status()).isEqualTo(Status.HELD);
        assertThat(reservations.get(fresh).status()).isEqualTo(Status.HELD);
        assertThat(row(ring).reserved()).as("the lapsed hold's piece is free again").isEqualTo(2);
        assertThat(reservations.expireDue(10)).as("a second sweep finds nothing").isZero();
        assertConsistent();
    }

    @Test
    @DisplayName("a sweep ends no more than its limit and the rest wait for the next")
    void sweepLimit() {
        for (int i = 0; i < 3; i++) {
            reservations.reserve(UUID.randomUUID(), UUID.randomUUID(), List.of(new Line(UUID.randomUUID(), ring, ringProduct, 1, UUID.randomUUID(), null)),
                    null, ACTOR);
        }
        CLOCK.advance(Duration.ofMinutes(11));

        assertThat(reservations.expireDue(2)).isEqualTo(2);
        assertThat(reservations.expireDue(2)).isEqualTo(1);
        assertConsistent();
    }
}
