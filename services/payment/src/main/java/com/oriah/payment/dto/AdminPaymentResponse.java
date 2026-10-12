package com.oriah.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;

/**
 * One row of the admin payments list.
 *
 * <h2>Why this exists instead of reusing {@link PaymentIntentResponse}</h2>
 *
 * <p>Reusing the customer-facing DTO would tie two audiences to one schema: a
 * field added for the admin table would silently appear on
 * {@code /api/v1/payments/intents/{id}}, and the additions-only OpenAPI gate
 * would see the published {@code PaymentIntentResponse} schema change rather
 * than a new schema appear. Separate record, separate blast radius.
 *
 * <h2>What is deliberately missing</h2>
 *
 * <p><strong>{@code keycloakId}.</strong> The entity has it, and it would be
 * the easiest thing in the world to include. It is a stable subject identifier
 * that correlates a person across every service in the platform, and it tells
 * an admin reading a payment list precisely nothing they can act on. An
 * operator who needs the human behind a payment resolves {@code orderId}
 * through the order and customer admin endpoints, which is a bounded lookup on
 * one row rather than a column of opaque ids on every row.
 *
 * <p><strong>Anything from the gateway.</strong> No provider payload, no client
 * secret, no card data — none of which this service stores, and this DTO is
 * where that would stop being true. {@code AdminPaymentListTest} asserts the
 * JSON key set <em>exactly</em>, so adding a field here fails a test rather
 * than quietly widening what admins (and anything downstream of them) can see.
 *
 * <p>{@code failureReason} IS included: it is the processor's own words about
 * why a charge did not land, which is the main thing an operator is looking
 * for, and it describes the payment rather than the payer.
 */
public record AdminPaymentResponse(
        UUID id,
        UUID orderId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String failureReason,
        Instant createdAt
) {
    public static AdminPaymentResponse from(PaymentIntent p) {
        return new AdminPaymentResponse(
                p.id(),
                p.orderId(),
                p.amount(),
                p.currency(),
                p.status(),
                p.failureReason(),
                p.createdAt()
        );
    }
}
