package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;

/**
 * {@code payment.succeeded}, schema 1 -- the gateway captured the money.
 *
 * <p>A full snapshot of the intent at that moment (topic oriah.payment,
 * aggregate (payment_intent, intentId)). {@code amount} is in
 * {@code currency}; {@code confirmedAt} is the gateway's capture time, which
 * is also the event's occurred-at. The field order is the wire order, pinned
 * by PaymentEventPayloadTest: change it only with a new schema version.
 */
public record PaymentSucceeded(
        UUID intentId,
        UUID orderId,
        BigDecimal amount,
        String currency,
        UUID chargeReference,
        String gatewayReference,
        Instant confirmedAt,
        String status) {

    public static PaymentSucceeded of(PaymentIntent intent) {
        if (intent.status() != PaymentStatus.SUCCEEDED) {
            throw new IllegalArgumentException("payment.succeeded describes a SUCCEEDED intent; "
                    + intent.id() + " is " + intent.status());
        }
        return new PaymentSucceeded(intent.id(), intent.orderId(), intent.amount(), intent.currency(),
                intent.chargeReference(), intent.gatewayReference(), intent.confirmedAt(), intent.status().name());
    }
}
