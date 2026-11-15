package com.oriah.payment.events;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.events.EventHeaders;
import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.confirmation.ConfirmationService;
import com.oriah.payment.confirmation.Staff;
import com.oriah.payment.dto.ConfirmPaymentRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment learns what became of the money from order's events (Phase 7): a shopper's CLAIM ({@code order.payment_submitted}), the order being PAID by a
 * confirmation ({@code order.paid}, version 2, which names it), and money that could not pay the sale ({@code order.payment_unsettled}), which is HELD
 * for the customer. Payment never reads an order: these events are how it knows. Each is applied once, in any order, and one it has no use for is
 * declared and ignored so a replay never stalls; one nobody declared is refused on its first delivery.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class OrderEventConsumerTest {

    private static final AtomicLong SEQ = new AtomicLong(9000);
    private static final Staff SAM = new Staff("user:kc-sam", "Sam Phiri", false);

    @Autowired
    @Qualifier("orderEvents")
    private Consumer<Message<byte[]>> orderEvents;

    @Autowired
    private InputDestination inputDestination;

    @Autowired
    private ConfirmationService confirmations;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static String reference() {
        return "MP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private UUID confirmed(UUID order, String gross) {
        return confirmations.confirm(new ConfirmPaymentRequest(order, "MTN", reference(), new BigDecimal(gross), new BigDecimal("10.00"), null, null, null, null),
                "key-" + UUID.randomUUID(), SAM).view().id();
    }

    private static Message<byte[]> message(String type, int version, UUID orderId, String payload, String aggregateType) {
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
                .setHeader(EventHeaders.SCHEMA_VERSION, Integer.toString(version))
                .setHeader(EventHeaders.PRODUCER, "order")
                .setHeader(EventHeaders.KAFKA_KEY, orderId.toString().getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private void deliver(String type, int version, UUID orderId, String payload) {
        orderEvents.accept(message(type, version, orderId, payload, "order"));
    }

    private void submitted(UUID claim, UUID order, String method, String reference) {
        deliver("order.payment_submitted", 1, order, "{\"claimId\":\"" + claim + "\",\"orderId\":\"" + order + "\",\"method\":\"" + method
                + "\",\"referenceNormalised\":\"" + reference + "\",\"submittedAt\":\"2026-11-12T09:58:00Z\",\"holdExpiresAt\":\"2026-11-13T09:58:00Z\"}");
    }

    private void paid(UUID order, UUID confirmation) {
        deliver("order.paid", 2, order, "{\"orderId\":\"" + order + "\",\"confirmationId\":\"" + confirmation + "\"}");
    }

    private void unsettled(UUID order, UUID confirmation, String reason) {
        deliver("order.payment_unsettled", 1, order, "{\"orderId\":\"" + order + "\",\"confirmationId\":\"" + confirmation + "\",\"reason\":\"" + reason + "\",\"currency\":\"ZMW\"}");
    }

    private String outcome(UUID confirmation) {
        return jdbc.queryForObject("SELECT outcome FROM payment_confirmation WHERE id = ?", String.class, confirmation);
    }

    private long heldRecords(UUID confirmation) {
        return jdbc.queryForObject("SELECT count(*) FROM held_money WHERE confirmation_id = ?", Long.class, confirmation);
    }

    private List<Map<String, Object>> events(UUID confirmation) {
        return jdbc.queryForList("SELECT event_type, actor, occurred_at, payload::text AS payload FROM outbox WHERE aggregate_type = 'payment_confirmation' "
                + "AND aggregate_id = ? ORDER BY seq", confirmation.toString());
    }

    // ---------------------------------------------------------------- a claim

    @Test
    @DisplayName("order.payment_submitted records the shopper's claim, once however often it is delivered, and a second event for the same claim adds nothing")
    void aClaim() {
        UUID claim = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        String reference = reference();

        submitted(claim, order, "MTN", reference);
        submitted(claim, order, "MTN", reference);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_claim WHERE id = ?", Long.class, claim)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT order_id, method, reference FROM payment_claim WHERE id = ?", claim))
                .containsEntry("order_id", order).containsEntry("method", "MTN").containsEntry("reference", reference);
        assertThat(jdbc.queryForObject("SELECT hold_expires_at IS NOT NULL FROM payment_claim WHERE id = ?", Boolean.class, claim)).isTrue();
    }

    @Test
    @DisplayName("a claim that cannot be one (cash, a reference with odd characters, no claim id) is a broken event: refused on its first delivery, never retried")
    void aBrokenClaim() {
        UUID order = UUID.randomUUID();

        assertThatThrownBy(() -> submitted(UUID.randomUUID(), order, "CASH", reference())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> submitted(UUID.randomUUID(), order, "MTN", "#!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deliver("order.payment_submitted", 1, order, "{\"orderId\":\"" + order + "\",\"method\":\"MTN\",\"referenceNormalised\":\"ABC123\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- the sale was paid

    @Test
    @DisplayName("order.paid (version 2) says which confirmation paid the sale: its outcome becomes SETTLED once, and a repeat or an unknown confirmation changes nothing and does not fail")
    void settled() {
        UUID order = UUID.randomUUID();
        UUID confirmation = confirmed(order, "1000.00");

        paid(order, confirmation);
        paid(order, confirmation);
        paid(order, UUID.randomUUID());

        assertThat(outcome(confirmation)).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT outcome_at IS NOT NULL FROM payment_confirmation WHERE id = ?", Boolean.class, confirmation)).isTrue();
        assertThat(heldRecords(confirmation)).isZero();
    }

    @Test
    @DisplayName("order.paid of an order staff recorded for a caller with no account (no owner, channel PHONE, the date the sale happened) settles its confirmation like any other (Phase 9)")
    void aPhoneOrderPaidSettles() {
        UUID order = UUID.randomUUID();
        UUID confirmation = confirmed(order, "1000.00");

        deliver("order.paid", 2, order, "{\"orderId\":\"" + order + "\",\"keycloakId\":null,\"previousStatus\":\"CREATED\",\"newStatus\":\"PAID\","
                + "\"confirmationId\":\"" + confirmation + "\",\"republished\":false,\"channel\":\"PHONE\",\"occurredAt\":\"2026-11-12T09:30:00.123456Z\"}");

        assertThat(outcome(confirmation)).isEqualTo("SETTLED");
    }

    @Test
    @DisplayName("order.paid in its first version names no confirmation: it is read and ignored")
    void versionOneIsIgnored() {
        UUID order = UUID.randomUUID();
        UUID confirmation = confirmed(order, "1000.00");

        deliver("order.paid", 1, order, "{\"orderId\":\"" + order + "\",\"newStatus\":\"PAID\"}");

        assertThat(outcome(confirmation)).isEqualTo("PENDING");
    }

    // ---------------------------------------------------------------- the money could not pay the sale

    @Test
    @DisplayName("order.payment_unsettled holds the money: outcome HELD with the reason, one record of what is owed, payment.held announced once however often it arrives")
    void held() {
        UUID order = UUID.randomUUID();
        UUID confirmation = confirmed(order, "1000.00");

        unsettled(order, confirmation, "WRONG_AMOUNT");
        unsettled(order, confirmation, "WRONG_AMOUNT");

        assertThat(outcome(confirmation)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT outcome_reason FROM payment_confirmation WHERE id = ?", String.class, confirmation)).isEqualTo("WRONG_AMOUNT");
        assertThat(heldRecords(confirmation)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT order_id, reason, owed_gross, currency, status FROM held_money WHERE confirmation_id = ?", confirmation))
                .containsEntry("order_id", order).containsEntry("reason", "WRONG_AMOUNT").containsEntry("currency", "ZMW").containsEntry("status", "OWED")
                .satisfies(row -> assertThat((BigDecimal) row.get("owed_gross")).isEqualByComparingTo("1000.00"));
        List<Map<String, Object>> events = events(confirmation);
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly("payment.confirmed", "payment.held");
        assertThat(events.get(1).get("actor")).isEqualTo("system:payment");
        assertThat((String) events.get(1).get("payload")).contains("\"reason\":\"WRONG_AMOUNT\"").contains("\"owedGross\":1000.00").contains("\"currency\":\"ZMW\"")
                .doesNotContain("\"fee\"");
    }

    @Test
    @DisplayName("every reason order can give is held: wrong amount, cancelled, the piece sold, already paid, an order nobody knows")
    void everyReason() {
        for (String reason : new String[] {"WRONG_AMOUNT", "ORDER_CANCELLED", "PIECE_SOLD", "ALREADY_PAID", "ORDER_UNKNOWN"}) {
            UUID confirmation = confirmed(UUID.randomUUID(), "250.00");

            unsettled(UUID.randomUUID(), confirmation, reason);

            assertThat(outcome(confirmation)).as(reason).isEqualTo("HELD");
        }
    }

    @Test
    @DisplayName("a reason payment does not know is a broken event (refused, nothing held); money payment never recorded cannot be held (logged, no failure); money that paid a sale is not held")
    void heldIsCarefulAboutWhatItHolds() {
        UUID order = UUID.randomUUID();
        UUID pending = confirmed(order, "1000.00");
        UUID settled = confirmed(order, "1000.00");
        paid(order, settled);

        assertThatThrownBy(() -> unsettled(order, pending, "BECAUSE_I_SAY_SO")).isInstanceOf(IllegalArgumentException.class);
        unsettled(order, UUID.randomUUID(), "WRONG_AMOUNT");
        unsettled(order, settled, "ALREADY_PAID");

        assertThat(outcome(pending)).isEqualTo("PENDING");
        assertThat(outcome(settled)).as("a sale stands on it").isEqualTo("SETTLED");
        assertThat(heldRecords(settled)).isZero();
    }

    @Test
    @DisplayName("a confirmation that was voided is not held: it was keyed wrongly and is no longer money to give back")
    void aVoidedConfirmationIsNotHeld() {
        UUID order = UUID.randomUUID();
        UUID confirmation = confirmed(order, "1000.00");
        confirmations.voidConfirmation(confirmation, "keyed against the wrong order", SAM);

        unsettled(order, confirmation, "ORDER_UNKNOWN");

        assertThat(heldRecords(confirmation)).isZero();
    }

    // ---------------------------------------------------------------- what is declared

    @Test
    @DisplayName("the order events payment has no use for are declared and ignored; one nobody declared, or about another kind of aggregate, is refused on its first delivery")
    void declarations() {
        UUID order = UUID.randomUUID();
        for (String type : new String[] {"order.created", "order.expired", "order.cancelled", "order.fulfilled", "order.shipped", "order.delivered"}) {
            deliver(type, 1, order, "{\"orderId\":\"" + order + "\"}");
        }

        assertThatThrownBy(() -> deliver("order.teleported", 1, order, "{}")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("order.teleported");
        assertThatThrownBy(() -> orderEvents.accept(message("order.paid", 2, order, "{}", "customer"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deliver("order.paid", 3, order, "{}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an event sent through the binder reaches the same consumer: the destination oriah.order is bound for payment")
    void throughTheBinder() {
        UUID claim = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        inputDestination.send(message("order.payment_submitted", 1, order, "{\"claimId\":\"" + claim + "\",\"orderId\":\"" + order
                + "\",\"method\":\"AIRTEL\",\"referenceNormalised\":\"" + reference() + "\",\"submittedAt\":\"" + Instant.now() + "\"}", "order"), "oriah.order");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_claim WHERE id = ?", Long.class, claim)).isEqualTo(1);
    }
}
