package com.oriah.order.model;

import java.util.Set;

/**
 * Hand-rolled order state machine. Allowed transitions:
 *
 * <pre>
 *   CREATED ──pay──▶ PAID ──fulfill──▶ FULFILLED ──ship──▶ SHIPPED ──deliver──▶ DELIVERED
 *      │              │                                                            (terminal)
 *      └──cancel──────┴──cancel──▶ CANCELLED  (terminal)
 * </pre>
 *
 * Once SHIPPED or beyond, no cancellation — the order is in customs /
 * out for delivery, returns go through a separate flow (out of scope).
 *
 * {@link #canTransitionTo(OrderStatus)} is the single source of truth.
 * Add a new edge here and the test suite will tell you what callers
 * (or admin endpoints) need updating.
 */
public enum OrderStatus {
    CREATED(Set.of("PAID", "CANCELLED")),
    PAID(Set.of("FULFILLED", "CANCELLED")),
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
}
