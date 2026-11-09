package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;

/**
 * {@code payment.failed}, schema 1 -- a charge took no money.
 *
 * <p>A full snapshot of the intent at that moment (topic oriah.payment,
 * aggregate (payment_intent, intentId)). {@code amount} -- what was asked
 * for, not taken -- is in {@code currency}; {@code reason} is the gateway's
 * own words ("card declined"), or "charge not completed" when the charge was
 * voided before it was decided; {@code failedAt} is the gateway's decision
 * time, also the event's occurred-at. The field order is the wire order,
 * pinned by PaymentEventPayloadTest.
 */
public record PaymentFailed(
        UUID intentId,
        UUID orderId,
        BigDecimal amount,
        String currency,
        UUID chargeReference,
        String reason,
        Instant failedAt,
        String status) {

    public static PaymentFailed of(PaymentIntent intent) {
        if (intent.status() != PaymentStatus.FAILED) {
            throw new IllegalArgumentException("payment.failed describes a FAILED intent; "
                    + intent.id() + " is " + intent.status());
        }
        return new PaymentFailed(intent.id(), intent.orderId(), intent.amount(), intent.currency(),
                intent.chargeReference(), intent.failureReason(), intent.confirmedAt(), intent.status().name());
    }
}
