package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.payout_voided} (Phase 12): a payout was cancelled as recorded wrongly; the debt it paid is owed again. The same figures as the payout, so whoever
 * hears of the cancellation first can still negate exactly. Aggregate {@code money_out}, occurred when it was cancelled. The typed reason is not in it.
 */
public record PayoutVoidedEvent(UUID payoutId, String purpose, UUID debtId, UUID orderId, UUID confirmationId, String method, BigDecimal gross, BigDecimal customerFee,
                                BigDecimal net, BigDecimal sendCharge, String currency, Instant paidAt, Instant voidedAt) {
    public static final String TYPE = "payment.payout_voided";
}
