package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.held}: money that came in could not pay a sale and is owed back to the customer (WRONG_AMOUNT, ORDER_CANCELLED, PIECE_SOLD,
 * ALREADY_PAID or ORDER_UNKNOWN), never revenue. Aggregate {@code payment_confirmation}, topic {@code oriah.payment}, occurred when payment
 * learned it. The fee posts once, from the confirmation: this event posts none.
 */
public record PaymentHeldEvent(
        UUID confirmationId,
        UUID orderId,
        String reason,
        BigDecimal owedGross,
        String currency,
        Instant heldAt) {

    public static final String TYPE = "payment.held";
}
