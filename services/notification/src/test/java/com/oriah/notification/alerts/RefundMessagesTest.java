package com.oriah.notification.alerts;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import com.oriah.events.EventHeaders;
import com.oriah.notification.MutableClock;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.TestData;
import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.EmailSetup.Mode;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.contact.ContactBook;
import com.oriah.notification.delivery.DispatchHealth;
import com.oriah.notification.delivery.Email;
import com.oriah.notification.delivery.MessageDispatcher;
import com.oriah.notification.delivery.MessageStore;
import com.oriah.notification.delivery.RecordingTransport;
import com.oriah.notification.events.Relayed;
import com.oriah.notification.orders.OrderRefs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Returns, refunds and cancellations of paid orders (Phase 12), as notification hears them from two topics.
 *
 * <p>A customer is told that a paid order was cancelled before it left, that a return was received, and that a refund was paid, each with the
 * amount from the event that caused the message. Staff who look after money are told when a refund has been owed for days and is not recorded as
 * paid; that alert ends when it is paid out or when the owner records that the money never came, opens again if the payout is cancelled, and
 * never closes by the clock while the debt stands.
 *
 * <p>The story here: order OR-000123, two rings, K180.00 for the pieces and K45.00 for delivery, K225.00 paid.
 */
@ServiceTest
class RefundMessagesTest {

    private static final List<String> MONEY = List.of("alerts-money@oriah.local", "alerts-owner@oriah.local");

    @Autowired
    @Qualifier("paymentEvents")
    private Consumer<Message<byte[]>> paymentEvents;
    @Autowired
    @Qualifier("orderEvents")
    private Consumer<Message<byte[]>> orderEvents;
    @Autowired
    private ConfirmationWatch watch;
    @Autowired
    private MessageStore store;
    @Autowired
    private ContactBook contacts;
    @Autowired
    private OrderRefs orderRefs;
    @Autowired
    private DispatchHealth health;
    @Autowired
    private NotificationSettings settings;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private final RecordingTransport transport = new RecordingTransport();
    private final UUID orderId = UUID.randomUUID();
    private final UUID confirmationId = UUID.randomUUID();
    private final UUID correctionId = UUID.randomUUID();
    private final UUID payoutId = UUID.randomUUID();
    private long seq;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
        jdbc.update("DELETE FROM refund_watch_payout");
        jdbc.update("DELETE FROM refund_watch");
        jdbc.update("""
                INSERT INTO recipient_contact (keycloak_id, email, email_verified, token_issued_at, first_seen_at, seen_at)
                VALUES ('kc-alice', 'alice@oriah.local', true, now(), now(), now())""");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------------------

    private MessageDispatcher dispatcher() {
        EmailSetup setup = new EmailSetup(Mode.CATCHER, null, MONEY, List.of("alerts-cost@oriah.local"), null, false);
        StaffAlerts alerts = new StaffAlerts(jdbc, transactions, setup, settings);
        return new MessageDispatcher(store, contacts, orderRefs, health, alerts, watch, transport, setup, settings, clock);
    }

    private String justNow() {
        return clock.instant().minusSeconds(1).toString();
    }

    private void order(String type, String body, String version) {
        orderEvents.accept(Relayed.orderEvent(UUID.randomUUID(), type, orderId, ++seq, body.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.RECORDED_AT, justNow()).setHeader(EventHeaders.SCHEMA_VERSION, version).build());
    }

    private void orderPaid() {
        order("order.paid", "{\"orderId\":\"" + orderId + "\",\"keycloakId\":\"kc-alice\",\"previousStatus\":\"PAYMENT_SUBMITTED\",\"newStatus\":\"PAID\","
                + "\"total\":225.00,\"currency\":\"ZMW\",\"confirmationId\":\"" + confirmationId + "\",\"receiptNumber\":\"OR-000123\"}", "2");
    }

