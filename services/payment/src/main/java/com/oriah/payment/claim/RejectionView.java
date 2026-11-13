package com.oriah.payment.claim;

import java.time.Instant;
import java.util.UUID;

/** A claim staff rejected as false: what was claimed (copied from the claim), the proof, and who. It posts nothing and uses no reference. */
public record RejectionView(
        UUID id,
        UUID claimId,
        UUID orderId,
        String method,
        String reference,
        String proofType,
        String evidence,
        String rejectedBy,
        String rejectedByName,
        Instant rejectedAt) {
}
