package com.oriah.inventory.events;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.events.EventHeaders;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.BoxCommitmentService;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService;
import com.oriah.inventory.service.ReservationService.Line;
import com.oriah.inventory.service.ReservationService.Status;
import com.oriah.inventory.service.StockService;
import com.oriah.inventory.service.StockTestData;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An order that is cancelled or expires lets go of its hold (Phase 7). Order's events say WHICH reservation the order held at that moment
 * ({@code reservationId}), and inventory releases that id and nothing else: an event that arrives late or twice can never touch a newer
 * reservation of the same order, which the order may have taken since (a late payment after a lapse takes a new one).
 *
 * <p>Every other order event is declared and ignored, so a new order event type never lands on the dead-letter topic by surprise; one that
 * is not declared is refused on its first delivery.
 *
 * <p>Keep this class on the SAME Spring context as the other event tests (same imports, same properties): a second, differing context started
 * before {@code ProductEventInboxTest} leaves that test's binder without an error-channel subscriber ("Dispatcher has no subscribers for channel
 * ...productEvents-in-0.errors"), and its four through-the-binder tests fail.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class OrderEventConsumerTest {

    private static final AtomicLong SEQ = new AtomicLong(5000);

    @Autowired
    private InputDestination inputDestination;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("orderEvents")
    private java.util.function.Consumer<Message<byte[]>> orderEvents;

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

    @BeforeEach
    void stockOnTheShelf() {
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        ring = UUID.randomUUID();
        UUID box = UUID.randomUUID();
        StockTestData.stock(stock, ring, 5);
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(3));
        product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
    }

    private UUID hold(UUID order, int qty) {
        UUID id = UUID.randomUUID();
        reservations.reserve(id, order, List.of(new Line(UUID.randomUUID(), ring, product, qty, UUID.randomUUID(), null)), null, ACTOR);
        return id;
    }

    private Message<byte[]> message(String type, UUID orderId, String payload, String aggregateType) {
        return MessageBuilder.withPayload(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.EVENT_ID, UUID.randomUUID().toString())
                .setHeader(EventHeaders.EVENT_TYPE, type)
                .setHeader(EventHeaders.AGGREGATE_TYPE, aggregateType)
                .setHeader(EventHeaders.AGGREGATE_ID, orderId.toString())
                .setHeader(EventHeaders.SEQ, Long.toString(SEQ.incrementAndGet()))
                .setHeader(EventHeaders.OUTBOX_ID, Long.toString(SEQ.get()))
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-12T09:59:00Z")
                .setHeader(EventHeaders.RECORDED_AT, "2026-11-12T09:59:00.125Z")
                .setHeader(EventHeaders.ACTOR, "user:kc-alice")
                .setHeader(EventHeaders.SCHEMA_VERSION, "1")
                .setHeader(EventHeaders.PRODUCER, "order")
                .setHeader(EventHeaders.KAFKA_KEY, orderId.toString().getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private void send(String type, UUID orderId, String payload, String aggregateType) {
        inputDestination.send(message(type, orderId, payload, aggregateType), "oriah.order");
    }

    private void sendOrderEvent(String type, UUID orderId, UUID reservationId) {
        send(type, orderId, "{\"orderId\":\"" + orderId + "\",\"reservationId\":" + (reservationId == null ? "null" : "\"" + reservationId + "\"") + "}", "order");
    }

    private Inventory row() {
        return inventories.findById(ring).orElseThrow();
    }

    @Test
    @DisplayName("a cancelled order lets go of the hold its event names: the pieces are free again")
    void cancelledReleases() {
        UUID order = UUID.randomUUID();
        UUID id = hold(order, 2);

        sendOrderEvent("order.cancelled", order, id);

        assertThat(reservations.get(id).status()).isEqualTo(Status.RELEASED);
        assertThat(row().reserved()).isZero();
        assertThat(row().available()).isEqualTo(5);
    }

    @Test
    @DisplayName("an order that expired lets go of its hold too")
    void expiredReleases() {
        UUID order = UUID.randomUUID();
        UUID id = hold(order, 2);

        sendOrderEvent("order.expired", order, id);

        assertThat(reservations.get(id).status()).isEqualTo(Status.RELEASED);
        assertThat(row().reserved()).isZero();
    }

    @Test
    @DisplayName("a late event naming an old reservation never touches the order's newer one")
    void aStaleEventCannotReleaseANewerReservation() {
        UUID order = UUID.randomUUID();
        UUID first = hold(order, 1);
        // The hold's time has passed (set in the database: this test shares its Spring context with the other event tests, which have no
        // movable clock, and a second differing context in the same JVM upsets the binder's error handling in ProductEventInboxTest).
        jdbc.update("UPDATE reservation SET held_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' WHERE id = ?", first);
        reservations.expireDue(10);
        UUID second = hold(order, 2);

        sendOrderEvent("order.expired", order, first);

        assertThat(reservations.get(first).status()).isEqualTo(Status.EXPIRED);
        assertThat(reservations.get(second).status()).as("the newer hold is untouched").isEqualTo(Status.HELD);
        assertThat(row().reserved()).isEqualTo(2);
    }

    @Test
    @DisplayName("the same event delivered twice releases once, and an event for a hold nobody here made is not an error")
    void redeliveryAndUnknownHolds() {
        UUID order = UUID.randomUUID();
        UUID id = hold(order, 1);
        UUID other = hold(UUID.randomUUID(), 2);
        Message<byte[]> once = MessageBuilder.withPayload(("{\"orderId\":\"" + order + "\",\"reservationId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.EVENT_ID, UUID.randomUUID().toString())
                .setHeader(EventHeaders.EVENT_TYPE, "order.cancelled")
                .setHeader(EventHeaders.AGGREGATE_TYPE, "order")
                .setHeader(EventHeaders.AGGREGATE_ID, order.toString())
                .setHeader(EventHeaders.SEQ, "9001")
                .setHeader(EventHeaders.OUTBOX_ID, "9001")
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-12T09:59:00Z")
                .setHeader(EventHeaders.RECORDED_AT, "2026-11-12T09:59:00.125Z")
                .setHeader(EventHeaders.ACTOR, "user:kc-alice")
                .setHeader(EventHeaders.SCHEMA_VERSION, "1")
                .setHeader(EventHeaders.PRODUCER, "order")
                .setHeader(EventHeaders.KAFKA_KEY, order.toString().getBytes(StandardCharsets.UTF_8))
                .build();

        inputDestination.send(once, "oriah.order");
        inputDestination.send(once, "oriah.order");
        sendOrderEvent("order.cancelled", UUID.randomUUID(), UUID.randomUUID());   // a hold this inventory never made

        assertThat(reservations.get(id).status()).isEqualTo(Status.RELEASED);
        assertThat(reservations.get(other).status()).isEqualTo(Status.HELD);
        assertThat(row().reserved()).as("only the other order's 2 remain").isEqualTo(2);
    }

    @Test
    @DisplayName("an event with no reservation named (an order that never held one) changes nothing and is not an error")
    void noReservationNamed() {
        UUID order = UUID.randomUUID();
        UUID id = hold(order, 1);

        sendOrderEvent("order.cancelled", order, null);

        assertThat(reservations.get(id).status()).isEqualTo(Status.HELD);
    }

    @Test
    @DisplayName("every other order event type is ignored, whatever it carries")
    void otherOrderEventsAreIgnored() {
        UUID order = UUID.randomUUID();
        UUID id = hold(order, 1);

        for (String type : List.of("order.created", "order.paid", "order.fulfilled", "order.shipped", "order.delivered", "order.payment_submitted",
                "order.payment_unsettled")) {
            sendOrderEvent(type, order, id);
        }

        assertThat(reservations.get(id).status()).isEqualTo(Status.HELD);
    }

    @Test
    @DisplayName("an order event type nobody declared is refused, and so is one about another kind of thing")
    void undeclaredEventsAreRefused() {
        UUID order = UUID.randomUUID();
        UUID id = hold(order, 1);

        assertThatThrownBy(() -> orderEvents.accept(message("order.mystery", order, "{\"reservationId\":\"" + id + "\"}", "order")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderEvents.accept(message("order.cancelled", order, "{\"reservationId\":\"" + id + "\"}", "customer")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(reservations.get(id).status()).isEqualTo(Status.HELD);
    }

    @Test
    @DisplayName("a reservation id that is not an id is refused, not shrugged off")
    void aMalformedReservationIdIsRefused() {
        UUID order = UUID.randomUUID();
        assertThatThrownBy(() -> orderEvents.accept(message("order.cancelled", order,
                "{\"orderId\":\"" + order + "\",\"reservationId\":\"not-an-id\"}", "order")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an id");
    }
}
