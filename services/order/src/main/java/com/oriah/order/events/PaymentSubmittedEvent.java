package com.oriah.order.events;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.oriah.order.model.Order;

/**
 * {@code order.payment_submitted}, schema version 1: a shopper's claim to have paid, in the one shape payment reads (it records the claim, so a
 * staff rejection can be bound to it). Not a snapshot of the order: a FACT about one claim. Payment declared this contract before order emitted it.
 *
 * @param referenceNormalised the reference in its one spelling ({@code PaymentReference}), as payment compares it with confirmed money
 * @param holdExpiresAt       when the pieces stay held to, after the hold was stretched for this claim
 */
@JsonPropertyOrder({"claimId", "orderId", "method", "referenceNormalised", "submittedAt", "holdExpiresAt"})
public record PaymentSubmittedEvent(
        UUID claimId,
        UUID orderId,
        String method,
        String referenceNormalised,
        Instant submittedAt,
        Instant holdExpiresAt
) {
    public static final String EVENT_TYPE = "order.payment_submitted";
    public static final int SCHEMA_VERSION = 1;

    public static PaymentSubmittedEvent of(Order order) {
        return new PaymentSubmittedEvent(order.claimId(), order.id(), order.claimMethod().name(), order.claimReference(),
                order.claimSubmittedAt(), order.holdExpiresAt());
    }
}
