package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money that came in and could not pay a sale, owed back to the customer (never revenue). {@code reason} is WRONG_AMOUNT, ORDER_CANCELLED, PIECE_SOLD,
 * ALREADY_PAID or ORDER_UNKNOWN; {@code status} is OWED, or VOIDED once its confirmation was voided as keyed wrongly.
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
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
        String confirmedBy,
        /** When it was paid back; absent while it is not. */
        Instant repaidAt,
        /** Who bears the provider's charge for sending it back, from why it was held: CUSTOMER or ORIAH; absent for money that is never paid back. */
        String feeBearer,
        /** The payout that paid it back; absent while it is not paid. */
        UUID payoutId) {
}
