package com.oriah.finance.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.finance.TestcontainersConfiguration;
import com.oriah.finance.events.Events.Bill;
import com.oriah.finance.events.Events.Paid;
import com.oriah.finance.events.Events.Sold;
import com.oriah.finance.events.Events.Stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every other service's events become the books, through the bindings exactly as the real relays deliver them: the live topics, and the replay topic every producer answers
 * finance's catch-up on. Each event applies once, whichever stream brings it and however many times Kafka delivers it; an event that breaks a promise is refused with
 * nothing written, and written down as an open error that closes itself when the event applies after all.
 *
 * <p>The one that matters most: a sale confirmed before finance existed appears in its month on the profit report, because the whole history is replayed from the beginning.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class FinanceInboxTest {

    private static final AtomicLong OUTBOX = new AtomicLong(1000);
    private static final Instant ARRIVED = Instant.parse("2026-10-14T08:00:00Z");

    @Autowired
    private InputDestination input;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("orderEvents")
    private Consumer<Message<byte[]>> orderEvents;

    @Autowired
    @Qualifier("paymentEvents")
    private Consumer<Message<byte[]>> paymentEvents;

    @Autowired
    @Qualifier("inventoryEvents")
    private Consumer<Message<byte[]>> inventoryEvents;

    @Autowired
    @Qualifier("procurementEvents")
    private Consumer<Message<byte[]>> procurementEvents;

    @Autowired
    @Qualifier("productEvents")
    private Consumer<Message<byte[]>> productEvents;

    @Autowired
    @Qualifier("priceEvents")
    private Consumer<Message<byte[]>> priceEvents;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------ fixtures

    private Message<byte[]> orderPaid(Paid paid) {
        return Relayed.of(UUID.randomUUID(), "order.paid", "order", paid.order.toString(), 1, OUTBOX.incrementAndGet(), paid.arrivedAt, "system:order-settlement", "order", 2,
                paid.body()).build();
    }

    private Paid aSale() {
        return new Paid().line(Sold.of(UUID.randomUUID(), 1, "1000.00", "0.00")).fee("10.00").arrived(ARRIVED.toString());
    }

    private Message<byte[]> relay(String type, String aggregateType, UUID aggregateId, Instant at, String producer, byte[] body) {
        return Relayed.of(UUID.randomUUID(), type, aggregateType, aggregateId.toString(), 1, OUTBOX.incrementAndGet(), at, "user:kc-sam", producer, 1, body).build();
    }

    private UUID eventId(Message<byte[]> m) {
        return UUID.fromString(String.valueOf(m.getHeaders().get("event-id")));
    }

    private boolean processed(Message<byte[]> m) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE consumer = 'finance' AND event_id = ?", Long.class, eventId(m)) > 0;
    }

    private long entries(UUID order) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE order_id = ?", Long.class, order);
    }

    private BigDecimal sum(UUID order, String account) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE order_id = ? AND account = ?", BigDecimal.class, order, account);
    }

    private boolean open(String code, UUID subject) {
        return "RAISE".equals(jdbc.query("SELECT action FROM anomaly_log WHERE code = ? AND subject = ? ORDER BY id DESC LIMIT 1", (rs, n) -> rs.getString(1), code,
                subject.toString()).stream().findFirst().orElse(null));
    }

    /** A message as the producer's replay delivers it: the next row of its outbox, answering the request finance made at start. */
    private Message<byte[]> replayOf(Message<byte[]> live, String producer) {
        Map<String, Object> row = jdbc.queryForMap("SELECT request_id, replayed_through FROM outbox_catch_up WHERE producer = ?", producer);
        return Relayed.replayed(live, (UUID) row.get("request_id"), ((Number) row.get("replayed_through")).longValue());
    }

    // ------------------------------------------------------------------ the catch-up

    @Test
    @DisplayName("finance asks every producer for its whole history at start: one catch-up per producer is recorded, waiting for the answer")
    void theCatchUpWasRequested() {
        assertThat(jdbc.queryForList("SELECT producer FROM outbox_catch_up ORDER BY producer", String.class)).containsExactly("catalog", "inventory", "order", "payment", "procurement");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_catch_up WHERE request_id IS NULL", Long.class)).isZero();
    }

    @Test
    @DisplayName("THE REPLAY TEST: a sale confirmed before finance existed (its sale, its payment, its cost) is brought by the replay and appears in its month, once")
    void aSaleFromBeforeFinanceExistedAppearsInItsMonth() {
        Paid paid = aSale();
        UUID conf = paid.confirmation;
        Message<byte[]> sale = orderPaid(paid);
        Message<byte[]> payment = relay("payment.confirmed", "payment_confirmation", conf, ARRIVED, "payment", Events.confirmed(conf, paid.order, "1000.00", "10.00", ARRIVED, 1));
        Stock cost = new Stock().sale("SELLABLE", -1, "400.00").at(ARRIVED.toString());
        Message<byte[]> stockCost = relay("stock.cost_recorded", "stock_action", cost.actionId, ARRIVED, "inventory", cost.body());

        input.send(replayOf(sale, "order"), "oriah.replay.finance");
        input.send(replayOf(payment, "payment"), "oriah.replay.finance");
        input.send(replayOf(stockCost, "inventory"), "oriah.replay.finance");

        assertThat(sum(paid.order, "REVENUE_GOODS")).isEqualByComparingTo("1000.00");
        assertThat(sum(paid.order, "EXPENSE")).as("the fee payment charged").isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM ledger_entry WHERE movement_id = ? AND effect_month = '2026-10-01'", BigDecimal.class, cost.movementId))
                .as("the cost of the goods, in the month of the sale").isEqualByComparingTo("400.00");
        assertThat(jdbc.queryForObject("SELECT replayed_through FROM outbox_catch_up WHERE producer = 'order'", Long.class)).isGreaterThan(0L);
        // the same events, delivered live, change nothing
        orderEvents.accept(sale);
        paymentEvents.accept(payment);
        inventoryEvents.accept(stockCost);
        assertThat(sum(paid.order, "REVENUE_GOODS")).isEqualByComparingTo("1000.00");
        assertThat(sum(paid.order, "EXPENSE")).isEqualByComparingTo("10.00");
    }

    // ------------------------------------------------------------------ the live topics

    @Test
    @DisplayName("every live topic reaches its applier: a sale, money, a cost, a bill for the month, a product name, an order, and a saved price that is declared and ignored")
    void everyTopicReachesItsApplier() {
        Paid paid = aSale();
        UUID product = UUID.randomUUID();
        UUID consignment = UUID.randomUUID();
        UUID newOrder = UUID.randomUUID();
        Stock cost = new Stock().sale("SELLABLE", -1, "50.00");
        Message<byte[]> price = relay("price.set", "variant_price", UUID.randomUUID(), ARRIVED, "catalog", "{}".getBytes());

        orderEvents.accept(orderPaid(paid));
        orderEvents.accept(relay("order.created", "order", newOrder, ARRIVED, "order", Events.orderCreated(newOrder, "PHONE")));
        paymentEvents.accept(relay("payment.confirmed", "payment_confirmation", UUID.randomUUID(), ARRIVED, "payment",
                Events.confirmed(UUID.randomUUID(), UUID.randomUUID(), "500.00", "5.00", ARRIVED, 1)));
        inventoryEvents.accept(relay("stock.cost_recorded", "stock_action", cost.actionId, cost.occurredAt, "inventory", cost.body()));
        procurementEvents.accept(Relayed.of(UUID.randomUUID(), "consignment.period_costs", "consignment", consignment.toString(), 1, OUTBOX.incrementAndGet(),
                Instant.parse("2026-10-20T08:00:00Z"), "user:kc-maya", "procurement", 1,
                Events.periodCosts(consignment, 1, Instant.parse("2026-10-20T08:00:00Z"), null, List.of(new Bill(1, "FREIGHT", "25.00", "2026-10-31")), List.of())).build());
        productEvents.accept(Relayed.of(UUID.randomUUID(), "product.created", "product", product.toString(), 1, OUTBOX.incrementAndGet(), ARRIVED, "user:kc-maya", "catalog", 1,
                Events.product(product, "Aurelia Ring", "SELLABLE")).build());
        priceEvents.accept(price);

        assertThat(entries(paid.order)).as("the sale's goods: its payment is another confirmation in this test").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_ref WHERE order_id = ?", Long.class, newOrder)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE movement_id = ?", Long.class, cost.movementId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE consignment_id = ?", Long.class, consignment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM product_name WHERE product_id = ?", String.class, product)).isEqualTo("Aurelia Ring");
        assertThat(processed(price)).as("a price is declared and ignored: marked, never applied").isTrue();
    }

    @Test
    @DisplayName("what finance has no use for is declared and ignored, so a replay of any producer's whole log never stalls: marked as seen, nothing applied")
    void ignoredTypes() {
        List<Message<byte[]>> ignored = List.of(
                relay("order.cancelled", "order", UUID.randomUUID(), ARRIVED, "order", "{}".getBytes()),
                relay("order.fulfilled", "order", UUID.randomUUID(), ARRIVED, "order", "{}".getBytes()),
                relay("order.payment_unsettled", "order", UUID.randomUUID(), ARRIVED, "order", "{}".getBytes()),
                relay("payment.rejected", "payment_claim", UUID.randomUUID(), ARRIVED, "payment", "{}".getBytes()),
                relay("reservation.consumed", "reservation", UUID.randomUUID(), ARRIVED, "inventory", "{}".getBytes()),
                relay("box.swapped", "box_swap", UUID.randomUUID(), ARRIVED, "inventory", "{}".getBytes()));

        orderEvents.accept(ignored.get(0));
        orderEvents.accept(ignored.get(1));
        orderEvents.accept(ignored.get(2));
        paymentEvents.accept(ignored.get(3));
        inventoryEvents.accept(ignored.get(4));
        inventoryEvents.accept(ignored.get(5));

        for (Message<byte[]> m : ignored) {
            assertThat(processed(m)).isTrue();
        }
    }

    @Test
    @DisplayName("a snapshot older than the newest already applied is skipped: the name follows the newest product event, whichever order they arrive in")
    void snapshotsInOrder() {
        UUID product = UUID.randomUUID();
        Instant at = ARRIVED;

        productEvents.accept(Relayed.of(UUID.randomUUID(), "product.updated", "product", product.toString(), 3, OUTBOX.incrementAndGet(), at, "user:kc-maya", "catalog", 1,
                Events.product(product, "newest name", "SELLABLE")).build());
        productEvents.accept(Relayed.of(UUID.randomUUID(), "product.updated", "product", product.toString(), 2, OUTBOX.incrementAndGet(), at, "user:kc-maya", "catalog", 1,
                Events.product(product, "older name", "SELLABLE")).build());

        assertThat(jdbc.queryForObject("SELECT title FROM product_name WHERE product_id = ?", String.class, product)).isEqualTo("newest name");
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("a sale that does not add up is refused with nothing written and written down as an open error; delivered again corrected, it applies and the error closes itself")
    void aRefusalIsWrittenDownAndClosesItself() {
        Paid broken = aSale().set("total", new BigDecimal("999.99"));
        Message<byte[]> refused = orderPaid(broken);
        UUID id = eventId(refused);

        assertThatThrownBy(() -> orderEvents.accept(refused)).isInstanceOf(IllegalArgumentException.class);

        assertThat(entries(broken.order)).isZero();
        assertThat(processed(refused)).as("a refused event is not marked as applied").isFalse();
        assertThat(open("event-refused", id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT detail::text FROM anomaly_log WHERE code = 'event-refused' AND subject = ? ORDER BY id LIMIT 1", String.class, id.toString()))
                .as("what happened, never the message").contains("order.paid").doesNotContain("999.99");

        Paid fixed = aSale().order(broken.order).confirmation(broken.confirmation);
        Message<byte[]> again = Relayed.of(id, "order.paid", "order", broken.order.toString(), 1, OUTBOX.incrementAndGet(), fixed.arrivedAt, "system:order-settlement", "order", 2,
                fixed.body()).build();
        orderEvents.accept(again);

        assertThat(entries(broken.order)).isGreaterThan(0);
        assertThat(open("event-refused", id)).as("the system closed it: the event applied after all").isFalse();
    }

    @Test
    @DisplayName("an event type nobody declared is refused on its first delivery, written down, and never marked as applied")
    void anUndeclaredTypeIsRefused() {
        Message<byte[]> surprise = relay("order.refunded", "order", UUID.randomUUID(), ARRIVED, "order", "{}".getBytes());

        assertThatThrownBy(() -> orderEvents.accept(surprise)).isInstanceOf(IllegalArgumentException.class);

        assertThat(processed(surprise)).isFalse();
        assertThat(open("event-refused", eventId(surprise))).isTrue();
    }
}
