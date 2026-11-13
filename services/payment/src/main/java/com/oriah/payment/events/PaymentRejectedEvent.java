package com.oriah.payment.events;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.rejected}: staff said a payment claim is false. Aggregate {@code payment_claim} (the claim id), topic {@code oriah.payment},
 * occurred when it was rejected. The order, method and reference are copied from the claim, never typed. Order acts only if it is still waiting
 * on that very claim; it posts nothing and uses no reference key.
 */
public record PaymentRejectedEvent(
        UUID claimId,
        UUID orderId,
        String method,
        String reference,
        String proofType,
        String rejectedBy,
        Instant rejectedAt) {

    public static final String TYPE = "payment.rejected";
}
