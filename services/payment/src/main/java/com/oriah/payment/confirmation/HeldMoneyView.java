package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money that came in and could not pay a sale, owed back to the customer (never revenue). {@code reason} is WRONG_AMOUNT, ORDER_CANCELLED, PIECE_SOLD,
 * ALREADY_PAID or ORDER_UNKNOWN; {@code status} is OWED, or VOIDED once its confirmation was voided as keyed wrongly.
 */
public record HeldMoneyView(
        UUID id,
        UUID confirmationId,
        UUID orderId,
        String reason,
        BigDecimal owedGross,
        String currency,
        String status,
        Instant createdAt,
        Instant voidedAt,
        String method,
        String reference,
        Instant arrivedAt,
        String confirmedBy) {
}
