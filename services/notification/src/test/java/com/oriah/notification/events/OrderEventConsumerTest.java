package com.oriah.notification.events;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.oriah.events.EventHeaders;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.MutableClock;
import com.oriah.notification.TestData;
import com.oriah.notification.delivery.MessageTransport;
import com.oriah.notification.model.MessageStatus;
import com.oriah.notification.model.NotificationLog;
import com.oriah.notification.model.RecipientKind;
import com.oriah.notification.model.StatusReason;
import com.oriah.notification.orders.OrderRefs;
import com.oriah.notification.repository.NotificationLogRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the consumer writes down for each order event: one row for the customer, with the words and with what is to become of it.
 *
 * <p>The consumer SENDS NOTHING. It records the message in the same transaction as the "applied" marker and returns; a scheduled sender does
 * every send. A row is either waiting to be emailed (RECORDED), or it is final at once with the reason: this kind of message is shown on the
 * website only, it is already too old to be worth sending, or it announces a dispatch step that was keyed long after it happened.
 */
@ServiceTest
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class OrderEventConsumerTest {

    /** The order in the two version-1 example payloads (src/test/resources/events). */
    private static final UUID EXAMPLE_ORDER = UUID.fromString("8a1f4b2e-3c5d-4e6f-9a0b-1c2d3e4f5a6b");
    /** The order in order's own contract example of order.paid version 2 (a copy of order's contracts/order-paid-v2.json). */
    private static final UUID V2_ORDER = UUID.fromString("0f9a4c2e-6b1d-4e8f-a3c5-7d9e1b2f4a60");

    @Autowired
    private InputDestination inputDestination;
    @Autowired
    @Qualifier("orderEvents")
    private Consumer<Message<byte[]>> orderEvents;
    @Autowired
    private NotificationLogRepository repository;
    @Autowired
    private OrderRefs orderRefs;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
    }

    // ---- one row, with its status ----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("order.paid writes one row for the customer that waits to be emailed: due at once, worth sending for six hours after the event was recorded")
    void paidEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        inputDestination.send(Relayed.orderEvent(eventId, "order.paid", orderId, 2, objectMapper.writeValueAsBytes(Map.of(
                "orderId", orderId.toString(), "keycloakId", "kc-alice", "previousStatus", "CREATED", "newStatus", "PAID",
                "total", new BigDecimal("200.00"), "currency", "ZMW"))).build(), "oriah.order");

        NotificationLog row = told(orderId);
        assertThat(row.eventType()).isEqualTo("order.paid");
        assertThat(row.keycloakId()).isEqualTo("kc-alice");
        assertThat(row.recipientKind()).isEqualTo(RecipientKind.SHOPPER);
        assertThat(row.channel()).isEqualTo("email");
        assertThat(row.subject()).isEqualTo("Payment received - thank you");
        assertThat(row.status()).isEqualTo(MessageStatus.RECORDED);
        assertThat(row.statusReason()).isNull();
        assertThat(row.recordedAt()).isEqualTo(MutableClock.START);
        assertThat(row.statusAt()).isEqualTo(MutableClock.START);
        assertThat(row.nextAttemptAt()).isEqualTo(MutableClock.START);
        assertThat(row.sendBefore()).isEqualTo(Instant.parse("2026-11-09T13:30:00.125Z"));
        assertThat(row.sentAt()).isNull();
        assertThat(row.eventId()).isEqualTo(eventId);
        assertThat(row.eventSeq()).isEqualTo(2L);
        assertThat(row.eventRecordedAt()).isEqualTo(Instant.parse("2026-11-09T07:30:00.125Z"));
    }

    @Test
    @DisplayName("the kinds that are emailed wait to be sent; every other kind is final at once as 'website only'")
    void theEmailedOnesAreRecordedAndTheRestAreBellOnly() throws Exception {
        record Case(String type, String deliveryOption, String previousStatus, boolean emailed) {
        }
        for (Case c : List.of(
                new Case("order.created", "OWN_DELIVERY", null, false),
                new Case("order.paid", "OWN_DELIVERY", "PAYMENT_SUBMITTED", true),
                new Case("order.fulfilled", "SELF_COLLECT", "PAID", true),
                new Case("order.fulfilled", "YANGO", "PAID", false),
                new Case("order.shipped", "YANGO", "FULFILLED", true),
                new Case("order.delivered", "YANGO", "SHIPPED", true),
                new Case("order.delivered", "SELF_COLLECT", "FULFILLED", false),
                new Case("order.cancelled", "YANGO", "CREATED", false),
                new Case("order.expired", "YANGO", "CREATED", false),
                new Case("order.expired", "YANGO", "PAYMENT_SUBMITTED", true))) {
            UUID orderId = UUID.randomUUID();
            Map<String, Object> payload = snapshot(orderId, "kc-bob");
            payload.put("deliveryOption", c.deliveryOption());
            payload.put("previousStatus", c.previousStatus());
            send(c.type(), orderId, payload);

            NotificationLog row = told(orderId);
            String what = c.type() + " " + c.deliveryOption() + " after " + c.previousStatus();
            if (c.emailed()) {
                assertThat(row.status()).as(what).isEqualTo(MessageStatus.RECORDED);
            } else {
                assertThat(row.status()).as(what).isEqualTo(MessageStatus.NOT_SENT);
                assertThat(row.statusReason()).as(what).isEqualTo(StatusReason.BELL_ONLY);
                assertThat(row.nextAttemptAt()).as(what).isNull();
            }
        }
    }

    @Test
    @DisplayName("the consumer has no way to send: its handler takes no transport")
    void theHandlerTakesNoTransport() {
        assertThat(Arrays.stream(OrderEventConsumer.Handler.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                .noneMatch(MessageTransport.class::isAssignableFrom)
                .noneMatch(type -> type.getSimpleName().contains("Sender"));
    }

    // ---- too late, and steps keyed long after they happened -------------------------------------------------------------------------------

    @Test
    @DisplayName("an event recorded more than six hours ago is written down for the website and not emailed: it is too late")
    void anEventOlderThanTheWindowIsRecordedNotSent() throws Exception {
        UUID orderId = UUID.randomUUID();
        inputDestination.send(Relayed.orderEvent(UUID.randomUUID(), "order.paid", orderId, 2, objectMapper.writeValueAsBytes(snapshot(orderId, "kc-alice")))
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-09T01:00:00Z")
                .setHeader(EventHeaders.RECORDED_AT, "2026-11-09T01:30:59Z").build(), "oriah.order");

        NotificationLog row = told(orderId);
        assertThat(row.status()).isEqualTo(MessageStatus.NOT_SENT);
        assertThat(row.statusReason()).isEqualTo(StatusReason.TOO_LATE);
        assertThat(row.subject()).isEqualTo("Payment received - thank you");
    }

    @Test
    @DisplayName("a dispatch step keyed more than a day after it happened is not emailed: the customer already has the parcel")
    void aStepKeyedMoreThanADayLateIsNotSent() throws Exception {
        for (String type : List.of("order.fulfilled", "order.shipped", "order.delivered")) {
            UUID orderId = UUID.randomUUID();
            Map<String, Object> payload = snapshot(orderId, "kc-alice");
            payload.put("deliveryOption", type.equals("order.fulfilled") ? "SELF_COLLECT" : "YANGO");
            inputDestination.send(Relayed.orderEvent(UUID.randomUUID(), type, orderId, 3, objectMapper.writeValueAsBytes(payload))
                    .setHeader(EventHeaders.OCCURRED_AT, "2026-11-08T07:29:00Z").build(), "oriah.order");

            assertThat(told(orderId).statusReason()).as(type).isEqualTo(StatusReason.BACKDATED_STEP);
        }
    }

    @Test
    @DisplayName("a payment confirmed a day after the money came is still emailed: 'payment received' is news whenever staff record it")
    void aPaymentConfirmedADayAfterTheMoneyCameIsStillRecorded() throws Exception {
        UUID orderId = UUID.randomUUID();
        inputDestination.send(Relayed.orderEvent(UUID.randomUUID(), "order.paid", orderId, 2, objectMapper.writeValueAsBytes(snapshot(orderId, "kc-alice")))
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-07T07:29:00Z").build(), "oriah.order");

        assertThat(told(orderId).status()).isEqualTo(MessageStatus.RECORDED);
    }

    // ---- the words -----------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the contract's example order.created: held, nothing paid yet, the total written K5,875.00 and the order by its eight-character code")
    void exampleCreatedNotifies() throws Exception {
        inputDestination.send(Relayed.orderEvent("order.created", EXAMPLE_ORDER, 1, fixture("events/order.created.v1.json")), "oriah.order");

        NotificationLog row = told(EXAMPLE_ORDER);
        assertThat(row.eventType()).isEqualTo("order.created");
        assertThat(row.keycloakId()).isEqualTo("kc-alice");
        assertThat(row.subject()).isEqualTo("We are holding your order");
        assertThat(row.body()).contains("order 8A1F4B2E").contains("The total is K5,875.00.").contains("Nothing is paid yet")
                .doesNotContain(EXAMPLE_ORDER.toString()).doesNotContain("5875.00 ZMW");
        assertThat(row.statusReason()).isEqualTo(StatusReason.BELL_ONLY);
    }

    @Test
    @DisplayName("the contract's example order.paid in its first version (no receipt number): the amount, and what happens next for a pickup")
    void examplePaidNotifies() throws Exception {
        inputDestination.send(Relayed.orderEvent("order.paid", EXAMPLE_ORDER, 2, fixture("events/order.paid.v1.json")), "oriah.order");

        NotificationLog row = told(EXAMPLE_ORDER);
        assertThat(row.subject()).isEqualTo("Payment received - thank you");
        assertThat(row.body()).isEqualTo("We have received your payment of K5,875.00 for order 8A1F4B2E. "
                + "We will tell you when your order is ready to collect. This message confirms your payment. It is not a tax invoice.");
    }

    @Test
    @DisplayName("order.paid in its second version: the receipt number is told, and remembered for the messages that follow")
    void paidVersionTwoKeepsItsReceiptNumber() throws Exception {
        inputDestination.send(Relayed.orderEvent(UUID.randomUUID(), "order.paid", V2_ORDER, 2, fixture("events/order.paid.v2.json"))
                .setHeader(EventHeaders.SCHEMA_VERSION, "2").build(), "oriah.order");

        assertThat(told(V2_ORDER).body()).isEqualTo("We have received your payment of K2,595.50 for order 0F9A4C2E. "
                + "Your receipt number is OR-000123. We will tell you when your order is on its way. "
                + "This message confirms your payment. It is not a tax invoice.");
        assertThat(orderRefs.find(V2_ORDER)).hasValueSatisfying(ref -> {
            assertThat(ref.receiptNumber()).isEqualTo("OR-000123");
            assertThat(ref.paidAt()).isEqualTo(Instant.parse("2026-11-09T07:30:00.125Z"));
            assertThat(ref.keycloakId()).isEqualTo("8c2d4e6f-1a3b-4c5d-9e7f-0a1b2c3d4e5f");
            assertThat(ref.phoneOrder()).isFalse();
        });
    }

    @Test
    @DisplayName("once an order is paid, later messages name it by its receipt number")
    void aLaterMessageNamesTheOrderByItsReceipt() throws Exception {
        inputDestination.send(Relayed.orderEvent(UUID.randomUUID(), "order.paid", V2_ORDER, 2, fixture("events/order.paid.v2.json"))
                .setHeader(EventHeaders.SCHEMA_VERSION, "2").build(), "oriah.order");
        Map<String, Object> packed = snapshot(V2_ORDER, "8c2d4e6f-1a3b-4c5d-9e7f-0a1b2c3d4e5f");
        packed.put("deliveryOption", "OWN_DELIVERY");
        inputDestination.send(Relayed.orderEvent(UUID.randomUUID(), "order.fulfilled", V2_ORDER, 3, objectMapper.writeValueAsBytes(packed)).build(),
                "oriah.order");

        assertThat(told(V2_ORDER, "order.fulfilled").body()).isEqualTo("Order OR-000123 is packed. We will tell you when it is on its way.");
    }

    @Test
    @DisplayName("an order that ends after the customer said they paid is told it could not be completed, and that their money is safe if they did pay")
    void aClaimedOrderThatEndsIsToldItCouldNotBeCompleted() throws Exception {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> payload = snapshot(orderId, "kc-alice");
        payload.put("previousStatus", "PAYMENT_SUBMITTED");
        payload.put("newStatus", "EXPIRED");
        send("order.expired", orderId, payload);

        NotificationLog row = told(orderId);
        assertThat(row.subject()).isEqualTo("We could not complete your order");
        assertThat(row.body()).contains("your money is safe with us").doesNotContain("could not confirm").doesNotContain("not paid");
        assertThat(row.status()).isEqualTo(MessageStatus.RECORDED);
    }

    @Test
    @DisplayName("an order that expired with no payment claimed says so and names no figure")
    void anUnclaimedExpiryNamesNoFigure() throws Exception {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> payload = snapshot(orderId, "kc-alice");
        payload.put("previousStatus", "CREATED");
        send("order.expired", orderId, payload);

        NotificationLog row = told(orderId);
        assertThat(row.subject()).isEqualTo("Your order has expired");
        assertThat(row.body()).contains("was not paid in time").doesNotContain("K100.00");
    }

    @Test
    @DisplayName("a cancelled order is no longer promised a refund: only an unpaid order can be cancelled")
    void aCancelledOrderNoLongerPromisesARefund() throws Exception {
        UUID orderId = UUID.randomUUID();
        send("order.cancelled", orderId, snapshot(orderId, "kc-alice"));

        assertThat(told(orderId).subject()).isEqualTo("Your order was cancelled");
        assertThat(told(orderId).body()).doesNotContainIgnoringCase("refund").contains("If you have already sent money for it, please contact us.");
    }

    @Test
    @DisplayName("an order that goes by courier: packed, on its way, delivered")
    void anOrderThatIsDeliveredKeepsItsWording() throws Exception {
        UUID packed = UUID.randomUUID();
        UUID arrived = UUID.randomUUID();
        UUID onItsWay = UUID.randomUUID();
        sendStatus("order.fulfilled", packed, "FULFILLED", "YANGO");
        sendStatus("order.delivered", arrived, "DELIVERED", "OWN_DELIVERY");
        sendStatus("order.shipped", onItsWay, "SHIPPED", "PLATINUM");

        assertThat(told(packed).subject()).isEqualTo("Your order is packed");
        assertThat(told(packed).body()).contains("is packed. We will tell you when it is on its way.");
        assertThat(told(arrived).subject()).isEqualTo("Your order has been delivered");
        assertThat(told(arrived).body()).contains("has been delivered. We hope you love it.");
        assertThat(told(onItsWay).subject()).isEqualTo("Your order is on its way");
        assertThat(told(onItsWay).body()).contains("has left our shop and is on its way to you.").doesNotContain("courier directly");
    }

    @Test
    @DisplayName("when the customer pays the courier on arrival, 'on its way' says so (the dispatch step carries who pays)")
    void aRecipientPaidCourierIsSaidOnTheWay() throws Exception {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> payload = snapshot(orderId, "kc-bob");
        payload.put("deliveryOption", "YANGO");
        payload.put("dispatch", Map.of("dispatchedAt", "2026-11-09T07:29:00Z", "payer", "RECIPIENT_PAYS"));
        send("order.shipped", orderId, payload);

        assertThat(told(orderId).body()).endsWith("You pay the courier directly when it arrives.");
    }

    @Test
    @DisplayName("an order the shopper collects is 'ready to collect' when it is packed and 'collected' when it is handed over: nothing is shipped or delivered to them")
    void aCollectedOrderIsToldReadyAndCollected() throws Exception {
        UUID packed = UUID.randomUUID();
        UUID handedOver = UUID.randomUUID();
        sendStatus("order.fulfilled", packed, "FULFILLED", "SELF_COLLECT");
        sendStatus("order.delivered", handedOver, "DELIVERED", "SELF_COLLECT");

        assertThat(told(packed).subject()).isEqualTo("Your order is ready to collect");
        assertThat(told(packed).body()).contains("ready for you to collect").doesNotContain("shipment").doesNotContain("shipped");
        assertThat(told(handedOver).subject()).isEqualTo("Your order has been collected");
        assertThat(told(handedOver).body()).contains("has been collected").doesNotContain("delivered");
    }

    @Test
    @DisplayName("an order with no recorded delivery option (placed before Phase 7) is told the delivery wording, never a guess that it was collected")
    void anUnknownOptionIsToldTheCourierWording() throws Exception {
        UUID packed = UUID.randomUUID();
        send("order.fulfilled", packed, snapshot(packed, "kc-bob"));

        assertThat(told(packed).subject()).isEqualTo("Your order is packed");
    }

    // ---- what is refused, and what is applied in silence -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a body that is not JSON is refused: the exception leaves the consumer and nothing is recorded")
    void malformedPayloadIsRefused() {
        Message<byte[]> garbage = Relayed.orderEvent("order.paid", UUID.randomUUID(), 2, "not-json".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> orderEvents.accept(garbage)).isInstanceOf(RuntimeException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("notification reads every field of the order event contract's first version: names, order, types, scale and nulls")
    void readsTheWholeContract() throws Exception {
        // Read with the consumer's own mapper and written back, each example must come out exactly as order wrote it. A field notification's
        // view leaves out, misnames or mistypes -- or an amount that loses its scale -- shows up as a difference here.
        for (String path : List.of("events/order.created.v1.json", "events/order.paid.v1.json")) {
            byte[] json = fixture(path);
            OrderEventPayload read = objectMapper.readValue(json, OrderEventPayload.class);

            assertThat(objectMapper.writeValueAsString(read)).as(path).isEqualTo(compact(json));
        }
    }

    // ---- a caller with no account: words for a person to send ---------------------------------------------------------------------------------
    //
    // Somebody who ordered by phone has no account and no email, and no machine holds or uses their phone number. For a sale taken LIVE on the
    // phone the system writes the words ("payment received", "ready to collect", "on its way") and sales staff send them from the shop's own
    // WhatsApp. For a sale typed in later from paper it writes nothing, however soon it was typed: that customer already has their goods.
    // Which kind a sale was is what order says on the event (saleKind), never a guess from dates.

    private Map<String, Object> phoneSnapshot(UUID orderId, String saleKind, String deliveryOption) {
        Map<String, Object> payload = snapshot(orderId, null);
        payload.put("channel", "PHONE");
        payload.put("deliveryOption", deliveryOption);
        if (saleKind != null) {
            payload.put("saleKind", saleKind);
        }
        return payload;
    }

    private List<NotificationLog> rowsOf(UUID orderId) {
        return repository.findAll().stream().filter(l -> orderId.equals(l.orderId())).toList();
    }

    @Test
    @DisplayName("a sale taken live on the phone gets words for a person to send: payment received, ready to collect, on its way; and nothing else")
    void aLivePhoneOrderGetsWordsForAPersonToSend() throws Exception {
        UUID pickup = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        for (String type : new String[] {"order.created", "order.paid", "order.fulfilled", "order.delivered", "order.cancelled", "order.expired"}) {
            send(type, pickup, phoneSnapshot(pickup, "LIVE_PHONE", "SELF_COLLECT"));
        }
        for (String type : new String[] {"order.created", "order.paid", "order.fulfilled", "order.shipped", "order.delivered"}) {
            send(type, delivery, phoneSnapshot(delivery, "LIVE_PHONE", "YANGO"));
        }

        assertThat(rowsOf(pickup)).extracting(NotificationLog::eventType).containsExactlyInAnyOrder("order.paid", "order.fulfilled");
        assertThat(rowsOf(delivery)).extracting(NotificationLog::eventType).containsExactlyInAnyOrder("order.paid", "order.shipped");
        assertThat(repository.findAll()).allSatisfy(row -> {
            assertThat(row.recipientKind()).isEqualTo(RecipientKind.CALLER);
            assertThat(row.keycloakId()).as("a caller's row has no owner, so it is in nobody's bell").isNull();
            assertThat(row.channel()).isEqualTo("by-hand");
            assertThat(row.status()).isEqualTo(MessageStatus.BY_HAND);
            assertThat(row.statusReason()).isNull();
            assertThat(row.nextAttemptAt()).as("it is never due to be sent by the system").isNull();
            assertThat(row.recordedAt()).isEqualTo(MutableClock.START);
        });
        assertThat(told(pickup, "order.paid").subject()).isEqualTo("Payment received");
        assertThat(told(pickup, "order.paid").body()).isEqualTo("Oriah Jewelry: we have received your payment of K100.00. Thank you.");
        assertThat(told(pickup, "order.fulfilled").subject()).isEqualTo("Ready to collect");
        assertThat(told(delivery, "order.shipped").subject()).isEqualTo("On its way");
    }

    @Test
    @DisplayName("a sale typed in later from paper gets no words, however soon it was typed")
    void aPaperSaleGetsNoWordsHoweverSoonItWasKeyed() throws Exception {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> typedAtOnce = phoneSnapshot(orderId, "LATE_PAPER", "SELF_COLLECT");
        // It happened seconds before it was typed: by its dates alone it would look like a live sale.
        typedAtOnce.put("occurredAt", "2026-11-09T07:29:50Z");
        for (String type : new String[] {"order.created", "order.paid", "order.fulfilled", "order.delivered"}) {
            send(type, orderId, typedAtOnce);
        }

        assertThat(rowsOf(orderId)).isEmpty();
        assertThat(orderRefs.find(orderId)).hasValueSatisfying(ref -> {
            assertThat(ref.phoneOrder()).isTrue();
            assertThat(ref.keycloakId()).isNull();
            assertThat(ref.livePhoneSale()).isFalse();
        });
    }

    @Test
    @DisplayName("a phone order whose events name no kind (written before order said it) gets no words: nothing is guessed")
    void aPhoneOrderWhoseEventsNameNoKindGetsNoWords() throws Exception {
        for (String type : new String[] {"order.created", "order.paid", "order.fulfilled", "order.shipped", "order.delivered", "order.cancelled", "order.expired"}) {
            UUID orderId = UUID.randomUUID();
            send(type, orderId, phoneSnapshot(orderId, null, "SELF_COLLECT"));

            assertThat(rowsOf(orderId)).as(type + " tells nobody").isEmpty();
            assertThat(orderRefs.find(orderId)).as(type + " is remembered as a phone order").hasValueSatisfying(ref -> {
                assertThat(ref.phoneOrder()).isTrue();
                assertThat(ref.livePhoneSale()).isNull();
            });
        }
    }

    @Test
    @DisplayName("the kind is learnt from whichever event first carries it, and kept: a payment that says 'live' is enough, and a later event cannot unsay it")
    void theKindIsLearntFromWhicheverEventFirstCarriesIt() throws Exception {
        UUID orderId = UUID.randomUUID();
        send("order.paid", orderId, phoneSnapshot(orderId, "LIVE_PHONE", "SELF_COLLECT"));
        send("order.fulfilled", orderId, phoneSnapshot(orderId, null, "SELF_COLLECT"));

        assertThat(rowsOf(orderId)).extracting(NotificationLog::eventType).containsExactlyInAnyOrder("order.paid", "order.fulfilled");
    }

    @Test
    @DisplayName("a kind notification does not know gets no words")
    void aKindNotificationDoesNotKnowGetsNoWords() throws Exception {
        UUID orderId = UUID.randomUUID();
        send("order.paid", orderId, phoneSnapshot(orderId, "COUNTER_SALE", "SELF_COLLECT"));

        assertThat(rowsOf(orderId)).isEmpty();
        assertThat(orderRefs.find(orderId).orElseThrow().livePhoneSale()).isNull();
    }

    @Test
    @DisplayName("a step of a live phone sale keyed more than a day after it happened gets no words; its payment still does")
    void aStepKeyedADayLateGetsNoWords() throws Exception {
        UUID orderId = UUID.randomUUID();
        byte[] body = objectMapper.writeValueAsBytes(phoneSnapshot(orderId, "LIVE_PHONE", "SELF_COLLECT"));
        inputDestination.send(Relayed.orderEvent(UUID.randomUUID(), "order.paid", orderId, 2, body)
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-07T07:29:00Z").build(), "oriah.order");
        inputDestination.send(Relayed.orderEvent(UUID.randomUUID(), "order.fulfilled", orderId, 3, body)
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-07T07:29:00Z").build(), "oriah.order");

        assertThat(rowsOf(orderId)).extracting(NotificationLog::eventType).containsExactly("order.paid");
    }

    @Test
    @DisplayName("the same payment of a phone sale announced again writes no second set of words")
    void aRepeatedPaymentOfAPhoneSaleWritesNothingMore() throws Exception {
        UUID orderId = UUID.randomUUID();
        send("order.paid", orderId, phoneSnapshot(orderId, "LIVE_PHONE", "SELF_COLLECT"));
        Map<String, Object> again = phoneSnapshot(orderId, "LIVE_PHONE", "SELF_COLLECT");
        again.put("republished", true);
        send("order.paid", orderId, again);

        assertThat(rowsOf(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("a web order with no owner is a defect, not a phone order: it is refused (to the dead-letter topic on Kafka), and nothing is remembered about it")
    void aWebOrderWithNoOwnerIsRefused() throws Exception {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> orderEvents.accept(Relayed.orderEvent("order.created", orderId, 1, objectMapper.writeValueAsBytes(snapshot(orderId, null)))))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("has no owner and is not a phone order");
        assertThat(repository.count()).isZero();
        assertThat(orderRefs.find(orderId)).isEmpty();
    }

    @Test
    @DisplayName("a web order announced with channel WEB is told as before")
    void aWebOrderWithAChannelIsToldAsBefore() throws Exception {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> payload = snapshot(orderId, "kc-alice");
        payload.put("channel", "WEB");
        send("order.paid", orderId, payload);

        assertThat(told(orderId).keycloakId()).isEqualTo("kc-alice");
    }

    @Test
    @DisplayName("order.paid with Phase 10's trailing delivery fields is told exactly as before: the shopper hears nothing about the shop's own bookkeeping")
    void thePhase10DeliveryFieldsAreIgnored() throws Exception {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> payload = snapshot(orderId, "kc-alice");
        payload.put("deliveryWaivedAmount", "150.00");
        payload.put("deliveryPriceSource", "AGREED");
        payload.put("deliveryAgreedAmount", "60.00");
        payload.put("deliveryQuotedAmount", "100.00");

        send("order.paid", orderId, payload);

        assertThat(told(orderId).body()).doesNotContain("150.00").doesNotContain("60.00").doesNotContain("AGREED");
    }

    @Test
    @DisplayName("no log line of the consumer carries a message's words")
    void noLogLineCarriesTheWords(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // The words are in the row. A log line says which event, which order and what became of it.
        UUID orderId = UUID.randomUUID();
        send("order.paid", orderId, snapshot(orderId, "kc-alice"));

        assertThat(told(orderId).body()).contains("K100.00");
        assertThat(output.getAll()).doesNotContain("K100.00").doesNotContain("We have received your payment")
                .doesNotContain("Payment received - thank you");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------------------

    private NotificationLog told(UUID orderId) {
        return repository.findAll().stream().filter(l -> orderId.equals(l.orderId())).findFirst().orElseThrow();
    }

    private NotificationLog told(UUID orderId, String eventType) {
        return repository.findAll().stream().filter(l -> orderId.equals(l.orderId()) && eventType.equals(l.eventType())).findFirst().orElseThrow();
    }

    /** A small order snapshot: K100.00, no delivery option. */
    private static Map<String, Object> snapshot(UUID orderId, String owner) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("keycloakId", owner);
        payload.put("newStatus", "CREATED");
        payload.put("total", "100.00");
        payload.put("currency", "ZMW");
        return payload;
    }

    private void sendStatus(String eventType, UUID orderId, String newStatus, String deliveryOption) throws Exception {
        Map<String, Object> payload = snapshot(orderId, "kc-bob");
        payload.put("newStatus", newStatus);
        payload.put("deliveryOption", deliveryOption);
        send(eventType, orderId, payload);
    }

    private void send(String eventType, UUID orderId, Map<String, Object> payload) throws Exception {
        inputDestination.send(Relayed.orderEvent(eventType, orderId, 1, objectMapper.writeValueAsBytes(payload)), "oriah.order");
    }

    /** The example without its layout: the same properties in the same order, every number exactly as written. */
    private static String compact(byte[] json) throws IOException {
        ObjectMapper exact = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .build();
        return exact.writeValueAsString(exact.readTree(json));
    }

    static byte[] fixture(String path) throws IOException {
        try (InputStream in = OrderEventConsumerTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }
}
