package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A confirmation as staff see it. {@code outcome} is PENDING (order has not answered yet), SETTLED (it paid the sale) or HELD (it could not, and is
 * owed back; {@code outcomeReason} says why). {@code voided} is true once it was taken back (only possible before it paid a sale).
 */
public record ConfirmationView(
        UUID id,
        UUID orderId,
        String method,
        String reference,
        BigDecimal gross,
        BigDecimal fee,
        BigDecimal net,
        String currency,
        Instant arrivedAt,
        String backdateReason,
        String confirmedBy,
        String confirmedByName,
        Instant confirmedAt,
        String outcome,
        String outcomeReason,
        Instant outcomeAt,
        boolean voided,
        Instant voidedAt,
        String voidReason,
        String voidedBy) {
}
