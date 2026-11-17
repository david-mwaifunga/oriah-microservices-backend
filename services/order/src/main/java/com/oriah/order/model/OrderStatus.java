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
 * <p>A paid sale is never undone by a status change alone. Since Phase 12 a paid order that has not been dispatched (PAID, or FULFILLED:
 * packed, still on the shelf) can be CANCELLED, but only by its correction ({@code Order.cancelPaid()}), which puts the stock back and
 * records what the customer is owed; {@code Order.transitionTo} refuses the same edge, so no other door can take it.
 * Once SHIPPED or beyond, no cancellation: the parcel has left, and what comes back is a return (the status does not change).
 *
 * <p>{@link #canTransitionTo(OrderStatus)} is the single source of truth.
 * Add a new edge here and the test suite will tell you what callers
 * (or admin endpoints) need updating.
 */
public enum OrderStatus {
    CREATED(Set.of("PAYMENT_SUBMITTED", "EXPIRED", "CANCELLED", "PAID")),
    PAYMENT_SUBMITTED(Set.of("PAID", "EXPIRED")),
    EXPIRED(Set.of("PAID")),
    PAID(Set.of("FULFILLED", "CANCELLED")),      // CANCELLED only through the paid cancellation (Order.cancelPaid)
    FULFILLED(Set.of("SHIPPED", "DELIVERED", "CANCELLED")),   // DELIVERED only for a pickup (handed over): Order.transitionTo knows the option, this enum cannot
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
     * Whether a person may ask for this status through the generic admin transition endpoint: only cancelling an unpaid order (which the state machine
     * itself allows only from CREATED). Packing, sending and delivering are the dispatch steps (Phase 8), which keep the record the books and refunds
     * read; never PAID (only money makes an order paid), EXPIRED (only a hold lapsing or a rejected claim) or PAYMENT_SUBMITTED (only the shopper's claim).
     */
    public static boolean adminMayRequest(OrderStatus target) {
        return target == CANCELLED;
    }

    /** The statuses that are reached only through the dispatch steps, whose refusal through the generic door says so. */
    public static boolean isDispatchStep(OrderStatus target) {
        return target == FULFILLED || target == SHIPPED || target == DELIVERED;
    }
}
