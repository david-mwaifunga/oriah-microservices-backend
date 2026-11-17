package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.events.EventJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire format of payment's four staff-money events (Phase 7), pinned character for character. They are contracts: order reads
 * {@code payment.confirmed}, {@code payment.rejected} and {@code payment.voided}, and finance will read all four (a fee posts once, from the
 * confirmation; a void reverses it; held money posts no fee). Amounts keep their two places and carry their currency; times are ISO instants.
 * Property order is the record's component order.
 */
class StaffPaymentEventPayloadTest {

    private static final UUID CONFIRMATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CLAIM = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant ARRIVED = Instant.parse("2026-11-09T07:30:00Z");
    private static final Instant KEYED = Instant.parse("2026-11-10T08:15:30.123456Z");

    @Test
    @DisplayName("payment.confirmed, exactly: the money as recorded, net worked out, and which announcement of it this is")
    void paymentConfirmed() {
        String json = EventJson.write(new PaymentConfirmedEvent(CONFIRMATION, ORDER, "MTN", "MP2411ABC", new BigDecimal("1000.00"),
                new BigDecimal("10.00"), new BigDecimal("990.00"), "ZMW", ARRIVED, null, "user:kc-sam", KEYED, 1, null, null));

        assertThat(json).isEqualTo("{"
                + "\"confirmationId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"orderId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"method\":\"MTN\","
                + "\"reference\":\"MP2411ABC\","
                + "\"gross\":1000.00,"
                + "\"fee\":10.00,"
                + "\"net\":990.00,"
                + "\"currency\":\"ZMW\","
                + "\"arrivedAt\":\"2026-11-09T07:30:00Z\","
                + "\"backdateReason\":null,"
                + "\"confirmedBy\":\"user:kc-sam\","
                + "\"confirmedAt\":\"2026-11-10T08:15:30.123456Z\","
                + "\"attempt\":1"
                + "}");
    }

    @Test
    @DisplayName("payment.confirmed for cash has no reference, and a late one carries the reason it was recorded late")
    void paymentConfirmedCashAndLate() {
        String json = EventJson.write(new PaymentConfirmedEvent(CONFIRMATION, ORDER, "CASH", null, new BigDecimal("250.00"),
                new BigDecimal("0.00"), new BigDecimal("250.00"), "ZMW", ARRIVED, "paid on Friday, keyed Monday", "user:kc-maya", KEYED, 3, null, null));

        assertThat(json).contains("\"method\":\"CASH\",\"reference\":null,").contains("\"backdateReason\":\"paid on Friday, keyed Monday\"").endsWith("\"attempt\":3}");
    }

    @Test
    @DisplayName("payment.rejected, exactly: the claim and what was claimed, copied from it")
    void paymentRejected() {
        String json = EventJson.write(new PaymentRejectedEvent(CLAIM, ORDER, "MTN", "MP2411ABC", "NOT_FOUND_AT_PROVIDER", "user:kc-sam", KEYED));

        assertThat(json).isEqualTo("{"
                + "\"claimId\":\"33333333-3333-3333-3333-333333333333\","
                + "\"orderId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"method\":\"MTN\","
                + "\"reference\":\"MP2411ABC\","
                + "\"proofType\":\"NOT_FOUND_AT_PROVIDER\","
                + "\"rejectedBy\":\"user:kc-sam\","
                + "\"rejectedAt\":\"2026-11-10T08:15:30.123456Z\""
                + "}");
    }

    @Test
    @DisplayName("payment.voided, exactly: what was voided, what had become of it, who and why, and (last) the day the money arrived, which is the month its fee posted in")
    void paymentVoided() {
        String json = EventJson.write(new PaymentVoidedEvent(CONFIRMATION, ORDER, "MTN", "MP2411ABC", new BigDecimal("1000.00"),
                new BigDecimal("10.00"), new BigDecimal("990.00"), "ZMW", "HELD", "keyed against the wrong order", "user:kc-sam", KEYED, ARRIVED));

        assertThat(json).isEqualTo("{"
                + "\"confirmationId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"orderId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"method\":\"MTN\","
                + "\"reference\":\"MP2411ABC\","
                + "\"gross\":1000.00,"
                + "\"fee\":10.00,"
                + "\"net\":990.00,"
                + "\"currency\":\"ZMW\","
                + "\"outcome\":\"HELD\","
                + "\"reason\":\"keyed against the wrong order\","
                + "\"voidedBy\":\"user:kc-sam\","
                + "\"voidedAt\":\"2026-11-10T08:15:30.123456Z\","
                + "\"arrivedAt\":\"2026-11-09T07:30:00Z\""
                + "}");
    }

    @Test
    @DisplayName("payment.held, exactly: owed to the customer, never revenue, carrying no fee, and (last) the day the money arrived, which is the month it is owed from")
    void paymentHeld() {
        String json = EventJson.write(new PaymentHeldEvent(CONFIRMATION, ORDER, "WRONG_AMOUNT", new BigDecimal("1000.00"), "ZMW", KEYED, ARRIVED));

        assertThat(json).isEqualTo("{"
                + "\"confirmationId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"orderId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"reason\":\"WRONG_AMOUNT\","
                + "\"owedGross\":1000.00,"
                + "\"currency\":\"ZMW\","
                + "\"heldAt\":\"2026-11-10T08:15:30.123456Z\","
                + "\"arrivedAt\":\"2026-11-09T07:30:00Z\""
                + "}");
    }
}
