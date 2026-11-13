package com.oriah.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One confirmed payment order was told about and what it did with it: the settlement saga's memory, and the staff's list of money that is stuck.
 * A row of {@code order_settlement}.
 *
 * @param state         RECEIVED, CONSUMING (inventory is being asked to sell the pieces), BLOCKED_BOX (only a box is missing: the sale waits), DONE
 *                      (the order is paid with it) or UNSETTLED (it could not pay the sale; payment holds the money)
 * @param reason        why it is UNSETTLED: WRONG_AMOUNT, ORDER_UNKNOWN, ORDER_CANCELLED, ALREADY_PAID, PIECE_SOLD or VOIDED
 * @param reservationId the hold THIS attempt sells, minted and kept so a retry names the same one
 * @param deferredEnd   a lapse (EXPIRED) or a rejected claim (REJECTED) that arrived while this was in flight: applied if it ends unsettled
 * @param voidedAt      payment voided this confirmation
 */
public record Settlement(UUID id, UUID orderId, UUID confirmationId, String state, String reason, String method, String reference, BigDecimal gross,
                         BigDecimal fee, BigDecimal net, String currency, Instant arrivedAt, String confirmedBy, UUID reservationId, int attempts,
                         Instant nextAttemptAt, String deferredEnd, Instant receivedAt, Instant settledAt, Instant voidedAt) {

    public boolean inFlight() {
        return state.equals("RECEIVED") || state.equals("CONSUMING") || state.equals("BLOCKED_BOX");
    }
}
