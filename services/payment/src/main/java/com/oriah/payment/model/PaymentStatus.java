package com.oriah.payment.model;

import java.util.Set;

/**
 * Lifecycle of a {@link PaymentIntent}:
 *
 * <pre>
 *   PENDING ──confirm──▶ CHARGING ──captured──▶ SUCCEEDED
 *                                 └─declined──▶ FAILED
 *
 *   SUCCEEDED ──refund──▶ REFUNDED   (out of scope this iteration)
 * </pre>
 *
 * CHARGING means "the gateway has been, or is being, asked to charge
 * {@code chargeReference}". It is committed before the gateway is called, so
 * a charge in flight is always on record; it ends only with the gateway's
 * answer about that charge.
 *
 * Refunds are out of scope for the minimum viable flow — they'll land
 * with the PAYMENT_ADMIN endpoints in a polish cycle.
 */
public enum PaymentStatus {
    PENDING,
    CHARGING,
    SUCCEEDED,
    FAILED,
    REFUNDED;

    /**
     * The states that may yet take an order's money, or already have. An
     * order has at most one intent in one of them (the partial unique index
     * uq_payment_intents_one_live_per_order); a FAILED intent took nothing and
     * leaves room for a new one.
     */
    public static final Set<PaymentStatus> LIVE = Set.of(PENDING, CHARGING, SUCCEEDED);
}
