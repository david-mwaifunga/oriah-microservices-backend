package com.oriah.payment.claim;

import java.time.Instant;
import java.util.UUID;

/**
 * What a shopper said they paid with, and where it stands. {@code moneyRecorded}: money is already confirmed with this reference for the SAME order
 * (so the claim is not false and cannot be rejected). {@code usedElsewhere}: this reference is already confirmed for ANOTHER order (so the claim can be
 * rejected with that proof, and the service checks it).
 */
public record ClaimView(
        UUID id,
        UUID orderId,
        String method,
        String reference,
        Instant submittedAt,
        Instant holdExpiresAt,
        Instant receivedAt,
        boolean rejected,
        Instant rejectedAt,
        String proofType,
        String rejectedBy,
        boolean moneyRecorded,
        boolean usedElsewhere) {
}
