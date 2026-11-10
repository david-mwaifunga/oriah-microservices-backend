package com.oriah.notification.events;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinder;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.integration.channel.AbstractMessageChannel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.events.EventHeaders;
import com.oriah.notification.TestcontainersConfiguration;
import com.oriah.notification.repository.NotificationLogRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each order event becomes exactly one message to a person, however often
 * Kafka delivers it.
 *
 * <p>Kafka delivers at least once: after a crash or a rebalance the same
 * event arrives again with the same event-id. Notification is a FACT
 * consumer -- each event is one message to send, order-independent -- so it
 * applies each through {@code Inbox.once}: the "sent" marker
 * (processed_event) and the notification_log row commit together or not at
 * all.
 *
 * <p>What cannot be applied is refused loudly instead of skipped: the
 * exception leaves the consumer and no marker is left behind. On Kafka the
 * oriah-events module's error handler then publishes it to the dead-letter
 * topic -- on its first delivery for a refusal, which would fail the same way
 * again, and after three deliveries in all for a failure that may pass, such
 * as the database being away -- and commits it only once that topic has it.
 *
 * <p>The "through the binder" tests run on Spring Cloud Stream's test binder,
 * which has no Kafka container, so the module's handler is not in play there:
 * the test binder retries with the binder's own retry, reading the same
 * retryable-exceptions, and keeps the last error where Kafka would
 * dead-letter. What they prove is which failures are tried again and which
 * are not; OrderEventBindingTest works out how many deliveries Kafka makes.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class,
        RecordingSender.InPlaceOfTheRealSender.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.function.definition=orderEvents",
        "spring.cloud.stream.bindings.orderEvents-in-0.destination=oriah.order"
})
class OrderEventInboxTest {

    @Autowired
    private RecordingSender sender;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private InputDestination input;
    @Autowired
    private TestChannelBinder binder;
    @Autowired
    @Qualifier("orderEvents")
    private Consumer<Message<byte[]>> orderEvents;
    @Autowired
    private NotificationLogRepository notifications;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /** The binding's own channel: the binder hands every try of every message to the consumer through it. */
    @Autowired
    @Qualifier("orderEvents-in-0")
    private AbstractMessageChannel binding;
    private final List<ChannelInterceptor> interceptors = new ArrayList<>();
    private final List<String> tries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clean() {
        notifications.deleteAll();
        sender.clear();
        binder.resetLastError();
        countTries();
    }

    @AfterEach
    void stopIntercepting() {
        interceptors.forEach(binding::removeInterceptor);
        interceptors.clear();
        tries.clear();
    }

    @Test
    @DisplayName("the same event delivered twice sends ONE message and records it once")
    void redeliveryNotifiesOnce() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> paid = Relayed.orderEvent(eventId, "order.paid", orderId, 2, payload(orderId)).build();

        input.send(paid, "oriah.order");
        input.send(paid, "oriah.order");

