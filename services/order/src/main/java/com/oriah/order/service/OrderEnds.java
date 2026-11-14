package com.oriah.order.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.order.events.InboundEvents.PaymentRejected;
import com.oriah.order.events.InboundEvents.ReservationExpired;
import com.oriah.order.events.OrderEventLog;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * How an order that is waiting for money ENDS without it, when another service says so (Phase 7; design FINAL A5): staff reject the claim the shopper
 * made, or inventory's sweep ends the hold. Each acts only on an order still waiting for payment, and every other state is a logged no-op, never an
 * error: an event that throws is parked on a dead-letter topic nobody reads.
 *
 * <p>An order having confirmed money applied to it is not ended: the lapse or rejection is REMEMBERED on the settlement ({@code deferred_end}) and applied
 * only if the settlement ends without paying the sale. If it pays, the money won. Both endings carry the reservation the pieces were held under, so
 * inventory lets go of that hold and a stale event can never reach a newer one.
 */
@Service
@Slf4j
public class OrderEnds {

    private final OrderRepository repository;
    private final OrderEventLog events;
    private final JdbcTemplate jdbc;
    private final SettlementService settlements;

    public OrderEnds(OrderRepository repository, OrderEventLog events, JdbcTemplate jdbc, SettlementService settlements) {
        this.repository = repository;
        this.events = events;
        this.jdbc = jdbc;
        this.settlements = settlements;
    }

    /** Staff could not find the money the shopper's claim named: the order EXPIRES, if this is the claim it holds and it is still waiting. */
    @Transactional
    public void rejected(PaymentRejected rejection) {
        settlements.lockOrder(rejection.orderId());
        Order order = repository.findById(rejection.orderId()).orElse(null);
        if (order == null) {
            log.info("Claim {} was rejected for order {}, which this order service does not know: nothing to end", rejection.claimId(), rejection.orderId());
            return;
        }
        if (order.status() != OrderStatus.PAYMENT_SUBMITTED || !rejection.claimId().equals(order.claimId())) {
            log.info("Claim {} was rejected for order {}, which is {} and holds claim {}: nothing to end", rejection.claimId(), order.id(), order.status(),
                    order.claimId());
            return;
        }
        if (order.settlingConfirmationId() != null) {
            defer(order, "REJECTED");
            return;
        }
        end(order);
    }

    /** Inventory's sweep ended the hold: the order EXPIRES, if this is the hold it holds NOW and it is still waiting. */
    @Transactional
    public void lapsed(ReservationExpired lapse) {
        settlements.lockOrder(lapse.orderId());
        Order order = repository.findById(lapse.orderId()).orElse(null);
        if (order == null) {
            log.info("Reservation {} lapsed for order {}, which this order service does not know: nothing to end", lapse.reservationId(), lapse.orderId());
            return;
        }
        if (!lapse.reservationId().equals(order.reservationId())) {
            log.info("Reservation {} lapsed, but order {} holds {} now: a stale event, ignored", lapse.reservationId(), order.id(), order.reservationId());
            return;
        }
        boolean waiting = order.status() == OrderStatus.CREATED || order.status() == OrderStatus.PAYMENT_SUBMITTED;
        if (!waiting) {
            log.info("Reservation {} lapsed for order {}, which is {}: nothing to end", lapse.reservationId(), order.id(), order.status());
            return;
        }
        if (order.holdState() == com.oriah.order.model.HoldState.PENDING) {
            // Written but never announced (the placement stopped before it was recorded): ending it would announce an expiry for an order nobody was told
            // about. The hold job removes the row without announcing anything.
            log.info("Reservation {} lapsed for order {}, which was never announced: left for the hold job", lapse.reservationId(), order.id());
            return;
        }
        if (order.settlingConfirmationId() != null) {
            defer(order, "EXPIRED");
            return;
        }
        end(order);
    }

    /**
     * The hold job's safety net for an expiry event that never arrived (it was dead-lettered after three failed deliveries, say): an order that is still
     * waiting for money, holds its pieces by its own record, is not being settled and has no extension pending, and whose hold ran out before
     * {@code cutoff}, EXPIRES. The same ending as {@link #lapsed}, reached from the clock instead of from inventory's event, and idempotent with it: the
     * event arriving later finds the order no longer waiting.
     */
    @Transactional
    public void lapsedByClock(java.util.UUID orderId, java.time.Instant cutoff) {
        settlements.lockOrder(orderId);
        Order order = repository.findById(orderId).orElse(null);
        if (order == null) {
            return;
        }
        boolean waiting = order.status() == OrderStatus.CREATED || order.status() == OrderStatus.PAYMENT_SUBMITTED;
        if (!waiting || order.holdState() != com.oriah.order.model.HoldState.HELD || order.settlingConfirmationId() != null || order.holdExtensionPending()
                || order.holdExpiresAt() == null || !order.holdExpiresAt().isBefore(cutoff)) {
            return;
        }
        log.warn("Order id={}'s hold ran out at {} and no expiry event came: ending it from the clock", order.id(), order.holdExpiresAt());
        end(order);
    }

    private void end(Order order) {
        OrderStatus previous = order.status();
        order.expire();
        events.moved(order, previous, SettlementService.ACTOR);
        log.info("Order id={} EXPIRED (was {}), releasing hold {}", order.id(), previous, order.reservationId());
    }

    /** Money is being applied: remember the ending, and let the settlement apply it only if it fails to pay the sale. */
    private void defer(Order order, String end) {
        int deferred = jdbc.update("UPDATE order_settlement SET deferred_end = ? WHERE order_id = ? AND state IN ('RECEIVED', 'CONSUMING', 'BLOCKED_BOX') "
                + "AND deferred_end IS NULL", end, order.id());
        log.info("Order id={} would end ({}), but confirmed money is being applied: {}", order.id(), end, deferred == 1 ? "deferred to the settlement" : "already deferred");
    }
}
