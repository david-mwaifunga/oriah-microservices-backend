package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.events.ReceiptSnapshot;
import com.oriah.inventory.events.ReceiptSnapshot.Slice;
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.service.ReservationService.Line;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static com.oriah.inventory.service.StockTestData.ring;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every movement of stock COST is announced to finance ({@code stock.cost_recorded}, Phase 10), because a sale, a write-off, a give-away, a take-back and a
 * cost correction all reach profit through these rows and through nothing else: finance never reads a stock count, only what each movement released.
 *
 * <p>One event per row of the ledger that moves a cost: the original drain, a re-cut's reversal and its replacement (each with the date of the action it belongs to, so
 * the signed sum equals what the ledger says is in force). Receipts and the transfers of an amendment move no cost and announce nothing. The product a cost belongs to is
 * worked out here, once, and travels with the event, so the report by product never depends on anything that can change afterwards. Announcing is a part of the
 * transaction that moved the stock: a rolled-back write-off announces nothing.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class StockCostEventsTest {

    private static final String MONDAY = "2026-11-09T08:00:00.123456Z";
    private static final Instant TUESDAY = Instant.parse("2026-11-10T12:00:00Z");
    private static final Instant SOLD_ON = Instant.parse("2026-11-10T10:00:00Z");

    @Autowired
    private StockService stock;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private BoxSwapService swaps;

    @Autowired
    private BoxCommitmentService commitments;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID ringProduct;
    private UUID boxProduct;

    @BeforeEach
    void clean() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        ringProduct = UUID.randomUUID();
        boxProduct = UUID.randomUUID();
    }

    // ------------------------------------------------------------------ fixtures

    private UUID indexedRing() {
        UUID variant = UUID.randomUUID();
        jdbc.update("INSERT INTO product_index (variant_id, title, product_id) VALUES (?, 'Aurelia Ring', ?)", variant, ringProduct);
        stock.applyReceipt(ring(variant, MONDAY), context(5001));
        return variant;
    }

    private UUID indexedBox(String receivedAt) {
        UUID variant = UUID.randomUUID();
        jdbc.update("INSERT INTO product_index (variant_id, title, product_id) VALUES (?, 'Gift box', ?)", variant, boxProduct);
        stock.applyReceipt(box(variant, receivedAt), context(5002));
        return variant;
    }

    private record Event(long seq, String aggregateType, String aggregateId, String type, int schemaVersion, String topic, Instant occurredAt, String actor, String payload) {
    }

    private List<Event> events(UUID variant) {
        return jdbc.query("SELECT seq, aggregate_type, aggregate_id, event_type, schema_version, topic, occurred_at, actor, payload::text FROM outbox "
                        + "WHERE event_type = 'stock.cost_recorded' AND payload::jsonb ->> 'variantId' = ? ORDER BY outbox_id",
                (rs, n) -> new Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
                        rs.getTimestamp(7).toInstant(), rs.getString(8), rs.getString(9)), variant.toString());
    }

    /** A sale through the reservation, as order makes it: the pieces and the box that goes with them (every sale line has one). */
    private void sell(UUID ringVariant, int qty, UUID box) {
        commitments.applyProduct(new ProductSnapshot(ringProduct, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        Line sale = new Line(UUID.randomUUID(), ringVariant, ringProduct, qty, UUID.randomUUID(), null);
        UUID reservation = UUID.randomUUID();
        reservations.reserve(reservation, UUID.randomUUID(), List.of(sale), null, ACTOR);
        reservations.consume(reservation, SOLD_ON, ACTOR);
    }

    private long allCostEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type = 'stock.cost_recorded'", Long.class);
    }

    private StockService.WriteOffResult writeOff(UUID variant, int qty, StockReason reason, Instant when, String actor) {
        return stock.writeOff(UUID.randomUUID(), variant, qty, reason, when, null, actor).orElseThrow();
    }

    private static String text(String json, String path) {
        return JsonPath.read(json, path);
    }

    // ------------------------------------------------------------------ one event per row that moves a cost

    @Test
    @DisplayName("a write-off across two lots announces each row: the cost it released, the reason, the lot and the line it came from, the product, and the time the row was written")
    void aWriteOffAnnouncesEachRow() {
        UUID variant = indexedRing();

        StockService.WriteOffResult result = writeOff(variant, 6, StockReason.WRITE_OFF_LOST, TUESDAY, ACTOR);

        List<Event> events = events(variant);
        assertThat(events).hasSize(2);
        Event first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("stock_action");
        assertThat(first.aggregateId()).isEqualTo(result.actionId().toString());
        assertThat(first.type()).isEqualTo("stock.cost_recorded");
        assertThat(first.schemaVersion()).isEqualTo(1);
        assertThat(first.topic()).isEqualTo("oriah.inventory");
        assertThat(first.actor()).isEqualTo(ACTOR);
        assertThat(first.occurredAt()).as("the day it happened").isEqualTo(TUESDAY);
        assertThat(events.get(1).seq()).as("the second event of the same action").isEqualTo(2);
        String p = first.payload();
        List<String> fields = new ArrayList<>(tools.jackson.databind.json.JsonMapper.builder().build().readTree(p).propertyNames());
        assertThat(fields).containsExactly("movementId", "actionId", "actionKind", "reason", "role", "variantId", "lotId", "consignmentLineId", "kind", "productId",
                "qtyDelta", "unitCost", "currency", "estimated", "recordedAt");
        Map<String, Object> row = jdbc.queryForMap("SELECT m.id, m.lot_id, m.recorded_at, l.consignment_line_id FROM stock_movement m JOIN cost_lot l ON l.id = m.lot_id "
                + "WHERE m.action_id = ? ORDER BY m.id LIMIT 1", result.actionId());
        assertThat(((Number) JsonPath.read(p, "$.movementId")).longValue()).isEqualTo(((Number) row.get("id")).longValue());
        assertThat(text(p, "$.actionId")).isEqualTo(result.actionId().toString());
        assertThat(text(p, "$.actionKind")).isEqualTo("WRITE_OFF");
        assertThat(text(p, "$.reason")).isEqualTo("WRITE_OFF_LOST");
        assertThat(text(p, "$.role")).isEqualTo("ORIGINAL");
        assertThat(text(p, "$.variantId")).isEqualTo(variant.toString());
        assertThat(text(p, "$.lotId")).isEqualTo(row.get("lot_id").toString());
        assertThat(text(p, "$.consignmentLineId")).isEqualTo(row.get("consignment_line_id").toString());
        assertThat(text(p, "$.kind")).isEqualTo("SELLABLE");
        assertThat(text(p, "$.productId")).isEqualTo(ringProduct.toString());
        assertThat((Integer) JsonPath.read(p, "$.qtyDelta")).isEqualTo(-4);
        assertThat(p).contains("\"unitCost\":1528.99").contains("\"currency\":\"ZMW\"").contains("\"estimated\":false");
        assertThat(events.get(1).payload()).contains("\"qtyDelta\":-2").contains("\"unitCost\":1528.98");
        assertThat(Instant.parse(text(p, "$.recordedAt"))).isEqualTo(((Timestamp) row.get("recorded_at")).toInstant());
    }

    @Test
    @DisplayName("a receipt, and the lot transfers of a cost correction, move no cost and announce nothing")
    void aReceiptAnnouncesNothing() {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot received = StockTestData.sevenRingsAsKeyed(variant, MONDAY);
        stock.applyReceipt(received, context(10));
        assertThat(events(variant)).isEmpty();

        stock.applyAmendment(StockTestData.amended(received, 2, "the courier was K500", "2026-11-11T09:00:00Z", "500.00", "7500.00",
                List.of(new Slice(6, new BigDecimal("1071.43")), new Slice(1, new BigDecimal("1071.42"))), false), StockTestData.amendmentContext(11, "2026-11-11T09:00:00Z"));

        assertThat(events(variant)).as("lot open and lot close rows are not costs").isEmpty();
    }

    @Test
    @DisplayName("a cost corrected after units were sold: the old rows are reversed and replaced, each with the date of its own sale, so the signed sum is what the ledger now says")
    void aCostCorrectionIsAReversalAndAReplacement() {
        UUID variant = UUID.randomUUID();
        jdbc.update("INSERT INTO product_index (variant_id, title, product_id) VALUES (?, 'Aurelia Ring', ?)", variant, ringProduct);
        ReceiptSnapshot received = StockTestData.sevenRingsAsKeyed(variant, MONDAY);
        stock.applyReceipt(received, context(10));
        sell(variant, 3, indexedBox("2026-11-09T08:00:00.123456Z"));
        assertThat(events(variant)).hasSize(1);

        stock.applyAmendment(StockTestData.amended(received, 2, "the courier was K500", "2026-11-11T09:00:00Z", "500.00", "7500.00",
                List.of(new Slice(6, new BigDecimal("1071.43")), new Slice(1, new BigDecimal("1071.42"))), false), StockTestData.amendmentContext(11, "2026-11-11T09:00:00Z"));

        List<Event> events = events(variant);
        assertThat(events).hasSizeGreaterThanOrEqualTo(3);
        List<String> roles = events.stream().map(e -> text(e.payload(), "$.role")).toList();
        assertThat(roles).contains("ORIGINAL", "RECUT_REVERSAL", "RECUT_REPLACEMENT");
        for (Event e : events) {
            assertThat(e.occurredAt()).as("every row of the sale carries the sale's own date, never the day of the correction").isEqualTo(SOLD_ON);
            assertThat(text(e.payload(), "$.reason")).isEqualTo("SALE");
            assertThat(text(e.payload(), "$.actionKind")).isEqualTo("SALE");
        }
        Event reversal = events.stream().filter(e -> "RECUT_REVERSAL".equals(text(e.payload(), "$.role"))).findFirst().orElseThrow();
        Event replacement = events.stream().filter(e -> "RECUT_REPLACEMENT".equals(text(e.payload(), "$.role"))).findFirst().orElseThrow();
        assertThat((Integer) JsonPath.read(reversal.payload(), "$.qtyDelta")).isPositive();
        assertThat(reversal.payload()).contains("\"reversesMovementId\":");
        assertThat(replacement.payload()).contains("\"replacesMovementId\":");
        BigDecimal announced = events.stream().map(e -> new BigDecimal(JsonPath.read(e.payload(), "$.unitCost").toString())
                .multiply(BigDecimal.valueOf(-(Integer) JsonPath.read(e.payload(), "$.qtyDelta")))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal inForce = jdbc.queryForObject("SELECT coalesce(sum(-qty_delta * unit_cost), 0) FROM live_movement WHERE variant_id = ? AND reason <> 'CONSIGNMENT_RECEIVED'",
                BigDecimal.class, variant);
        assertThat(announced).as("the signed sum of the announced rows is the cost the ledger holds in force").isEqualByComparingTo(inForce);
    }

    @Test
    @DisplayName("a take-back announces the units that came back as a positive row, the reason it reverses, and the action it reverses")
    void aTakeBack() {
        UUID variant = indexedRing();
        StockService.WriteOffResult lost = writeOff(variant, 2, StockReason.WRITE_OFF_LOST, TUESDAY, ACTOR);

        StockService.TakeBackResult back = stock.takeBack(UUID.randomUUID(), lost.actionId(), 1, TUESDAY.plusSeconds(3600), null, ACTOR).orElseThrow();

        List<Event> events = events(variant);
        Event e = events.get(events.size() - 1);
        assertThat(e.aggregateId()).isEqualTo(back.actionId().toString());
        assertThat(text(e.payload(), "$.actionKind")).isEqualTo("TAKE_BACK");
        assertThat(text(e.payload(), "$.reason")).isEqualTo("TAKEN_BACK");
        assertThat((Integer) JsonPath.read(e.payload(), "$.qtyDelta")).isEqualTo(1);
        assertThat(text(e.payload(), "$.reversesActionId")).isEqualTo(lost.actionId().toString());
        assertThat(text(e.payload(), "$.reversesReason")).as("it nets inside the reason it takes back").isEqualTo("WRITE_OFF_LOST");
        assertThat(e.payload()).contains("\"reversesMovementId\":");
    }

    @Test
    @DisplayName("announcing is part of the transaction: a write-off that is rolled back announces nothing")
    void aRolledBackWriteOffAnnouncesNothing() {
        UUID variant = indexedRing();
        long before = allCostEvents();

        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                writeOff(variant, 2, StockReason.WRITE_OFF_LOST, TUESDAY, ACTOR);
                throw new IllegalStateException("rolled back");
            });
        } catch (IllegalStateException expected) {
            // the point
        }

        assertThat(allCostEvents()).isEqualTo(before);
        assertThat(events(variant)).isEmpty();
    }

    @Test
    @DisplayName("a movement that cost K0.00 is announced too: an estimated lot costed at nothing and sold must still be counted as an estimated sale")
    void aZeroCostMovement() {
        UUID variant = UUID.randomUUID();
        jdbc.update("INSERT INTO product_index (variant_id, title, product_id) VALUES (?, 'Aurelia Ring', ?)", variant, ringProduct);
        ReceiptSnapshot free = new ReceiptSnapshot(UUID.randomUUID(), "C-000077", UUID.randomUUID(), variant, "RING-Z", "Aurelia Ring", "Aurelia Ring - Z", "SELLABLE",
                null, null, MONDAY, true, "ZMW", 2, new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"), List.of(new Slice(2, new BigDecimal("0.00"))),
                1, null, null, null);
        stock.applyReceipt(free, context(77));

        sell(variant, 1, indexedBox("2026-11-09T08:00:00.123456Z"));

        List<Event> events = events(variant);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).payload()).contains("\"unitCost\":0.00").contains("\"estimated\":true");
    }

    @Test
    @DisplayName("who did it is always 'user:<subject>' or 'system:<name>': an actor the ledger holds as something else is announced as system:inventory")
    void theActorIsNormalised() {
        UUID variant = indexedRing();

        writeOff(variant, 1, StockReason.WRITE_OFF_BROKEN, TUESDAY, "null");
        writeOff(variant, 1, StockReason.WRITE_OFF_BROKEN, TUESDAY, "system:consumer");

        assertThat(events(variant)).extracting(Event::actor).containsExactly("system:inventory", "system:consumer");
    }

    // ------------------------------------------------------------------ the product a cost belongs to

    @Test
    @DisplayName("a sale's box belongs to the product it was sold with (not to the box's own product); a box written off belongs to its own; a variant nobody indexed belongs to none")
    void theProductOfACost() {
        UUID ring = indexedRing();
        UUID box = indexedBox("2026-11-09T08:00:00.123456Z");
        UUID unindexed = UUID.randomUUID();
        stock.applyReceipt(ring(unindexed, MONDAY), context(5003));
        commitments.applyProduct(new ProductSnapshot(ringProduct, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        Line sale = new Line(UUID.randomUUID(), ring, ringProduct, 2, UUID.randomUUID(), null);
        UUID reservation = UUID.randomUUID();
        reservations.reserve(reservation, UUID.randomUUID(), List.of(sale), null, ACTOR);

        reservations.consume(reservation, SOLD_ON, ACTOR);
        writeOff(box, 1, StockReason.WRITE_OFF_BROKEN, TUESDAY, ACTOR);
        writeOff(unindexed, 1, StockReason.WRITE_OFF_LOST, TUESDAY, ACTOR);

        List<Event> boxEvents = events(box);
        assertThat(boxEvents).hasSize(2);
        assertThat(text(boxEvents.get(0).payload(), "$.kind")).isEqualTo("PACKAGING");
        assertThat(text(boxEvents.get(0).payload(), "$.actionKind")).isEqualTo("SALE");
        assertThat(text(boxEvents.get(0).payload(), "$.productId")).as("the box went out with a ring").isEqualTo(ringProduct.toString());
        assertThat(text(boxEvents.get(1).payload(), "$.productId")).as("a box written off is the box's own cost").isEqualTo(boxProduct.toString());
        assertThat(text(events(ring).get(0).payload(), "$.productId")).isEqualTo(ringProduct.toString());
        assertThat((Object) JsonPath.read(events(unindexed).get(0).payload(), "$.productId")).as("nothing says which product it is: said so, never guessed").isNull();
    }

    @Test
    @DisplayName("a box swapped after the sale: the box handed back and the replacement sold both belong to the product of the sale, whichever box they are")
    void aSwappedBoxBelongsToTheSalesProduct() {
        UUID ring = indexedRing();
        UUID box = indexedBox("2026-11-09T08:00:00.123456Z");
        UUID velvet = indexedBox("2026-11-09T09:00:00Z");
        commitments.applyProduct(new ProductSnapshot(ringProduct, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        Line sale = new Line(UUID.randomUUID(), ring, ringProduct, 2, UUID.randomUUID(), null);
        UUID reservation = UUID.randomUUID();
        reservations.reserve(reservation, UUID.randomUUID(), List.of(sale), null, ACTOR);
        reservations.consume(reservation, SOLD_ON, ACTOR);
        long beforeSwap = events(box).size() + events(velvet).size();

        swaps.swap(UUID.randomUUID(), UUID.randomUUID(), ringProduct, sale.boxLineId(), UUID.randomUUID(), velvet, "the customer asked for the velvet box", ACTOR);

        List<Event> handedBack = events(box);
        List<Event> replacement = events(velvet);
        assertThat(handedBack.size() + replacement.size()).isGreaterThan((int) beforeSwap);
        Event takeBack = handedBack.get(handedBack.size() - 1);
        assertThat((Integer) JsonPath.read(takeBack.payload(), "$.qtyDelta")).as("the box came back").isPositive();
        assertThat(text(takeBack.payload(), "$.productId")).isEqualTo(ringProduct.toString());
        assertThat(text(replacement.get(0).payload(), "$.productId")).isEqualTo(ringProduct.toString());
    }
}
