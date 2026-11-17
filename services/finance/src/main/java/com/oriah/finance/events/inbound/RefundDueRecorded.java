package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code payment.refund_due_recorded} (Phase 12), as finance reads it: the debt (its id is the correction's), what is owed, and whether it is owed outright or in question
 * (the money record was cancelled after it had paid the sale, and nobody has said yet whether the money arrived).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundDueRecorded(UUID dueId, UUID orderId, UUID confirmationId, BigDecimal owedGross, String currency, String status, Instant owedSince, Instant recordedAt) {
}
