package com.oriah.payment.refund;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.events.EventHeaders;
import com.oriah.events.EventJson;
import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.confirmation.ConfirmationService;
import com.oriah.payment.confirmation.Staff;
import com.oriah.payment.dto.ConfirmPaymentRequest;
import com.oriah.payment.events.PaidOutEvent;
import com.oriah.payment.events.PayoutVoidedEvent;
import com.oriah.payment.events.RefundClosedEvent;
import com.oriah.payment.events.RefundDueRecordedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What payment owes a customer after a sale was corrected (Phase 12). Order announces the correction (a paid order cancelled before it left, or
 * pieces that came back) and what is owed; payment keeps ONE debt per correction, under the correction's own id, and announces it. The debt is
 * the only thing a refund can be paid against, so nobody types an amount to pay back.
 *
 * <p>A debt is OWED, unless the money record that paid the sale was cancelled (voided) after it paid: then nobody knows whether the money ever
 * arrived, and the debt is IN_QUESTION until the owner says.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class RefundDueTest {

    private static final AtomicLong SEQ = new AtomicLong(40_000);
    private static final Staff SAM = new Staff("user:kc-sam", "Sam Phiri", false);

    @Autowired
    @Qualifier("orderEvents")
    private Consumer<Message<byte[]>> orderEvents;
    @Autowired
    private ConfirmationService confirmations;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ---------------------------------------------------------------- fixtures

    private static String reference() {
        return "MP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    /** Money confirmed for an order and then answered by the order as having paid it. */
    private UUID paidSale(UUID order, String gross) {
        UUID confirmation = confirmed(order, gross);
        paid(order, confirmation);
        return confirmation;
    }

    private UUID confirmed(UUID order, String gross) {
        return confirmations.confirm(new ConfirmPaymentRequest(order, "MTN", reference(), new BigDecimal(gross), new BigDecimal("3.40"), null, null, null, null),
                "key-" + UUID.randomUUID(), SAM).view().id();
    }

    private void deliver(String type, int version, UUID orderId, String payload) {
        orderEvents.accept(MessageBuilder.withPayload(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.EVENT_ID, UUID.randomUUID().toString()).setHeader(EventHeaders.EVENT_TYPE, type)
                .setHeader(EventHeaders.AGGREGATE_TYPE, "order").setHeader(EventHeaders.AGGREGATE_ID, orderId.toString())
                .setHeader(EventHeaders.SEQ, Long.toString(SEQ.incrementAndGet())).setHeader(EventHeaders.OUTBOX_ID, Long.toString(SEQ.get()))
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-05T09:15:00Z").setHeader(EventHeaders.RECORDED_AT, "2026-11-05T09:15:00.125Z")
                .setHeader(EventHeaders.ACTOR, "user:kc-olive").setHeader(EventHeaders.SCHEMA_VERSION, Integer.toString(version))
                .setHeader(EventHeaders.PRODUCER, "order").setHeader(EventHeaders.KAFKA_KEY, orderId.toString().getBytes(StandardCharsets.UTF_8)).build());
    }

    private void paid(UUID order, UUID confirmation) {
        deliver("order.paid", 2, order, "{\"orderId\":\"" + order + "\",\"confirmationId\":\"" + confirmation + "\"}");
    }

    /** A correction as order announces it; only what payment reads matters here. */
    private void corrected(String kind, UUID correction, UUID order, UUID confirmation, String owed, String bearer) {
        deliver("CANCELLATION".equals(kind) ? "order.sale_cancelled" : "order.return_recorded", 1, order, "{\"correctionId\":\"" + correction + "\",\"orderId\":\""
                + order + "\",\"kind\":\"" + kind + "\",\"reasonCode\":\"CUSTOMER_ASKED\",\"confirmationId\":\"" + confirmation + "\",\"receiptNumber\":\"OR-000123\","
                + "\"saleArrivedAt\":\"2026-11-03T08:00:00Z\",\"occurredAt\":\"2026-11-05T09:15:00Z\",\"recordedAt\":\"2026-11-05T09:15:00Z\",\"currency\":\"ZMW\","
                + "\"lines\":[],\"goodsAtList\":" + owed + ",\"discounts\":0.00,\"deliveryRefunded\":0.00,\"waiverTakenBack\":0.00,\"owedGross\":" + owed
                + ",\"feeBearer\":\"" + bearer + "\",\"deliveryOption\":\"OWN_DELIVERY\",\"deliveryPayer\":\"ORIAH_COLLECTS\",\"keycloakId\":\"kc-alice\"}");
    }

    private Map<String, Object> debt(UUID correction) {
        return jdbc.queryForMap("SELECT * FROM refund_due WHERE id = ?", correction);
    }

    private long debts(UUID correction) {
        return jdbc.queryForObject("SELECT count(*) FROM refund_due WHERE id = ?", Long.class, correction);
    }

    private List<Map<String, Object>> events(UUID debt) {
        return jdbc.queryForList("SELECT event_type, actor, occurred_at, payload::text AS payload FROM outbox WHERE aggregate_type = 'refund_due' AND aggregate_id = ? ORDER BY seq",
                debt.toString());
    }

    // ---------------------------------------------------------------- the debt

    @Test
    @DisplayName("a paid order cancelled before it left: one debt under the correction's id, owed since the correction, for the customer to bear the sending charge, and announced")
    void aCancellationIsOwed() {
        UUID order = UUID.randomUUID();
        UUID confirmation = paidSale(order, "225.00");
        UUID correction = UUID.randomUUID();

        corrected("CANCELLATION", correction, order, confirmation, "225.00", "CUSTOMER");

        Map<String, Object> debt = debt(correction);
        assertThat(debt).containsEntry("kind", "CANCELLATION").containsEntry("order_id", order).containsEntry("confirmation_id", confirmation)
                .containsEntry("currency", "ZMW").containsEntry("fee_bearer", "CUSTOMER").containsEntry("status", "OWED").containsEntry("paid_money_out_id", null);
        assertThat((BigDecimal) debt.get("owed_gross")).isEqualByComparingTo("225.00");
        assertThat(((java.sql.Timestamp) debt.get("owed_since")).toInstant()).isEqualTo(Instant.parse("2026-11-05T09:15:00Z"));
        assertThat(events(correction)).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("event_type", "payment.refund_due_recorded");
            assertThat((String) event.get("payload")).contains("\"dueId\":\"" + correction + "\"").contains("\"kind\":\"CANCELLATION\"").contains("\"owedGross\":225.00")
                    .contains("\"feeBearer\":\"CUSTOMER\"").contains("\"status\":\"OWED\"").contains("\"owedSince\":\"2026-11-05T09:15:00Z\"")
                    .doesNotContain("OR-000123").doesNotContain("kc-alice");
        });
    }

    @Test
    @DisplayName("pieces that came back: a debt for what they are owed, and when Oriah bears the sending charge the debt says so")
    void aReturnIsOwed() {
        UUID order = UUID.randomUUID();
        UUID confirmation = paidSale(order, "180.00");
        UUID correction = UUID.randomUUID();

        corrected("RETURN", correction, order, confirmation, "90.00", "ORIAH");

        assertThat(debt(correction)).containsEntry("kind", "RETURN").containsEntry("fee_bearer", "ORIAH").containsEntry("status", "OWED");
        assertThat((BigDecimal) debt(correction).get("owed_gross")).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("a correction that owes nothing makes no debt, and one told twice makes one")
    void nothingOwedAndARepeat() {
        UUID order = UUID.randomUUID();
        UUID confirmation = paidSale(order, "180.00");
        UUID nothing = UUID.randomUUID();
        corrected("CANCELLATION", nothing, order, confirmation, "0.00", "CUSTOMER");
        assertThat(debts(nothing)).isZero();
        assertThat(events(nothing)).isEmpty();

        UUID correction = UUID.randomUUID();
        corrected("RETURN", correction, order, confirmation, "90.00", "CUSTOMER");
        corrected("RETURN", correction, order, confirmation, "90.00", "CUSTOMER");
        assertThat(debts(correction)).isEqualTo(1);
        assertThat(events(correction)).hasSize(1);

        assertThatThrownBy(() -> corrected("RETURN", correction, order, confirmation, "80.00", "CUSTOMER"))
                .as("the same correction with another amount is a broken event, never a second debt").isInstanceOf(IllegalStateException.class);
        assertThat((BigDecimal) debt(correction).get("owed_gross")).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("never more is owed than the money that came in: one debt over it, or two that add up over it, is refused")
    void moreThanTheMoneyThatCameIsRefused() {
        UUID order = UUID.randomUUID();
        UUID confirmation = paidSale(order, "180.00");

        assertThatThrownBy(() -> corrected("CANCELLATION", UUID.randomUUID(), order, confirmation, "180.01", "CUSTOMER")).isInstanceOf(IllegalStateException.class);
        corrected("RETURN", UUID.randomUUID(), order, confirmation, "90.00", "CUSTOMER");
        corrected("RETURN", UUID.randomUUID(), order, confirmation, "90.00", "CUSTOMER");
        UUID third = UUID.randomUUID();
        assertThatThrownBy(() -> corrected("RETURN", third, order, confirmation, "0.01", "CUSTOMER")).isInstanceOf(IllegalStateException.class);
        assertThat(debts(third)).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(owed_gross) FROM refund_due WHERE confirmation_id = ?", BigDecimal.class, confirmation)).isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("a debt needs the money that paid the sale: a confirmation payment does not know, one for another order, or one that never paid a sale is refused")
    void theConfirmationMustHavePaidThatSale() {
        UUID order = UUID.randomUUID();
        UUID settled = paidSale(order, "180.00");
        UUID pending = confirmed(UUID.randomUUID(), "180.00");

        assertThatThrownBy(() -> corrected("RETURN", UUID.randomUUID(), order, UUID.randomUUID(), "90.00", "CUSTOMER")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> corrected("RETURN", UUID.randomUUID(), UUID.randomUUID(), settled, "90.00", "CUSTOMER")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> corrected("RETURN", UUID.randomUUID(), order, pending, "90.00", "CUSTOMER")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> corrected("RETURN", UUID.randomUUID(), order, settled, "90.00", "SOMEBODY")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_due WHERE confirmation_id IN (?, ?)", Long.class, settled, pending)).isZero();
    }

    @Test
    @DisplayName("when the money record was cancelled after it paid the sale, the debt is in question: nobody pays it until the owner says whether the money arrived")
    void aVoidedConfirmationMakesTheDebtInQuestion() {
        UUID order = UUID.randomUUID();
        UUID confirmation = confirmed(order, "180.00");
        confirmations.voidConfirmation(confirmation, "keyed against the wrong order", SAM);
        paid(order, confirmation);
        assertThat(jdbc.queryForObject("SELECT outcome_reason FROM payment_confirmation WHERE id = ?", String.class, confirmation)).isEqualTo("SETTLED_AFTER_VOID");
        UUID correction = UUID.randomUUID();

        corrected("CANCELLATION", correction, order, confirmation, "180.00", "CUSTOMER");

        assertThat(debt(correction)).containsEntry("status", "IN_QUESTION");
        assertThat((String) events(correction).getFirst().get("payload")).contains("\"status\":\"IN_QUESTION\"");
    }

    // ---------------------------------------------------------------- what is announced, to the byte

    private static String contract(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/contracts/" + name)).strip();
    }

    @Test
    @DisplayName("the four payment facts are written exactly as the contract files say, field for field and in that order")
    void theFactsAreWrittenAsTheContractsSay() throws Exception {
        UUID debt = UUID.fromString("c1a2b3c4-d5e6-4f70-8192-a3b4c5d6e7f8");
        UUID order = UUID.fromString("0f9a4c2e-6b1d-4e8f-a3c5-7d9e1b2f4a60");
        UUID confirmation = UUID.fromString("6d8fa0b2-4a6c-4d8f-a0b2-ce4a6c8e0fc5");
        UUID payout = UUID.fromString("e3c4d5e6-f708-4192-a3b4-c5d6e7f8091a");

        assertThat(EventJson.write(new RefundDueRecordedEvent(debt, "CANCELLATION", order, confirmation, new BigDecimal("225.00"), "ZMW", "CUSTOMER", "OWED",
                Instant.parse("2026-11-05T09:15:00.123456Z"), Instant.parse("2026-11-05T09:15:02.5Z")))).isEqualTo(contract("payment-refund-due-recorded-v1.json"));
        assertThat(EventJson.write(new RefundClosedEvent(debt, order, confirmation, new BigDecimal("225.00"), "ZMW", "MONEY_NEVER_ARRIVED",
                Instant.parse("2026-11-07T08:30:00Z"), Instant.parse("2026-11-07T08:30:00.75Z")))).isEqualTo(contract("payment-refund-closed-v1.json"));
        assertThat(EventJson.write(new PaidOutEvent(payout, "REFUND", debt, "CANCELLATION", order, confirmation, "MTN", new BigDecimal("225.00"), new BigDecimal("5.00"),
                new BigDecimal("220.00"), new BigDecimal("5.00"), "CUSTOMER", "ZMW", Instant.parse("2026-11-06T12:00:00Z"), Instant.parse("2026-11-06T12:03:10.25Z"))))
                .isEqualTo(contract("payment-paid-out-v1.json"));
        assertThat(EventJson.write(new PayoutVoidedEvent(payout, "REFUND", debt, order, confirmation, "MTN", new BigDecimal("225.00"), new BigDecimal("5.00"),
                new BigDecimal("220.00"), new BigDecimal("5.00"), "ZMW", Instant.parse("2026-11-06T12:00:00Z"), Instant.parse("2026-11-08T07:45:00.5Z"))))
                .isEqualTo(contract("payment-payout-voided-v1.json"));
    }
}
