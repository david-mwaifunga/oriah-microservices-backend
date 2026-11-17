package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.paid_out} (Phase 12): money went out to a customer. {@code purpose} is REFUND (the debt is a correction's) or HELD_MONEY (the debt is the held
 * record); {@code cause} is CANCELLATION or RETURN, or why the money was held. {@code gross} is what was owed, {@code customerFee} the part of the provider's charge
 * the customer bore, {@code net} what they received, {@code sendCharge} what the provider charged Oriah. Aggregate {@code money_out} (the payout's id), occurred on
 * the day the money went out. No reference, no name and no typed reason.
 */
public record PaidOutEvent(UUID payoutId, String purpose, UUID debtId, String cause, UUID orderId, UUID confirmationId, String method, BigDecimal gross,
                           BigDecimal customerFee, BigDecimal net, BigDecimal sendCharge, String feeBearer, String currency, Instant paidAt, Instant recordedAt) {
    public static final String TYPE = "payment.paid_out";
}