    /** A sale correction as order announces it. {@code owner} is the customer's login, or null for a phone order. */
    private String correction(String kind, String reason, String goods, String discounts, String delivery, String owed, int pieces, String bearer, String owner) {
        return "{\"correctionId\":\"" + correctionId + "\",\"orderId\":\"" + orderId + "\",\"kind\":\"" + kind + "\",\"reasonCode\":\"" + reason + "\","
                + "\"statusBefore\":\"PAID\",\"statusAfter\":\"" + ("CANCELLATION".equals(kind) ? "CANCELLED" : "DELIVERED") + "\","
                + "\"confirmationId\":\"" + confirmationId + "\",\"receiptNumber\":\"OR-000123\",\"saleArrivedAt\":\"2026-11-03T08:00:00Z\","
                + "\"occurredAt\":\"2026-11-09T07:30:00Z\",\"recordedAt\":\"2026-11-09T07:30:00Z\",\"currency\":\"ZMW\","
                + "\"lines\":[{\"lineId\":\"" + UUID.randomUUID() + "\",\"variantId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + UUID.randomUUID()
                + "\",\"quantity\":" + pieces + ",\"unitPrice\":100.00,\"discountPerPiece\":10.00,\"promotionId\":null,\"lineRefund\":" + owed + "}],"
                + "\"goodsAtList\":" + goods + ",\"discounts\":" + discounts + ",\"deliveryRefunded\":" + delivery + ",\"waiverTakenBack\":0.00,"
                + "\"owedGross\":" + owed + ",\"feeBearer\":\"" + bearer + "\",\"deliveryOption\":\"OWN_DELIVERY\",\"deliveryPayer\":\"ORIAH_COLLECTS\","
                + "\"keycloakId\":" + (owner == null ? "null,\"channel\":\"PHONE\",\"saleKind\":\"LIVE_PHONE\"" : "\"" + owner + "\"") + "}";
    }

    private void cancelled() {
        order("order.sale_cancelled", correction("CANCELLATION", "CUSTOMER_ASKED", "200.00", "20.00", "45.00", "225.00", 2, "CUSTOMER", "kc-alice"), "1");
    }

    private void returned() {
        order("order.return_recorded", correction("RETURN", "CHANGED_MIND", "100.00", "10.00", "0.00", "90.00", 1, "CUSTOMER", "kc-alice"), "1");
    }

    private Message<byte[]> paymentFact(String type, String aggregateType, UUID aggregateId, String body) {
        return MessageBuilder.withPayload(body.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.EVENT_ID, UUID.randomUUID().toString()).setHeader(EventHeaders.EVENT_TYPE, type)
                .setHeader(EventHeaders.AGGREGATE_TYPE, aggregateType).setHeader(EventHeaders.AGGREGATE_ID, aggregateId.toString())
                .setHeader(EventHeaders.SEQ, "1").setHeader(EventHeaders.OUTBOX_ID, Long.toString(++seq))
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-09T07:30:00Z").setHeader(EventHeaders.RECORDED_AT, justNow())
                .setHeader(EventHeaders.ACTOR, "user:kc-olive").setHeader(EventHeaders.SCHEMA_VERSION, "1").setHeader(EventHeaders.PRODUCER, "payment")
                .setHeader(EventHeaders.KAFKA_KEY, aggregateId.toString().getBytes(StandardCharsets.UTF_8)).build();
    }

    private String payout(String purpose, UUID debt, String gross, String fee) {
        java.math.BigDecimal net = new java.math.BigDecimal(gross).subtract(new java.math.BigDecimal(fee));
        return "{\"payoutId\":\"" + payoutId + "\",\"purpose\":\"" + purpose + "\",\"debtId\":\"" + debt + "\",\"cause\":\"CANCELLATION\",\"orderId\":\"" + orderId
                + "\",\"confirmationId\":\"" + confirmationId + "\",\"method\":\"MTN\",\"gross\":" + gross + ",\"customerFee\":" + fee + ",\"net\":" + net
                + ",\"sendCharge\":" + fee + ",\"feeBearer\":\"CUSTOMER\",\"currency\":\"ZMW\",\"paidAt\":\"2026-11-09T07:30:00Z\","
                + "\"recordedAt\":\"2026-11-09T07:30:00Z\",\"reference\":\"MP2411REFUND\",\"paidBy\":\"user:kc-olive\"}";
    }

    private void paidOut(String gross, String fee) {
        paymentEvents.accept(paymentFact("payment.paid_out", "money_out", payoutId, payout("REFUND", correctionId, gross, fee)));
    }

    private void payoutVoided() {
        paymentEvents.accept(paymentFact("payment.payout_voided", "money_out", payoutId, "{\"payoutId\":\"" + payoutId + "\",\"purpose\":\"REFUND\",\"debtId\":\""
                + correctionId + "\",\"orderId\":\"" + orderId + "\",\"confirmationId\":\"" + confirmationId + "\",\"method\":\"MTN\",\"gross\":225.00,"
                + "\"customerFee\":5.00,\"net\":220.00,\"sendCharge\":5.00,\"currency\":\"ZMW\",\"paidAt\":\"2026-11-09T07:30:00Z\","
                + "\"voidedAt\":\"2026-11-09T07:30:00Z\"}"));
    }

