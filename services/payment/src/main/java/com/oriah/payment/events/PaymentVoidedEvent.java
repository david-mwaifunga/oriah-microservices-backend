package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.voided}: a confirmation keyed wrongly was taken back. Aggregate {@code payment_confirmation}, topic {@code oriah.payment},
 * occurred when it was voided. Only a confirmation whose money has not paid a sale can be voided; {@code outcome} says what it had become
 * (PENDING or HELD). Finance reverses the fee it posted for the confirmation; the replacement is a new confirmation.
 *
 * <p>{@code arrivedAt} (last, Phase 10) is the day the money ARRIVED: the fee was posted in the month of that day, so the reversal belongs to it, not to the day
 * the void was keyed. Absent on an event written before the field existed.
 */
public record PaymentVoidedEvent(
        UUID confirmationId,
        UUID orderId,
        String method,
        String reference,
        BigDecimal gross,
        BigDecimal fee,
        BigDecimal net,
        String currency,
        String outcome,
        String reason,
        String voidedBy,
        Instant voidedAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Instant arrivedAt) {

    public static final String TYPE = "payment.voided";
}
