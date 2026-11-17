package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.refund_due_recorded} (Phase 12): payment owes a customer money for one correction of a paid sale. Aggregate {@code refund_due} (the debt's id, which
 * is the correction's), topic {@code oriah.payment}, occurred when the debt was recorded. {@code status} is OWED, or IN_QUESTION when the money record that paid the
 * sale was cancelled after it paid and nobody has said yet whether the money arrived. Ids, codes, an amount and dates only.
 */
public record RefundDueRecordedEvent(UUID dueId, String kind, UUID orderId, UUID confirmationId, BigDecimal owedGross, String currency, String feeBearer, String status,
                                     Instant owedSince, Instant recordedAt) {
    public static final String TYPE = "payment.refund_due_recorded";
}