    private void refundClosed() {
        paymentEvents.accept(paymentFact("payment.refund_closed", "refund_due", correctionId, "{\"dueId\":\"" + correctionId + "\",\"orderId\":\"" + orderId
                + "\",\"confirmationId\":\"" + confirmationId + "\",\"owedGross\":225.00,\"currency\":\"ZMW\",\"outcome\":\"MONEY_NEVER_ARRIVED\","
                + "\"closedAt\":\"2026-11-09T07:30:00Z\",\"recordedAt\":\"2026-11-09T07:30:00Z\"}"));
    }

    private List<Map<String, Object>> shopperRows() {
        return jdbc.queryForList("SELECT * FROM notification_log WHERE recipient_kind = 'SHOPPER' AND order_id = ? ORDER BY recorded_at, id", orderId);
    }

    private Map<String, Object> shopperRow(String type) {
        return jdbc.queryForMap("SELECT * FROM notification_log WHERE recipient_kind = 'SHOPPER' AND order_id = ? AND event_type = ?", orderId, type);
    }

    private List<Map<String, Object>> alerts(String kind) {
        return jdbc.queryForList("SELECT * FROM staff_alert WHERE kind = ? ORDER BY raised_at", kind);
    }

    private Map<String, Object> refundAlert() {
        return alerts("REFUND_TO_PAY").getFirst();
    }

    private List<Email> emailsTo(String address) {
        return transport.handedOver().stream().filter(email -> email.to().equals(address)).toList();
    }

    // ---- what the customer is told ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a paid order cancelled before it left: the customer is told what will be paid back, K225.00 as K180.00 for the pieces and K45.00 for delivery, and that the sending charge comes off it")
    void aPaidCancellationIsToldToTheShopper() {
        orderPaid();

        cancelled();

        Map<String, Object> row = shopperRow("order.sale_cancelled");
        assertThat(row).containsEntry("subject", "Order OR-000123 has been cancelled").containsEntry("keycloak_id", "kc-alice").containsEntry("status", "RECORDED");
        assertThat((String) row.get("body")).isEqualTo("Order OR-000123 has been cancelled before it left us. We will pay you back K225.00: K180.00 for your pieces "
                + "and K45.00 for delivery. The charge for sending the money is taken from this amount. We will be in touch about how you would like to be paid.");
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM order_ref WHERE order_id = ?", Timestamp.class, orderId)).as("the order is remembered as cancelled").isNotNull();

        // One message of an order goes out per pass: "payment received" first, then the cancellation.
        dispatcher().run();
        dispatcher().run();
        assertThat(emailsTo("alice@oriah.local")).extracting(Email::subject).containsExactly("Payment received - thank you", "Order OR-000123 has been cancelled");
    }

    @Test
    @DisplayName("a return received: the customer is told how many pieces came back and what will be paid back; delivery is not")
    void aReturnIsToldToTheShopper() {
        orderPaid();

        returned();

        Map<String, Object> row = shopperRow("order.return_recorded");
        assertThat(row).containsEntry("subject", "We have received your return for order OR-000123");
        assertThat((String) row.get("body")).isEqualTo("We have received 1 piece back from order OR-000123. We will pay you back K90.00. Delivery is not paid back. "
                + "The charge for sending the money is taken from this amount.");
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM order_ref WHERE order_id = ?", Timestamp.class, orderId)).as("a return cancels nothing").isNull();
    }

    @Test
    @DisplayName("when the money record of the sale had been cancelled, the cancellation promises no amount: nobody knows yet whether the money arrived")
    void aCancelledMoneyRecordPromisesNoAmount() {
        orderPaid();
        paymentEvents.accept(Relayed.paymentEvent(UUID.randomUUID(), "payment.voided", confirmationId, ++seq, ("{\"confirmationId\":\"" + confirmationId
                + "\",\"orderId\":\"" + orderId + "\",\"method\":\"MTN\",\"gross\":225.00,\"currency\":\"ZMW\",\"outcome\":\"PENDING\"}").getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.RECORDED_AT, justNow()).build());
        assertThat(alerts("VOID_AFTER_SALE")).singleElement().satisfies(alert -> assertThat(alert).containsEntry("state", "OPEN"));

        cancelled();

        assertThat((String) shopperRow("order.sale_cancelled").get("body"))
                .isEqualTo("Order OR-000123 has been cancelled. We will contact you about any money you sent us.").doesNotContain("K225");
        assertThat(alerts("VOID_AFTER_SALE")).as("the sale that money paid has been corrected: the alert has its answer").singleElement()
                .satisfies(alert -> assertThat(alert).containsEntry("state", "RESOLVED").containsEntry("resolved_how", "SALE_CORRECTED"));
    }

