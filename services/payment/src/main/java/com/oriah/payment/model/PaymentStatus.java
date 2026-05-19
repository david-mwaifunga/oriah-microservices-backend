package com.oriah.payment.model;

/**
 * Lifecycle of a {@link PaymentIntent}:
 *
 * <pre>
 *   PENDING ──confirm-ok──▶ SUCCEEDED
 *          │
 *          └──confirm-fail─▶ FAILED
 *
 *   SUCCEEDED ──refund──▶ REFUNDED   (out of scope this iteration)
 * </pre>
 *
 * Refunds are out of scope for the minimum viable flow — they'll land
 * with the PAYMENT_ADMIN endpoints in a polish cycle.
 */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    REFUNDED
}
