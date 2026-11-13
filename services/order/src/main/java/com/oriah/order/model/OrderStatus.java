package com.oriah.order.model;

import java.util.Set;

/**
 * Hand-rolled order state machine. Allowed transitions:
 *
 * <pre>
 *   CREATED ──claim──▶ PAYMENT_SUBMITTED ──money──▶ PAID ──fulfill──▶ FULFILLED ──ship──▶ SHIPPED ──deliver──▶ DELIVERED
 *      │  │                  │                       ▲                                                          (terminal)
 *      │  └──────money───────┼───────────────────────┤
 *      │                     └──lapse/reject──▶ EXPIRED ──money (piece still free)──┘
 *      └──cancel──▶ CANCELLED  (terminal)             (an unpaid order's hold ran out, or its claim was rejected)
 * </pre>
 *
 * <p>An order is a hold on pieces for a shopper who has not paid yet. CREATED is placed and held; PAYMENT_SUBMITTED is
 * CREATED after the shopper said how they paid (the claim: method and reference), which stretches the hold while staff check;
 * EXPIRED is an unpaid order whose hold ran out or whose claim was rejected. PAID is reached only by money: a staff-confirmed
 * payment settles the sale (the settlement saga), and it can settle a PAYMENT_SUBMITTED order, a CREATED one, or an EXPIRED one
 * if its pieces are still free. No person asks for PAID, EXPIRED or PAYMENT_SUBMITTED through the admin transition
 * ({@link #adminMayRequest}); the edges are here so that the saga and the sweeper can make them.
 *
 * <p>A paid sale is never undone by a status change: there is no PAID -&gt; CANCELLED (undoing a sale is a refund, Phase 12).
 * Once SHIPPED or beyond, no cancellation either: the order is out for delivery and returns go through a separate flow.
 *
 * <p>{@link #canTransitionTo(OrderStatus)} is the single source of truth.
 * Add a new edge here and the test suite will tell you what callers
 * (or admin endpoints) need updating.
 */
public enum OrderStatus {
    CREATED(Set.of("PAYMENT_SUBMITTED", "EXPIRED", "CANCELLED", "PAID")),
    PAYMENT_SUBMITTED(Set.of("PAID", "EXPIRED")),
    EXPIRED(Set.of("PAID")),
    PAID(Set.of("FULFILLED")),
    FULFILLED(Set.of("SHIPPED")),
    SHIPPED(Set.of("DELIVERED")),
    DELIVERED(Set.of()),     // terminal
    CANCELLED(Set.of());     // terminal

    private final Set<String> allowedNext;

    OrderStatus(Set<String> allowedNext) {
        this.allowedNext = allowedNext;
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext.contains(next.name());
    }

    public boolean isTerminal() {
        return allowedNext.isEmpty();
    }

    /**
     * Whether a person may ask for this status through the admin transition endpoint: the fulfilment steps, and cancelling an unpaid
     * order (which the state machine itself allows only from CREATED). Never PAID (only money makes an order paid), EXPIRED (only a hold
     * lapsing or a rejected claim) or PAYMENT_SUBMITTED (only the shopper's claim).
     */
    public static boolean adminMayRequest(OrderStatus target) {
        return target == FULFILLED || target == SHIPPED || target == DELIVERED || target == CANCELLED;
    }
}