    @Test
    @DisplayName("a refund paid is told to the customer with what they received and why it is less; it never goes out before the cancellation's own message")
    void refundPaidWaitsBehindTheCancellation() {
        orderPaid();
        dispatcher().run();
        cancelled();

        paidOut("225.00", "5.00");

        Map<String, Object> paid = shopperRow("payment.paid_out");
        assertThat(paid).containsEntry("subject", "Your refund for order OR-000123 has been paid").containsEntry("event_seq", null);
        assertThat((String) paid.get("body")).isEqualTo("We have paid back K220.00 for order OR-000123: K225.00 less K5.00 for the charge of sending it.");
        assertThat(paid.get("after_message_id")).as("it names the message it waits behind").isEqualTo(shopperRow("order.sale_cancelled").get("id"));

        // The cancellation's message cannot go yet (its next try is later): the refund's message waits behind it and is not handed over alone.
        jdbc.update("UPDATE notification_log SET next_attempt_at = ? WHERE id = ?", Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))),
                shopperRow("order.sale_cancelled").get("id"));
        dispatcher().run();
        assertThat(emailsTo("alice@oriah.local")).extracting(Email::subject).doesNotContain("Your refund for order OR-000123 has been paid");

        clock.advance(Duration.ofMinutes(11));
        dispatcher().run();
        dispatcher().run();
        assertThat(emailsTo("alice@oriah.local")).extracting(Email::subject)
                .containsSubsequence("Order OR-000123 has been cancelled", "Your refund for order OR-000123 has been paid");
    }

    @Test
    @DisplayName("a payout that is cancelled before its message went out is never emailed as paid: the waiting message is withdrawn (superseded), and the cancellation's own message still goes")
    void aPayoutCancelledBeforeItsMessageWentIsNeverEmailed() {
        orderPaid();
        dispatcher().run();
        cancelled();
        // The cancellation's message cannot go yet, so the refund's message waits behind it.
        jdbc.update("UPDATE notification_log SET next_attempt_at = ? WHERE id = ?", Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))),
                shopperRow("order.sale_cancelled").get("id"));
        paidOut("225.00", "5.00");
        assertThat(shopperRow("payment.paid_out")).containsEntry("status", "RECORDED");

        // The owner keyed the payout by mistake and cancels it: the money has NOT been paid back.
        payoutVoided();

        assertThat(shopperRow("payment.paid_out")).containsEntry("status", "NOT_SENT").containsEntry("status_reason", "SUPERSEDED");
        assertThat(alerts("REFUND_TO_PAY").getLast()).as("and the refund is owed again: a new alert stands open").containsEntry("state", "OPEN");
        clock.advance(Duration.ofMinutes(11));
        dispatcher().run();
        dispatcher().run();
        assertThat(emailsTo("alice@oriah.local")).extracting(Email::subject)
                .contains("Order OR-000123 has been cancelled").doesNotContain("Your refund for order OR-000123 has been paid");
    }

    @Test
    @DisplayName("a cancelled money record heard AFTER its sale was already corrected raises no 'a cancelled record paid a sale' alert: the order system has dealt with it")
    void aVoidHeardAfterTheCorrectionRaisesNothing() {
        orderPaid();
        cancelled();

        paymentEvents.accept(paymentFact("payment.voided", "payment_confirmation", confirmationId, "{\"confirmationId\":\"" + confirmationId + "\",\"orderId\":\""
                + orderId + "\",\"method\":\"MTN\",\"reference\":\"MP2411\",\"gross\":225.00,\"fee\":3.40,\"net\":221.60,\"currency\":\"ZMW\",\"outcome\":\"SETTLED\","
                + "\"reason\":\"keyed against the wrong order\",\"voidedBy\":\"Sam Phiri\",\"voidedAt\":\"" + justNow() + "\",\"arrivedAt\":\"2026-11-03T08:00:00Z\"}"));

        assertThat(alerts("VOID_AFTER_SALE")).as("nothing is left open for staff to chase").noneMatch(alert -> "OPEN".equals(alert.get("state")));

        // The sale's payment announced once more (order answers a repeated confirmation with the same order.paid): still nothing.
        orderPaid();
        assertThat(alerts("VOID_AFTER_SALE")).as("a repeated order.paid after the correction").noneMatch(alert -> "OPEN".equals(alert.get("state")));
    }

    @Test
    @DisplayName("a 'refund paid' message that may already have reached the customer when its payout is cancelled is never recorded as 'not sent': it ends as outcome unknown")
    void aMessageThatMayHaveGoneIsNotCalledUnsent() {
        orderPaid();
        dispatcher().run();
        cancelled();
        paidOut("225.00", "5.00");
        // A try that got no answer from the mail server: the message may have been handed over.
        jdbc.update("UPDATE notification_log SET maybe_handed_over = TRUE, attempts = 1 WHERE id = ?", shopperRow("payment.paid_out").get("id"));

        payoutVoided();

        assertThat(shopperRow("payment.paid_out")).containsEntry("status", "FAILED").containsEntry("status_reason", "OUTCOME_UNKNOWN");
    }
    @Test
    @DisplayName("a payout that reaches notification before the cancellation writes nothing until the cancellation is known; a refund paid in full says so in one sentence")
    void aPayoutThatArrivesFirstWritesNothingUntilTheCorrectionIsKnown() {
        orderPaid();

        paidOut("225.00", "0.00");
        assertThat(shopperRows()).extracting(row -> row.get("event_type")).doesNotContain("payment.paid_out");

        cancelled();

        assertThat((String) shopperRow("payment.paid_out").get("body")).isEqualTo("We have paid back K225.00 for order OR-000123.");
        assertThat(shopperRow("payment.paid_out").get("after_message_id")).isEqualTo(shopperRow("order.sale_cancelled").get("id"));
        // Told twice, written once.
        paidOut("225.00", "0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_log WHERE order_id = ? AND event_type = 'payment.paid_out'", Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("held money returned tells the customer nothing (the order on that money was typed by a person), and a phone order's caller gets no words for a cancellation")
    void whoIsNotTold() {
        orderPaid();
        UUID heldPayout = UUID.randomUUID();
        paymentEvents.accept(paymentFact("payment.paid_out", "money_out", heldPayout,
                payout("HELD_MONEY", confirmationId, "85.00", "2.00").replace(payoutId.toString(), heldPayout.toString())));
        assertThat(shopperRows()).extracting(row -> row.get("event_type")).containsExactly("order.paid");
        assertThat(alerts("REFUND_TO_PAY")).isEmpty();

        // A phone order: applied, never dead-lettered, and nothing is written for anybody to send.
        UUID phoneOrder = UUID.randomUUID();
        String body = correction("CANCELLATION", "CUSTOMER_ASKED", "200.00", "20.00", "45.00", "225.00", 2, "CUSTOMER", null).replace(orderId.toString(), phoneOrder.toString())
                .replace(correctionId.toString(), UUID.randomUUID().toString());
        orderEvents.accept(Relayed.orderEvent(UUID.randomUUID(), "order.sale_cancelled", phoneOrder, 1, body.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.RECORDED_AT, justNow()).build());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_log WHERE order_id = ? AND recipient_kind <> 'STAFF'", Integer.class, phoneOrder)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert WHERE kind = 'REFUND_TO_PAY' AND order_id = ?", Integer.class, phoneOrder))
                .as("the debt is still a debt: staff are alerted").isEqualTo(1);
    }

    @Test
    @DisplayName("a correction whose body is about another order, or a web order's with no owner, is refused: nobody is told about somebody else's order")
    void whatIsRefused() {
        String other = correction("CANCELLATION", "CUSTOMER_ASKED", "200.00", "20.00", "45.00", "225.00", 2, "CUSTOMER", "kc-alice")
                .replace(orderId.toString(), UUID.randomUUID().toString());
        assertThatThrownBy(() -> order("order.sale_cancelled", other, "1")).isInstanceOf(IllegalArgumentException.class);
        String ownerless = correction("CANCELLATION", "CUSTOMER_ASKED", "200.00", "20.00", "45.00", "225.00", 2, "CUSTOMER", "kc-alice")
                .replace("\"keycloakId\":\"kc-alice\"", "\"keycloakId\":null");
        assertThatThrownBy(() -> order("order.sale_cancelled", ownerless, "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paymentEvents.accept(paymentFact("payment.paid_out", "money_out", UUID.randomUUID(), payout("REFUND", correctionId, "225.00", "5.00"))))
                .as("a payout's body names another payout than its envelope").isInstanceOf(IllegalArgumentException.class);
        assertThat(shopperRows()).isEmpty();
        assertThat(alerts("REFUND_TO_PAY")).isEmpty();
        // What payment says is owed is declared and ignored: the order's own fact is what notification acts on.
        paymentEvents.accept(paymentFact("payment.refund_due_recorded", "refund_due", correctionId, "{\"dueId\":\"" + correctionId + "\"}"));
        assertThat(alerts("REFUND_TO_PAY")).isEmpty();
    }

    // ---- the alert: a refund is waiting to be paid ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a refund owed is listed at once and emailed after three days, then every week, in words that say how much, for which order and since when")
    void listedAtOnceEmailedAfterThreeDays() {
        orderPaid();
        cancelled();

        Map<String, Object> alert = refundAlert();
        assertThat(alert).containsEntry("state", "OPEN").containsEntry("audience", "MONEY").containsEntry("subject_id", correctionId.toString())
                .containsEntry("order_id", orderId).containsEntry("closes_at", null);
        assertThat((String) alert.get("summary")).isEqualTo("A refund is waiting to be paid - order OR-000123");
        assertThat((String) alert.get("detail")).isEqualTo("A refund of K225.00 for order OR-000123 has been owed since 9 November and is not recorded as paid. "
                + "The owner pays it under Payments, Refunds owed.");
        dispatcher().run();
        assertThat(emailsTo(MONEY.get(0))).as("listed, not emailed yet").isEmpty();

        clock.advance(Duration.ofDays(3).plusMinutes(1));
        dispatcher().run();
        dispatcher().run();
        assertThat(emailsTo(MONEY.get(0))).extracting(Email::subject).containsExactly("A refund is waiting to be paid - order OR-000123");
        assertThat(emailsTo(MONEY.get(1))).hasSize(1);

        clock.advance(Duration.ofDays(7));
        dispatcher().run();
        dispatcher().run();
        assertThat(emailsTo(MONEY.get(0))).as("and again a week later, while it is still owed").hasSize(2);
        assertThat(refundAlert()).containsEntry("state", "OPEN");
    }

    @Test
    @DisplayName("it ends when the refund is paid out, opens again if that payout is cancelled, and ends when the owner records that the money never came")
    void endsWhenPaidOutAndReopensWhenThePayoutIsVoided() {
        orderPaid();
        cancelled();

        paidOut("225.00", "5.00");
        assertThat(alerts("REFUND_TO_PAY")).singleElement().satisfies(alert -> assertThat(alert).containsEntry("state", "RESOLVED").containsEntry("resolved_how", "PAID_OUT"));

        payoutVoided();
        assertThat(alerts("REFUND_TO_PAY")).hasSize(2).last().satisfies(alert -> assertThat(alert).containsEntry("state", "OPEN"));

        refundClosed();
        assertThat(alerts("REFUND_TO_PAY")).last().satisfies(alert -> assertThat(alert).containsEntry("state", "RESOLVED").containsEntry("resolved_how", "MONEY_NEVER_CAME"));
        // After it has ended, three days later nobody is emailed about it.
        clock.advance(Duration.ofDays(4));
        dispatcher().run();
        dispatcher().run();
        assertThat(emailsTo(MONEY.get(0))).isEmpty();
    }

    @Test
    @DisplayName("a payout, or a close, that notification hears of before the debt never leaves an alert open")
    void aPayoutBeforeTheDebtNeverLeavesItOpen() {
        orderPaid();
        paidOut("225.00", "5.00");
        cancelled();
        assertThat(alerts("REFUND_TO_PAY")).as("paid before notification even heard it was owed").isEmpty();

        // And a payout cancelled after all opens it.
        payoutVoided();
        assertThat(alerts("REFUND_TO_PAY")).singleElement().satisfies(alert -> assertThat(alert).containsEntry("state", "OPEN"));
    }

    @Test
    @DisplayName("it never closes by the clock while the debt stands, and a correction that owes nothing raises nothing")
    void neverClosesByTheClockWhileOwed() {
        orderPaid();
        cancelled();

        clock.advance(Duration.ofDays(60));
        dispatcher().run();

        assertThat(refundAlert()).containsEntry("state", "OPEN");

        UUID free = UUID.randomUUID();
        String nothing = correction("CANCELLATION", "CUSTOMER_ASKED", "0.00", "0.00", "0.00", "0.00", 1, "CUSTOMER", "kc-alice").replace(correctionId.toString(), free.toString());
        order("order.sale_cancelled", nothing, "1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert WHERE subject_id = ?", Integer.class, free.toString())).isZero();
    }
}