        assertThat(notifications.findAll())
                .as("a redelivered event is the same event: the customer gets one 'payment received', not two")
                .hasSize(1);
        assertThat(markers(eventId)).isEqualTo(1);
    }

    // ---- Sent only once the transaction has committed ----------------------
    //
    // Sending is the one step that cannot be taken back: a message that has
    // gone out stays out. So it must happen only for a notification whose
    // row and "applied" marker are committed. Sent inside the transaction, a
    // commit that then failed would leave a message out with nothing
    // recorded -- and Kafka's redelivery would send it a second time.

    @Test
    @DisplayName("a transaction that rolls back after the notification row is written sends nothing")
    void rolledBackTransactionSendsNothing() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> paid = Relayed.orderEvent(eventId, "order.paid", orderId, 2, payload(orderId)).build();

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            orderEvents.accept(paid); // joins this transaction, as it joins the listener's own
            assertThat(notifications.count()).as("the row is written, inside the transaction").isEqualTo(1);
            transaction.setRollbackOnly(); // and then the transaction does not commit
        });

        assertThat(sender.sent()).as("nothing is sent for a transaction that never committed").isEmpty();
        assertThat(notifications.count()).isZero();
        assertThat(markers(eventId)).isZero();
    }

    @Test
    @DisplayName("an event whose transaction commits is sent exactly once, after its row is committed")
    void committedEventIsSentOnceAfterTheCommit() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        input.send(Relayed.orderEvent(eventId, "order.paid", orderId, 2, payload(orderId)).build(), "oriah.order");

        assertThat(sender.sent()).singleElement().satisfies(sent -> {
            assertThat(sent.notification().orderId()).isEqualTo(orderId);
            assertThat(sent.notification().keycloakId()).isEqualTo("kc-alice");
            assertThat(sent.notification().subject()).contains("Payment");
            assertThat(sent.committedWhenSent())
                    .as("the row was already committed -- visible to another connection -- when it was sent")
                    .isTrue();
        });
        assertThat(markers(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a redelivery of an event already sent sends nothing")
    void redeliveryOfAnAppliedEventSendsNothing() {
        UUID orderId = UUID.randomUUID();
        Message<byte[]> paid = Relayed.orderEvent(UUID.randomUUID(), "order.paid", orderId, 2, payload(orderId))
                .build();
        input.send(paid, "oriah.order");
        assertThat(sender.sent()).hasSize(1);
        sender.clear();

        input.send(paid, "oriah.order");

        assertThat(sender.sent()).as("the Inbox finds it applied, so nothing is sent again").isEmpty();
        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an event whose first transaction rolled back is sent exactly once when it is delivered again")
    void redeliveryAfterARollbackSendsOnce() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> paid = Relayed.orderEvent(eventId, "order.paid", orderId, 2, payload(orderId)).build();
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            orderEvents.accept(paid);
            transaction.setRollbackOnly(); // say the commit failed: Kafka will deliver it again
        });

        input.send(paid, "oriah.order");

        assertThat(sender.sent())
                .as("one message: none for the transaction that rolled back, one for the one that committed")
                .singleElement()
                .satisfies(sent -> assertThat(sent.committedWhenSent()).isTrue());
        assertThat(markers(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("events of one order are facts: each is sent once, whatever order they arrive in")
    void factsApplyInAnyOrder() {
        UUID orderId = UUID.randomUUID();
        // paid (seq 2) overtakes created (seq 1). A fact consumer still sends
        // both: each is its own message, not a newer copy of the other.
        input.send(Relayed.orderEvent("order.paid", orderId, 2, payload(orderId)), "oriah.order");
        input.send(Relayed.orderEvent("order.created", orderId, 1, payload(orderId)), "oriah.order");

        assertThat(notifications.findAll()).extracting(log -> log.eventType())
                .containsExactlyInAnyOrder("order.paid", "order.created");
    }

    @Test
    @DisplayName("an event type notification never declared is refused, and nothing is recorded")
    void undeclaredTypeIsRefused() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> refunded = Relayed.orderEvent(eventId, "order.refunded", orderId, 3, payload(orderId)).build();

        assertThatThrownBy(() -> orderEvents.accept(refunded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order.refunded");

        assertThat(notifications.count()).isZero();
        assertThat(markers(eventId)).isZero();
    }

    @Test
    @DisplayName("a payload version notification cannot read is refused, and nothing is recorded")
    void unknownSchemaVersionIsRefused() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> paidV2 = Relayed.orderEvent(eventId, "order.paid", orderId, 2, payload(orderId))
                .setHeader(EventHeaders.SCHEMA_VERSION, "2").build();

        assertThatThrownBy(() -> orderEvents.accept(paidV2)).isInstanceOf(IllegalArgumentException.class);

        assertThat(notifications.count()).isZero();
        assertThat(markers(eventId)).isZero();
    }

    /**
     * Every order event is about an order: order writes aggregate-type
     * "order" (OrderEvent.AGGREGATE_TYPE), in lower case, on every one. A
     * message of an order type that says it is about something else -- or
     * spells it "Order" -- is not one notification knows how to read, so the
     * Inbox refuses it before anything is recorded, and on Kafka it goes to the
     * dead-letter topic on its first delivery.
     */
    @ParameterizedTest(name = "aggregate-type ''{0}''")
    @ValueSource(strings = {"Order", "payment_intent", "product"})
    @DisplayName("an order event that says it is about something other than an order is refused, and nothing is recorded")
    void anotherAggregateIsRefused(String aggregateType) {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> misfiled = Relayed.orderEvent(eventId, "order.paid", orderId, 2, payload(orderId))
                .setHeader(EventHeaders.AGGREGATE_TYPE, aggregateType).build();

        assertThatThrownBy(() -> orderEvents.accept(misfiled))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate 'order'")
                .hasMessageContaining("aggregate-type '" + aggregateType + "'");

        input.send(misfiled, "oriah.order");
        assertThat(tries(eventId))
                .as("through the binder: a refusal fails the same way every time, so it is never tried again")
                .isEqualTo(1);
        assertThat(binder.getLastError()).as("it is dead-lettered").isNotNull();
        assertThat(notifications.count()).isZero();
        assertThat(markers(eventId)).isZero();
        assertThat(sender.sent()).as("nothing is sent").isEmpty();
    }

    /**
     * The envelope names the order the event is about (aggregate-id), and the
     * body is meant to be that order's snapshot. A body about another order
     * would tell one customer about somebody else's order, so it is refused
     * before anything is recorded or sent -- and, being a refusal that fails
     * the same way every time, it goes to the dead-letter topic on its first
     * delivery.
     */
    @Test
    @DisplayName("an event whose body is about another order than its envelope names is refused, and nothing is recorded")
    void bodyAboutAnotherOrderIsRefused() {
        UUID envelopeOrder = UUID.randomUUID();
        UUID bodyOrder = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> mismatched = Relayed.orderEvent(eventId, "order.paid", envelopeOrder, 2, payload(bodyOrder))
                .build();

        assertThatThrownBy(() -> orderEvents.accept(mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(bodyOrder.toString())
                .hasMessageContaining(envelopeOrder.toString());

        input.send(mismatched, "oriah.order");
        assertThat(tries(eventId))
                .as("through the binder: the same body is about the same wrong order on every delivery")
                .isEqualTo(1);
        assertThat(binder.getLastError()).as("it is dead-lettered").isNotNull();
        assertThat(notifications.count()).isZero();
        assertThat(markers(eventId)).isZero();
        assertThat(sender.sent()).as("nobody is told about another order").isEmpty();
    }

    @Test
    @DisplayName("an event whose body names no order is refused, and nothing is recorded")
    void bodyWithoutAnOrderIsRefused() {
        UUID eventId = UUID.randomUUID();
        byte[] noOrder = """
                {"keycloakId":"kc-alice","previousStatus":"CREATED","newStatus":"PAID",
                 "total":4625.00,"currency":"ZMW"}""".getBytes(StandardCharsets.UTF_8);
        Message<byte[]> anonymous = Relayed.orderEvent(eventId, "order.paid", UUID.randomUUID(), 2, noOrder).build();

        assertThatThrownBy(() -> orderEvents.accept(anonymous))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId");

        assertThat(notifications.count()).isZero();
        assertThat(markers(eventId)).isZero();
        assertThat(sender.sent()).as("nothing is sent").isEmpty();
    }

    @Test
    @DisplayName("a message with no event-id is refused, and nothing is sent")
    void missingEventIdIsRefused() {
        UUID orderId = UUID.randomUUID();
        Message<byte[]> anonymous = Relayed.orderEvent(UUID.randomUUID(), "order.paid", orderId, 2, payload(orderId))
                .removeHeader(EventHeaders.EVENT_ID).build();

        assertThatThrownBy(() -> orderEvents.accept(anonymous))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(EventHeaders.EVENT_ID);

        assertThat(notifications.count()).isZero();
    }

    @Test
    @DisplayName("a garbled seq header is refused, and nothing is recorded")
    void garbledSeqIsRefused() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> garbled = Relayed.orderEvent(eventId, "order.paid", orderId, 2, payload(orderId))
                .setHeader(EventHeaders.SEQ, "two").build();

        assertThatThrownBy(() -> orderEvents.accept(garbled))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(EventHeaders.SEQ);

        assertThat(notifications.count()).isZero();
        assertThat(markers(eventId)).isZero();
    }

    @Test
    @DisplayName("when sending throws, the exception leaves the consumer and no marker is left behind")
    void throwingEffectLeavesNoMarker() {
        UUID eventId = UUID.randomUUID();
        // A handled type with a body that breaks the contract: the order id is
        // not a UUID. Reading it is part of sending, so the send throws after
        // the marker was written -- and the marker must roll back with it, or
        // the retry would find it and never send.
        byte[] broken = """
                {"orderId":"not-a-uuid","keycloakId":"kc-alice","previousStatus":"CREATED","newStatus":"PAID",
                 "total":4625.00,"currency":"ZMW"}""".getBytes(StandardCharsets.UTF_8);
        Message<byte[]> paid = Relayed.orderEvent(eventId, "order.paid", UUID.randomUUID(), 2, broken).build();

        assertThatThrownBy(() -> orderEvents.accept(paid)).isInstanceOf(RuntimeException.class);

        assertThat(notifications.count()).isZero();
        assertThat(markers(eventId))
                .as("the marker rolled back with the failed send, so the retry sends it")
                .isZero();
    }

    @Test
    @DisplayName("through the binder: a failure reaches the binder's error handling instead of vanishing in a log")
    void failureReachesTheBinder() {
        UUID eventId = UUID.randomUUID();
        Message<byte[]> refunded = Relayed.orderEvent(eventId, "order.refunded", UUID.randomUUID(), 3,
                payload(UUID.randomUUID())).build();

        // The binder hands the failure to the error handling -- on Kafka, the
        // oriah-events module's, which dead-letters it. The test binder cannot
        // run a DLQ; it keeps the last error instead.
        input.send(refunded, "oriah.order");

        assertThat(binder.getLastError())
                .as("the consumer threw out to the binder, so Kafka would dead-letter it")
                .isNotNull();
        assertThat(markers(eventId)).isZero();
    }

    @Test
    @DisplayName("through the binder: a refused event goes to the dead-letter topic on its first delivery")
    void refusalIsNotRetried() {
        UUID eventId = UUID.randomUUID();

        input.send(Relayed.orderEvent(eventId, "order.refunded", UUID.randomUUID(), 3,
                payload(UUID.randomUUID())).build(), "oriah.order");

        assertThat(tries(eventId))
                .as("an undeclared type is refused the same way every time: a retry cannot change the answer, "
                        + "it only holds up the orders behind it")
                .isEqualTo(1);
        assertThat(binder.getLastError()).as("it is still dead-lettered").isNotNull();
        assertThat(markers(eventId)).isZero();
    }

    @Test
    @DisplayName("through the binder: a body that cannot be read goes to the dead-letter topic on its first delivery")
    void unreadableBodyIsNotRetried() {
        UUID eventId = UUID.randomUUID();
        byte[] unreadable = """
                {"orderId":"not-a-uuid","keycloakId":"kc-alice","previousStatus":"CREATED","newStatus":"PAID",
                 "total":4625.00,"currency":"ZMW"}""".getBytes(StandardCharsets.UTF_8);

        input.send(Relayed.orderEvent(eventId, "order.paid", UUID.randomUUID(), 2, unreadable).build(),
                "oriah.order");

        assertThat(tries(eventId))
                .as("the same bytes fail to read the same way on every try")
                .isEqualTo(1);
        assertThat(binder.getLastError()).as("it is still dead-lettered").isNotNull();
        assertThat(markers(eventId)).isZero();
    }

    @Test
    @DisplayName("through the binder: a failure that can pass on a later try is retried, and the message sent once")
    void passingFailureIsRetried() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        // Stands in for the database being away for a moment: the first try
        // fails on the way into the consumer, the retry gets through.
        intercept(failsFirstTry(eventId));

        input.send(Relayed.orderEvent(eventId, "order.paid", orderId, 2, payload(orderId)).build(), "oriah.order");

        assertThat(tries(eventId)).isEqualTo(2);
        assertThat(notifications.findAll()).hasSize(1);
        assertThat(markers(eventId)).isEqualTo(1);
        assertThat(binder.getLastError()).as("nothing was dead-lettered").isNull();
    }

    /** Every try the binder makes passes through the binding's channel; this counts them by event-id. */
    private void countTries() {
        intercept(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                tries.add(String.valueOf(message.getHeaders().get(EventHeaders.EVENT_ID)));
                return message;
            }
        });
    }

    private long tries(UUID eventId) {
        return tries.stream().filter(eventId.toString()::equals).count();
    }

    private static ChannelInterceptor failsFirstTry(UUID eventId) {
        AtomicBoolean failed = new AtomicBoolean();
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (eventId.toString().equals(message.getHeaders().get(EventHeaders.EVENT_ID))
                        && failed.compareAndSet(false, true)) {
                    throw new TransientDataAccessResourceException("the database is restarting");
                }
                return message;
            }
        };
    }

    private void intercept(ChannelInterceptor interceptor) {
        binding.addInterceptor(interceptor);
        interceptors.add(interceptor);
    }

    private long markers(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = 'notification' AND event_id = ?",
                Long.class, eventId);
    }

    private static byte[] payload(UUID orderId) {
        return ("""
                {"orderId":"%s","keycloakId":"kc-alice","previousStatus":"CREATED","newStatus":"PAID",
                 "total":4625.00,"currency":"ZMW","subtotal":4500.00,"shippingAmount":125.00,
                 "discountAmount":0.00,"deliveryOption":"SELF_COLLECT",
                 "paymentIntentId":"6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b",
                 "lines":[{"variantId":"22222222-2222-4222-8222-222222222222",
                           "title":"Aurelia Solitaire Ring - Ring Size M","quantity":1,"unitPrice":4500.00}]}"""
                .formatted(orderId)).getBytes(StandardCharsets.UTF_8);
    }
}
