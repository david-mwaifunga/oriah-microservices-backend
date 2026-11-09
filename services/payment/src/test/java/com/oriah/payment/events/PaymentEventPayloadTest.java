package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.events.EventJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire format of payment's two events, pinned character for character.
 *
 * <p>These are contracts: order (and later finance) read them. A renamed
 * field, a moved one, an amount written as {@code 4500} instead of
 * {@code 4500.00}, or a time written as a number would each break a reader
 * silently, so the exact JSON is the assertion -- property order included.
 * Both are full snapshots of the intent (topic oriah.payment, aggregate
 * (payment_intent, id), schema 1), and the amount is in {@code currency}.
 */
class PaymentEventPayloadTest {

    private static final UUID INTENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CHARGE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant DECIDED = Instant.parse("2026-11-09T07:30:00.123456Z");

    @Test
    @DisplayName("payment.succeeded, exactly")
    void paymentSucceeded() {
        String json = EventJson.write(new PaymentSucceeded(INTENT, ORDER, new BigDecimal("4500.00"), "ZMW",
                CHARGE, "fake_ch_abc", DECIDED, "SUCCEEDED"));

        assertThat(json).isEqualTo("{"
                + "\"intentId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"orderId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"amount\":4500.00,"
                + "\"currency\":\"ZMW\","
                + "\"chargeReference\":\"33333333-3333-3333-3333-333333333333\","
                + "\"gatewayReference\":\"fake_ch_abc\","
                + "\"confirmedAt\":\"2026-11-09T07:30:00.123456Z\","
                + "\"status\":\"SUCCEEDED\""
                + "}");
    }

    @Test
    @DisplayName("payment.failed, exactly")
    void paymentFailed() {
        String json = EventJson.write(new PaymentFailed(INTENT, ORDER, new BigDecimal("4500.00"), "ZMW",
                CHARGE, "card declined", DECIDED, "FAILED"));

        assertThat(json).isEqualTo("{"
                + "\"intentId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"orderId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"amount\":4500.00,"
                + "\"currency\":\"ZMW\","
                + "\"chargeReference\":\"33333333-3333-3333-3333-333333333333\","
                + "\"reason\":\"card declined\","
                + "\"failedAt\":\"2026-11-09T07:30:00.123456Z\","
                + "\"status\":\"FAILED\""
                + "}");
    }

    @Test
    @DisplayName("an amount keeps its two decimal places, and a whole-second time has no fraction")
    void amountsAndTimesKeepTheirShape() {
        String json = EventJson.write(new PaymentSucceeded(INTENT, ORDER, new BigDecimal("100.50"), "ZMW",
                CHARGE, "fake_ch_abc", Instant.parse("2026-11-09T07:30:00Z"), "SUCCEEDED"));

        assertThat(json).contains("\"amount\":100.50,").contains("\"confirmedAt\":\"2026-11-09T07:30:00Z\"");
    }
}
