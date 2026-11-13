package com.oriah.order.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.oriah.order.client.InventoryClient;
import com.oriah.order.exception.UpstreamUnavailableException;
import com.oriah.order.model.HoldState;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;
import com.oriah.order.service.OrderHolds.BoxGoneException;
import com.oriah.order.service.OrderHolds.HoldGoneException;
import com.oriah.order.service.OrderHolds.PieceGoneException;

import lombok.extern.slf4j.Slf4j;

/**
 * The middle of the settlement saga (Phase 7; design FINAL A3): asks inventory to SELL the pieces of an order whose money has been confirmed, between
 * two short transactions ({@link SettlementService}). Deliberately NOT transactional: no database connection is held while inventory answers.
 *
 * <p>Every way it can go has a defined ending, and none loses the money or sells a piece twice:
 * <ul>
 *   <li><b>sold:</b> the order is made PAID;</li>
 *   <li><b>the hold is gone</b> (it lapsed before the sweep ended it, or it was released): the old hold is given back, a NEW one is taken for the same
 *       lines under an id chosen and kept first, and that is sold;</li>
 *   <li><b>the piece is gone:</b> PIECE_SOLD, the money is held for the customer;</li>
 *   <li><b>only a box is gone:</b> the sale waits (BLOCKED_BOX): a paid ring that is still free is never refunded for a missing box;</li>
 *   <li><b>anything else</b> (inventory silent, a conflict that clears by itself): the same call is repeated later under the same id, backing off;
 *       inventory answers a repeat once.</li>
 * </ul>
 *
 * <p>Safe to run twice at once (the consumer drives straight after the money arrives, and the retry job is the safety net): inventory's sale is
 * idempotent and the closing transactions act only on a settlement still in flight.
 */
@Service
@Slf4j
public class SettlementDriver {

    /** First wait after a failed attempt; each failure doubles it, up to {@link #LONGEST_WAIT}. */
    static final Duration FIRST_WAIT = Duration.ofSeconds(15);
    static final Duration LONGEST_WAIT = Duration.ofMinutes(5);
    /** A sale waiting only for a box asks again every minute: packaging is received by people, not by the clock. */
    static final Duration BOX_WAIT = Duration.ofMinutes(1);

    private final SettlementService settlements;
    private final OrderHolds holds;
    private final OrderRepository repository;

    public SettlementDriver(SettlementService settlements, OrderHolds holds, OrderRepository repository) {
        this.settlements = settlements;
        this.holds = holds;
        this.repository = repository;
    }

    /** The wait after the {@code failures}-th failure of one settlement (0 for the first): 15 s, 30 s, 60 s ... never past five minutes. */
    static Duration backoff(int failures) {
        long seconds = FIRST_WAIT.toSeconds() << Math.min(failures, 10);
        return Duration.ofSeconds(Math.min(seconds, LONGEST_WAIT.toSeconds()));
    }

    /** Applies one confirmation that is in flight; anything else (settled, unsettled, unknown) is left alone. */
    public void drive(UUID confirmationId) {
        Settlement s = settlements.find(confirmationId);
        if (s == null || !s.inFlight()) {
            return;
        }
        if (s.voidedAt() != null) {
            // Voided before inventory was asked: never applied. (A void that lands after inventory has been asked cannot be undone: see SettlementService.paid.)
            settlements.unsettled(confirmationId, "VOIDED", false);
            return;
        }
        Order order = repository.findById(s.orderId()).orElse(null);
        if (order == null) {
            settlements.unsettled(confirmationId, "ORDER_UNKNOWN", true);
            return;
        }
        try {
            sell(order, s);
            settlements.paid(confirmationId);
        } catch (PieceGoneException gone) {
            log.warn("Order {}: a piece is gone, so confirmation {} is unsettled PIECE_SOLD: {}", order.id(), confirmationId, gone.getMessage());
            settlements.unsettled(confirmationId, "PIECE_SOLD", true);
        } catch (BoxGoneException gone) {
            settlements.blocked(confirmationId, BOX_WAIT);
        } catch (RuntimeException notYet) {
            log.warn("Order {}: confirmation {} could not be applied yet (attempt {}): {}", order.id(), confirmationId, s.attempts() + 1, notYet.getMessage());
            settlements.retryLater(confirmationId, backoff(s.attempts()));
        }
    }

    private void sell(Order order, Settlement s) {
        UUID reservation = s.reservationId();
        boolean holdsIt = reservation.equals(order.reservationId()) && order.holdState() == HoldState.HELD
                && (order.status() == OrderStatus.CREATED || order.status() == OrderStatus.PAYMENT_SUBMITTED);
        if (holdsIt) {
            try {
                holds.sell(reservation, s.arrivedAt());
                return;
            } catch (HoldGoneException gone) {
                // It lapsed before the sweep ended it, or was released: a new hold is chosen and KEPT, then taken.
                reservation = settlements.chooseNewReservation(s.confirmationId(), reservation);
            }
        }
        holds.release(order.reservationId());
        InventoryClient.Reservation taken = holds.holdAgain(order, reservation);
        if (!"HELD".equals(taken.status()) && !"CONSUMED".equals(taken.status())) {
            // Inventory knows this id, and it is already dead (it ran out while the sale could not be applied): never ask for it again.
            log.warn("Order {}: the replacement hold {} is already {} at inventory: a fresh one is chosen", order.id(), reservation, taken.status());
            settlements.chooseNewReservation(s.confirmationId(), reservation);
            throw new UpstreamUnavailableException("inventory");
        }
        try {
            holds.sell(reservation, s.arrivedAt());
        } catch (HoldGoneException gone) {
            // A replacement taken some time ago has lapsed before the sale got through: asking for the same id again would answer the same dead hold for
            // ever, so a fresh one is chosen and kept, and the next attempt takes it.
            log.warn("Order {}: the replacement hold {} is gone before it was sold: a fresh one is chosen", order.id(), reservation);
            settlements.chooseNewReservation(s.confirmationId(), reservation);
            throw new UpstreamUnavailableException("inventory");
        }
    }
}
