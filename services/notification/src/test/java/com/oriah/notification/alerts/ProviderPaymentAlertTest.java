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

import com.oriah.events.EventHeaders;
import com.oriah.notification.MutableClock;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.TestData;
import com.oriah.notification.text.MessageTexts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A payment through ZynlePay that ZynlePay did not settle (Phase 13): the people who look after money are told, at once, because the customer may
 * have paid and nobody else will find out. The alert says what to do and what NOT to do (do not ask the customer to pay again, do not confirm it
 * by hand), and it ends when ZynlePay answers or the owner decides. It never carries the payer's number or a reference: payment sends neither.
 *
 * <p>The story here: an MTN mobile-money payment of K180.00 for one order.
 */
@ServiceTest
class ProviderPaymentAlertTest {

    @Autowired
    @Qualifier("paymentEvents")
    private Consumer<Message<byte[]>> paymentEvents;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private final UUID orderId = UUID.randomUUID();
    private final UUID attempt = UUID.randomUUID();
    private long seq;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
    }

    private Message<byte[]> update(UUID envelopeId, UUID bodyId, String method, String status, String code, Instant recordedAt) {
        boolean card = "CARD".equals(method);
        String body = "{\"providerPaymentId\":\"" + bodyId + "\",\"orderId\":\"" + orderId + "\",\"keycloakId\":\"kc-alice\",\"channel\":\""
                + (card ? "CARD" : "MOBILE_MONEY") + "\",\"method\":\"" + method + "\",\"status\":\"" + status + "\""
                + ("UNRESOLVED".equals(status) ? ",\"unresolvedReason\":\"" + code + "\"" : "") + ("FAILED".equals(status) ? ",\"failureCode\":\"" + code + "\"" : "")
                + ",\"amount\":180.00,\"currency\":\"ZMW\",\"startedAt\":\"2026-11-17T08:00:00Z\",\"at\":\"2026-11-17T08:30:00Z\"}";
        return MessageBuilder.withPayload(body.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.EVENT_ID, UUID.randomUUID().toString()).setHeader(EventHeaders.EVENT_TYPE, "payment.provider_payment_updated")
                .setHeader(EventHeaders.AGGREGATE_TYPE, "provider_payment").setHeader(EventHeaders.AGGREGATE_ID, envelopeId.toString())
                .setHeader(EventHeaders.SEQ, Long.toString(++seq)).setHeader(EventHeaders.OUTBOX_ID, Long.toString(seq))
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-17T08:30:00Z").setHeader(EventHeaders.RECORDED_AT, recordedAt.toString())
                .setHeader(EventHeaders.ACTOR, "system:zynlepay").setHeader(EventHeaders.SCHEMA_VERSION, "1").setHeader(EventHeaders.PRODUCER, "payment")
                .setHeader(EventHeaders.KAFKA_KEY, envelopeId.toString().getBytes(StandardCharsets.UTF_8)).build();
    }

    private void said(String method, String status, String code) {
        paymentEvents.accept(update(attempt, attempt, method, status, code, clock.instant().minusSeconds(1)));
    }

    private List<Map<String, Object>> alerts() {
        return jdbc.queryForList("SELECT * FROM staff_alert WHERE kind = 'PROVIDER_PAYMENT_UNRESOLVED' ORDER BY raised_at, id");
    }

    private static Instant at(Object timestamp) {
        return ((Timestamp) timestamp).toInstant();
    }

    @Test
    @DisplayName("ZynlePay has not answered about an MTN payment of K180.00: the money mailboxes are told at once what it is, what to do, and what not to do")
    void anUnresolvedPaymentAlertsTheMoneyMailbox() {
        said("MTN", "UNRESOLVED", "NO_ANSWER");

        assertThat(alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.get("state")).isEqualTo("OPEN");
            assertThat(alert.get("subject_id")).isEqualTo(attempt.toString());
            assertThat(alert.get("order_id")).isEqualTo(orderId);
            assertThat(alert.get("audience")).isEqualTo("MONEY");
            String code = orderId.toString().substring(0, 8).toUpperCase();
            assertThat((String) alert.get("summary")).isEqualTo("A ZynlePay payment has not been settled - order " + code);
            assertThat((String) alert.get("detail")).contains("MTN mobile money payment of K180.00").contains("order " + code)
                    .contains("ZynlePay has not said whether it was paid")
                    .contains("Do not ask the customer to pay again").contains("do not confirm it by hand")
                    .contains("Check now").contains("the owner can mark it paid or not paid")
                    // Nothing of the customer's that payment did not send, and nothing internal.
                    .doesNotContain("+260").doesNotContain("OZ").doesNotContain(attempt.toString());
            assertThat(at(alert.get("remind_at"))).as("told again a day later if still open").isEqualTo(at(alert.get("raised_at")).plus(Duration.ofDays(1)));
            assertThat((String) alert.get("remind_body")).contains("is still not settled");
            assertThat(at(alert.get("closes_at"))).isEqualTo(at(alert.get("raised_at")).plus(Duration.ofDays(14)));
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_log WHERE event_type = 'alert.provider_payment_unresolved' AND alert_id = ?",
                Long.class, alerts().getFirst().get("id"))).as("an email for each money mailbox").isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_log WHERE recipient_kind = 'SHOPPER'", Long.class))
                .as("the customer is told on the page they are paying on, not by a message").isZero();
    }

    @Test
    @DisplayName("the alert says why the payment was not settled, in a sentence for each reason, and a card payment is called a card payment")
    void theReasonIsSaidInWords() {
        Map<String, String> sentences = Map.of(
                "AMOUNT_DIFFERS", "ZynlePay says it was paid, but for a different amount",
                "PAID_AFTER_FAILED", "ZynlePay first said it had failed and now says it was paid",
                "SAME_MONEY_ON_RECORD", "the same money is already recorded as a payment keyed by hand",
                "CONFIRMATION_VOIDED", "its payment record was cancelled");
        sentences.forEach((reason, sentence) -> {
            TestData.clean(jdbc, clock);
            said("CARD", "UNRESOLVED", reason);
            assertThat((String) alerts().getFirst().get("detail")).as(reason).contains(sentence).contains("card payment of K180.00");
        });
        assertThat(MessageTexts.methodWords("CARD")).isEqualTo("card");
    }

    @Test
    @DisplayName("one open alert per payment; it ends when ZynlePay answers or the owner decides, and opens again if the payment comes back to the owner")
    void itEndsWhenThePaymentIsSettled() {
        said("MTN", "UNRESOLVED", "NO_ANSWER");
        said("MTN", "UNRESOLVED", "AMOUNT_DIFFERS");
        assertThat(alerts()).hasSize(1);

        said("MTN", "VERIFIED", null);
        assertThat(alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.get("state")).isEqualTo("RESOLVED");
            assertThat(alert.get("resolved_how")).isEqualTo("PAYMENT_SETTLED");
        });

        // The owner cancels its record: it is the owner's again, and the alert is raised anew.
        said("MTN", "UNRESOLVED", "CONFIRMATION_VOIDED");
        assertThat(alerts()).hasSize(2).filteredOn(alert -> "OPEN".equals(alert.get("state"))).hasSize(1);
        said("MTN", "FAILED", "MARKED_NOT_PAID");
        assertThat(alerts()).hasSize(2).allSatisfy(alert -> assertThat(alert.get("resolved_how")).isEqualTo("PAYMENT_SETTLED"));
    }

    @Test
    @DisplayName("history read late raises nothing; an ending with no alert open does nothing; a message about another payment than its envelope names is refused")
    void whatRaisesNothing() {
        paymentEvents.accept(update(attempt, attempt, "MTN", "UNRESOLVED", "NO_ANSWER", clock.instant().minus(Duration.ofDays(30))));
        said("MTN", "FAILED", "DECLINED");
        said("MTN", "VERIFIED", null);
        assertThat(alerts()).isEmpty();

        assertThatThrownBy(() -> paymentEvents.accept(update(attempt, UUID.randomUUID(), "MTN", "UNRESOLVED", "NO_ANSWER", clock.instant())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(alerts()).isEmpty();
    }
}
