package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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

import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.events.ReceiptSnapshot;
import com.oriah.inventory.events.ReceiptSnapshot.Slice;
import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService.Line;
import com.oriah.inventory.service.ReturnedPieceService.Decision;
import com.oriah.inventory.service.SaleReversalService.Piece;
import com.oriah.inventory.service.SaleReversalService.Request;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.amended;
import static com.oriah.inventory.service.StockTestData.amendmentContext;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A returned piece that will be sold again at a lower price moves to a listing of its own (Phase 12): out of the lot it went back to, into a
 * ONE-PIECE lot under the marked-down variant, at exactly the cost it had. Nothing is recognised by the move (the piece only changed shelf);
 * its cost is released when the marked-down piece is sold.
 *
 * <p>The one-piece lot belongs to a family: it says which lot its piece came out of, and it must always cost what that piece cost. So when
 * anything later changes which lot, or what cost, the piece left at (a corrected courier bill; an older delivery keyed late), the marked-down
 * lot is replaced to match, and a sale already made from it is re-costed at its own date, like any other sale.
 *
 * <p>The stock here: seven rings of one delivery, K7,000.00 plus K800.00 shared = K7,800.00, landing as four at K1,114.29 and three at
 * K1,114.28. One was sold (from the four), came back, and is waiting.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class MarkdownRelistTest {

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
    private UUID child;
    private UUID product;
    private UUID order;
    private UUID piece;
    private ReceiptSnapshot delivery;
    private long outboxIds = 100;

    @BeforeEach
    void aRingCameBack() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        jdbc.execute("DELETE FROM product_index");
        ring = UUID.randomUUID();
        child = UUID.randomUUID();
        product = UUID.randomUUID();
        UUID boxVariant = UUID.randomUUID();
        delivery = StockTestData.sevenRingsAsKeyed(ring, "2026-11-08T08:00:00Z");
        stock.applyReceipt(delivery, context(1));
        stock.applyReceipt(box(boxVariant, "2026-11-09T09:00:00.123456Z"), context(3));
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", boxVariant, 0, false), UUID.randomUUID(), "system:test");
        known(ring, product, "Aurelia Ring - RING-9", "RING-9");
        known(child, product, "Aurelia Ring - RING-9 - returned, marked down", "RING-9-R-1a2b3c4d");
        order = UUID.randomUUID();
        piece = returned(ring, order, sell(ring, order, 1));
    }

    /** What catalog's snapshot tells inventory about a variant. */
    private void known(UUID variant, UUID ofProduct, String title, String sku) {
        jdbc.update("INSERT INTO product_index (variant_id, title, product_id, sku) VALUES (?, ?, ?, ?)", variant, title, ofProduct, sku);
    }

    /** A paid sale of {@code qty} pieces of a variant; its sale line. */
    private UUID sell(UUID variant, UUID forOrder, int qty) {
        Line line = new Line(UUID.randomUUID(), variant, product, qty, UUID.randomUUID(), null);
        UUID hold = UUID.randomUUID();
        reservations.reserve(hold, forOrder, List.of(line), null, ACTOR);
        reservations.consume(hold, SOLD_ON, ACTOR);
        return line.saleLineId();
    }

    /** One piece of a sale line came back and waits; its id. */
    private UUID returned(UUID variant, UUID ofOrder, UUID saleLine) {
        UUID id = UUID.randomUUID();
        reversals.reverse(new Request(UUID.randomUUID(), "RETURN", ofOrder, null, BACK_ON, List.of(), List.of(), List.of(new Piece(id, saleLine, variant))),
                ORDER_SYSTEM);
        return id;
    }

    private Decision relist() {
        return pieces.decide(piece, "RELISTED", child, LOOKED_AT, ORDER_SYSTEM);
    }

    private Inventory row(UUID variant) {
        return inventories.findById(variant).orElseThrow();
    }

    /** The lots a variant holds now, as "cost x quantity", in the order they are drawn. */
    private List<String> lots(UUID variant) {
        return jdbc.queryForList("""
                SELECT unit_cost || ' x ' || qty_remaining FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL
                 ORDER BY received_at, fifo_tiebreak, slice_position""", String.class, variant);
    }

    private Map<String, Object> childLot() {
        return jdbc.queryForMap("SELECT * FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL", child);
    }

    private BigDecimal saleCost(UUID saleLine) {
        return jdbc.queryForObject("SELECT sum(-qty_delta * unit_cost) FROM live_movement WHERE action_id = ?", BigDecimal.class, saleLine);
    }

    private int costEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_cost_event", Integer.class);
    }

    /** The courier's bill corrected: every ring now lands at {@code each}. */
    private void correct(int revision, String shared, String landed, String each, String at) {
        stock.applyAmendment(amended(delivery, revision, "the courier's bill was keyed wrongly", at, shared, landed, List.of(new Slice(7, new BigDecimal(each))), false),
                amendmentContext(++outboxIds, at));
    }

    /** The ring and its marked-down listing, each reconciled on its own (other tests leave relist records behind: those tables are never wiped). */
    private void assertConsistent() {
        for (UUID variant : List.of(ring, child)) {
            var report = stock.reconcile(variant);
            assertThat(report.countMismatches()).isEmpty();
            assertThat(report.ledgerMismatches()).isEmpty();
            assertThat(report.quarantineMismatches()).isEmpty();
            assertThat(report.familyMismatches()).isEmpty();
            assertThat(report.strandedChildren()).isEmpty();
        }
    }

    // ---------------------------------------------------------------- the move

    @Test
    @DisplayName("the piece moves to a one-piece lot of its own listing at the cost it had (K1,114.29); nothing is recognised by the move; its sale then costs exactly that")
    void relistedIntoAOneUnitLotAtItsSourceCost() {
        assertThat(lots(ring)).containsExactly("1114.29 x 4", "1114.28 x 3");
        int announced = costEvents();

        Decision done = relist();

        assertThat(done.outcome()).isEqualTo("RELISTED");
        assertThat(done.childVariantId()).isEqualTo(child);
        assertThat(lots(ring)).as("out of the lot it went back to").containsExactly("1114.29 x 3", "1114.28 x 3");
        assertThat(row(ring).onHand()).isEqualTo(6);
        assertThat(row(ring).quarantined()).isZero();
        assertThat(lots(child)).containsExactly("1114.29 x 1");
        assertThat(row(child).onHand()).isEqualTo(1);
        assertThat(row(child).free()).as("for sale at once, under its own listing").isEqualTo(1);
        assertThat(row(child).kind()).isEqualTo("SELLABLE");
        Map<String, Object> lot = childLot();
        assertThat(lot).containsEntry("qty_received", 1).containsEntry("sku", "RING-9-R-1a2b3c4d")
                .containsEntry("variant_title", "Aurelia Ring - RING-9 - returned, marked down").containsEntry("product_title", "Aurelia Ring")
                .containsEntry("consignment_line_id", delivery.consignmentLineId()).containsEntry("consignment_reference", "C-000050")
                .containsEntry("revision", 1);
        assertThat(lot.get("source_lot_id")).as("it says which lot its piece came out of").isNotNull();
        assertThat(jdbc.queryForMap("SELECT * FROM markdown_relist WHERE piece_id = ?", piece))
                .containsEntry("source_variant_id", ring).containsEntry("child_variant_id", child).containsEntry("exit_action_id", lot.get("source_action_id"));
        assertThat(jdbc.queryForMap("SELECT outcome, child_variant_id FROM returned_piece WHERE piece_id = ?", piece))
                .containsEntry("outcome", "RELISTED").containsEntry("child_variant_id", child);
        assertThat(costEvents()).as("the piece only changed shelf: finance is told nothing").isEqualTo(announced);

        UUID childSale = sell(child, UUID.randomUUID(), 1);

        assertThat(saleCost(childSale)).isEqualByComparingTo("1114.29");
        assertThat(lots(child)).containsExactly("1114.29 x 0");
        assertThat(costEvents()).as("the marked-down piece's sale is a cost like any other").isEqualTo(announced + 2);   // the ring, and its box
        assertThat(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type = 'stock.cost_recorded'", String.class,
                childSale.toString())).as("and it belongs to its product").contains(product.toString());
        assertThat(relist().replay()).isTrue();
        assertConsistent();
    }

    @Test
    @DisplayName("the listing must be known to inventory, of the same product, and unused; a refusal moves nothing and the piece still waits")
    void theVariantMustBeKnownAndOfTheSameProduct() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> pieces.decide(piece, "RELISTED", unknown, LOOKED_AT, ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("markdown-variant-not-known-yet"));

        UUID noSku = UUID.randomUUID();
        known(noSku, product, "Aurelia Ring - RING-9 - returned, marked down", null);
        assertThatThrownBy(() -> pieces.decide(piece, "RELISTED", noSku, LOOKED_AT, ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("markdown-variant-not-known-yet"));

        UUID elsewhere = UUID.randomUUID();
        known(elsewhere, UUID.randomUUID(), "Another product - returned, marked down", "OTHER-R-1");
        assertThatThrownBy(() -> pieces.decide(piece, "RELISTED", elsewhere, LOOKED_AT, ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("markdown-variant-of-another-product"));

        // A second piece of the same ring cannot go to a listing that already holds one.
        UUID secondOrder = UUID.randomUUID();
        UUID second = returned(ring, secondOrder, sell(ring, secondOrder, 1));
        relist();
        assertThatThrownBy(() -> pieces.decide(second, "RELISTED", child, LOOKED_AT, ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("markdown-variant-in-use"));
        assertThatThrownBy(() -> pieces.decide(second, "RELISTED", ring, LOOKED_AT, ORDER_SYSTEM))
                .as("its own variant is not a marked-down listing").isInstanceOf(CommitmentProblem.class);

        assertThat(row(ring).quarantined()).as("the second piece still waits").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM returned_piece WHERE piece_id = ?", String.class, second)).isNull();
        assertConsistent();
    }

    @Test
    @DisplayName("a marked-down piece that is sold and comes back goes into its own lot: it can be written off there at its own cost, never marked down again")
    void aChildSoldAndReturnedGoesBackToItsOwnLot() {
        relist();
        UUID childOrder = UUID.randomUUID();
        UUID back = returned(child, childOrder, sell(child, childOrder, 1));
        assertThat(lots(child)).containsExactly("1114.29 x 1");
        assertThat(row(child).quarantined()).isEqualTo(1);

        UUID another = UUID.randomUUID();
        known(another, product, "Aurelia Ring - RING-9 - returned, marked down", "RING-9-R-99999999");
        assertThatThrownBy(() -> pieces.decide(back, "RELISTED", another, LOOKED_AT, ORDER_SYSTEM))
                .isInstanceOfSatisfying(CommitmentProblem.class, problem -> assertThat(problem.code()).isEqualTo("already-marked-down"));

        pieces.decide(back, "WRITTEN_OFF", null, LOOKED_AT, ORDER_SYSTEM);

        assertThat(lots(child)).containsExactly("1114.29 x 0");
        assertThat(row(child).onHand()).isZero();
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM stock_movement m JOIN stock_action a ON a.id = m.action_id WHERE a.assesses_action_id = ?",
                BigDecimal.class, back)).isEqualByComparingTo("1114.29");
        assertConsistent();
    }

    // ---------------------------------------------------------------- the family follows its source

    @Test
    @DisplayName("a corrected courier bill reaches a marked-down piece still unsold: its lot is replaced at the corrected cost, and the delivery is still worth its landed total")
    void aCostCorrectionReachesAnUnsoldChild() {
        relist();

        correct(2, "1400.00", "8400.00", "1200.00", "2026-11-12T09:45:00Z");

        assertThat(lots(ring)).containsExactly("1200.00 x 6");
        assertThat(lots(child)).containsExactly("1200.00 x 1");
        assertThat(childLot()).containsEntry("revision", 2).containsEntry("qty_received", 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_lot WHERE variant_id = ?", Integer.class, child)).as("the old one-piece lot is kept, replaced").isEqualTo(2);
        assertThat(row(child).onHand()).isEqualTo(1);
        assertThat(saleCost(sell(child, UUID.randomUUID(), 1))).isEqualByComparingTo("1200.00");
        assertConsistent();
    }

    @Test
    @DisplayName("a corrected courier bill re-costs a marked-down piece that was already sold, at its own date")
    void aCostCorrectionRecutsASoldChildsSale() {
        relist();
        UUID childSale = sell(child, UUID.randomUUID(), 1);
        assertThat(saleCost(childSale)).isEqualByComparingTo("1114.29");

        correct(2, "1400.00", "8400.00", "1200.00", "2026-11-12T09:45:00Z");

        assertThat(saleCost(childSale)).isEqualByComparingTo("1200.00");
        assertThat(jdbc.queryForList("SELECT DISTINCT occurred_at FROM live_movement WHERE action_id = ?", java.sql.Timestamp.class, childSale))
                .as("the sale keeps its own date").containsExactly(java.sql.Timestamp.from(SOLD_ON));
        assertThat(lots(child)).containsExactly("1200.00 x 0");
        assertConsistent();
    }

    @Test
    @DisplayName("after two corrections the delivery is still worth exactly its landed total, with the marked-down piece unsold and then sold")
    void theLineIsStillWorthItsLandedTotalAfterTwoAmendments() {
        relist();
        correct(2, "1400.00", "8400.00", "1200.00", "2026-11-12T09:45:00Z");
        correct(3, "2100.00", "9100.00", "1300.00", "2026-11-12T09:50:00Z");
        assertThat(lots(child)).containsExactly("1300.00 x 1");
        assertThat(childLot()).containsEntry("revision", 3);

        UUID childSale = sell(child, UUID.randomUUID(), 1);
        correct(4, "700.00", "7700.00", "1100.00", "2026-11-12T09:55:00Z");

        assertThat(saleCost(childSale)).isEqualByComparingTo("1100.00");
        // Held and recognised, over the whole delivery: six rings held under the ordinary variant, one sold as a marked-down piece.
        BigDecimal held = jdbc.queryForObject("SELECT sum(qty_remaining * unit_cost) FROM cost_lot WHERE consignment_line_id = ? AND superseded_at IS NULL",
                BigDecimal.class, delivery.consignmentLineId());
        assertThat(held).isEqualByComparingTo("6600.00");
        assertThat(held.add(saleCost(childSale))).isEqualByComparingTo("7700.00");
        assertConsistent();
    }

    @Test
    @DisplayName("an older delivery keyed late moves the sale, the return and the marked-down piece to the older lot: the piece now costs what that lot cost")
    void aLateOlderReceiptMovesAnUnsoldChildsCost() {
        relist();
        assertThat(lots(child)).containsExactly("1114.29 x 1");

        // One ring bought earlier for K900.00 and only now keyed: oldest first, the sale should have taken IT.
        stock.applyReceipt(olderRing("900.00"), context(++outboxIds));

        assertThat(lots(ring)).as("the older ring left with the sale, came back and moved on; the seven are all there again")
                .containsExactly("900.00 x 0", "1114.29 x 4", "1114.28 x 3");
        assertThat(lots(child)).containsExactly("900.00 x 1");
        assertThat(saleCost(sell(child, UUID.randomUUID(), 1))).isEqualByComparingTo("900.00");
        assertConsistent();
    }

    @Test
    @DisplayName("an older delivery keyed late re-costs a marked-down piece that was already sold")
    void aLateOlderReceiptRecutsASoldChildsSale() {
        relist();
        UUID childSale = sell(child, UUID.randomUUID(), 1);

        stock.applyReceipt(olderRing("900.00"), context(++outboxIds));

        assertThat(saleCost(childSale)).isEqualByComparingTo("900.00");
        assertThat(lots(child)).containsExactly("900.00 x 0");
        assertConsistent();
    }

    private ReceiptSnapshot olderRing(String cost) {
        BigDecimal total = new BigDecimal(cost);
        return new ReceiptSnapshot(UUID.randomUUID(), "C-000049", UUID.randomUUID(), ring, "RING-9", "Aurelia Ring", "Aurelia Ring - RING-9", "SELLABLE",
                UUID.randomUUID(), "Dubai Gold", "2026-11-07T08:00:00Z", false, "ZMW", 1, total, new BigDecimal("0.00"), total, List.of(new Slice(1, total)), 1,
                null, null, null);
    }

    // ---------------------------------------------------------------- the checks say so

    @Test
    @DisplayName("the reconciliation reports a marked-down lot that is out of step with the piece it holds, and a marked-down listing catalog no longer describes")
    void theReconciliationReportsAFamilyOutOfStep() {
        relist();
        assertConsistent();
        UUID lot = (UUID) childLot().get("id");

        jdbc.update("UPDATE cost_lot SET superseded_at = now(), qty_remaining = 0 WHERE id = ?", lot);
        assertThat(stock.reconcile(ring).familyMismatches()).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.pieceId()).isEqualTo(piece);
            assertThat(mismatch.childVariantId()).isEqualTo(child);
        });

        jdbc.update("DELETE FROM product_index WHERE variant_id = ?", child);
        assertThat(stock.reconcile(ring).strandedChildren()).singleElement().satisfies(stranded -> assertThat(stranded.variantId()).isEqualTo(child));
    }

    @Test
    @DisplayName("orders holding the ring and its marked-down piece while its cost is corrected never deadlock")
    void aReservationHoldingSourceAndChildAgainstAnAmendment() throws Exception {
        relist();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holds = pool.submit(() -> {
                for (int i = 0; i < 12; i++) {
                    try {
                        UUID hold = UUID.randomUUID();
                        reservations.reserve(hold, UUID.randomUUID(), List.of(new Line(UUID.randomUUID(), ring, product, 1, UUID.randomUUID(), null),
                                new Line(UUID.randomUUID(), child, product, 1, UUID.randomUUID(), null)), null, ACTOR);
                        reservations.release(hold, ACTOR);
                    } catch (RuntimeException e) {
                        failures.add(e);
                    }
                }
            });
            Future<?> corrections = pool.submit(() -> {
                List<String[]> bills = new ArrayList<>(List.of(new String[] {"1400.00", "8400.00", "1200.00"}, new String[] {"2100.00", "9100.00", "1300.00"}));
                for (int i = 0; i < 12; i++) {
                    String[] bill = bills.get(i % 2);
                    try {
                        correct(2 + i, bill[0], bill[1], bill[2], "2026-11-12T09:45:00Z");
                    } catch (RuntimeException e) {
                        failures.add(e);
                    }
                }
            });
            holds.get(120, TimeUnit.SECONDS);
            corrections.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // A change that lost a race is refused and may be retried; what must never happen is two of them waiting on each other for ever.
        assertThat(failures).allSatisfy(failure -> assertThat(String.valueOf(failure) + failure.getCause()).doesNotContainIgnoringCase("deadlock"));
        assertConsistent();
    }
}
