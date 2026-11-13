package com.oriah.order.model;

/**
 * Where an order stands with its hold on the pieces at inventory (Phase 7). An order placed before the sale moved here has none: null, "legacy".
 *
 * <ul>
 *   <li>{@code PENDING}: the order is written and nobody has been told; inventory has not (yet) answered. Finished, or deleted, by the shopper's
 *       repeat or the hold job.</li>
 *   <li>{@code HELD}: inventory holds the pieces until {@code holdExpiresAt}.</li>
 *   <li>{@code RELEASED}: the hold was given back (cancelled, lapsed, rejected claim).</li>
 *   <li>{@code CONSUMED}: the pieces were sold (the order is paid).</li>
 * </ul>
 */
public enum HoldState {
    PENDING, HELD, RELEASED, CONSUMED
}
