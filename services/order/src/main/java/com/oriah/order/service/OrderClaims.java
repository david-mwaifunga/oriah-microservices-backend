package com.oriah.order.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.events.OrderEventLog;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.ClaimMethod;
import com.oriah.order.model.HoldState;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The database halves of a payment claim, each one short local transaction (no HTTP in here: {@code OrderClaimService} asks inventory between them,
 * for the reasons {@link OrderWriter} gives).
 *
 * <p>The claim is written FIRST ({@link #begin}): the shopper's word is recorded whatever inventory then does, and the stretch of the hold is marked
 * pending. {@link #extended} records that inventory stretched it and only THEN announces {@code order.payment_submitted}, carrying the new time;
 * {@link #lapsed} is the other ending, when inventory says the hold is gone.
 */
@Service
@Slf4j
public class OrderClaims {

    private final OrderRepository repository;
    private final OrderEventLog events;
    private final Clock clock;

    public OrderClaims(OrderRepository repository, OrderEventLog events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    /** What {@link #begin} found: the order, and whether the hold still has to be stretched (a new claim, or an earlier extension that never finished). */
    public record Claimed(Order order, boolean needsExtension) {
    }

    /**
     * Takes the claim: CREATED -> PAYMENT_SUBMITTED. The same claim again (the same method and reference) is the same claim; another is refused.
     *
     * @throws ResourceNotFoundException   the order does not exist or is not the owner's (the same 404 for both)
     * @throws SalesProblem                409: {@code payment-being-applied}, {@code payment-already-submitted}, {@code order-not-awaiting-payment},
     *                                     {@code order-not-held}, {@code hold-expired}
     */
    @Transactional
    public Claimed begin(String owner, UUID orderId, ClaimMethod method, String normalisedReference) {
        Order order = repository.findById(orderId).filter(o -> o.isOwnedBy(owner))
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId.toString()));
        if (order.settlingConfirmationId() != null) {
            throw new SalesProblem(HttpStatus.CONFLICT, "payment-being-applied",
                    "a confirmed payment is being applied to this order right now: look at it again in a moment");
        }
        if (order.status() == OrderStatus.PAYMENT_SUBMITTED) {
            if (order.hasClaim(method, normalisedReference)) {
                return new Claimed(order, order.holdExtensionPending());
            }
            throw new SalesProblem(HttpStatus.CONFLICT, "payment-already-submitted",
                    "you already gave a payment reference for this order (" + order.claimMethod() + " " + order.claimReference()
                            + "); staff will confirm it or tell you if they cannot find it");
        }
        if (order.status() != OrderStatus.CREATED) {
            throw new SalesProblem(HttpStatus.CONFLICT, "order-not-awaiting-payment",
                    "this order is " + order.status() + ": a payment reference can only be given for an order waiting for payment");
        }
        // A payment through ZynlePay is in flight (Phase 13): a second way of paying now could pay the order twice.
        ProviderPaymentHolds.refuseWhileInFlight(order);
        if (order.holdState() != HoldState.HELD) {
            throw new SalesProblem(HttpStatus.CONFLICT, "order-not-held", "this order does not hold its pieces: place it again");
        }
        if (order.holdExpiresAt() != null && !order.holdExpiresAt().isAfter(clock.instant())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "hold-expired",
                    "the time this order held its pieces for has passed: place it again");
        }
        // To the microsecond Postgres keeps, so the claim the event reports is exactly the claim the row holds.
        order.submitClaim(method, normalisedReference, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        repository.flush();
        log.info("Order id={} took a payment claim {} {} (claim {})", order.id(), method, normalisedReference, order.claimId());
        return new Claimed(order, true);
    }

    /**
     * Inventory stretched the hold to {@code expiresAt}: the extension is done, and {@code order.payment_submitted} goes out, carrying that time.
     * Idempotent: an order whose extension was already recorded is answered as it stands and announces nothing more.
     */
    @Transactional
    public Order extended(UUID orderId, Instant expiresAt, String actor) {
        Order order = repository.findById(orderId).orElseThrow(() -> new IllegalStateException("order " + orderId + " vanished"));
        if (!order.holdExtensionPending() || order.status() != OrderStatus.PAYMENT_SUBMITTED) {
            return order;
        }
        order.holdExtended(expiresAt);
        events.claimSubmitted(order, actor);
        log.info("Order id={} hold stretched to {}: payment claim announced", order.id(), order.holdExpiresAt());
        return order;
    }

    /**
     * Inventory says the hold is gone: the order EXPIRES (carrying its reservation id, so inventory lets go of that one) and the claim, if any,
     * is never announced. An order that is not waiting for payment any more, or is having money applied, is left to whatever is deciding it.
     */
    @Transactional
    public Order lapsed(UUID orderId, String actor) {
        Order order = repository.findById(orderId).orElseThrow(() -> new IllegalStateException("order " + orderId + " vanished"));
        boolean waiting = order.status() == OrderStatus.CREATED || order.status() == OrderStatus.PAYMENT_SUBMITTED;
        if (!waiting || order.settlingConfirmationId() != null) {
            return order;
        }
        OrderStatus previous = order.status();
        order.expire();
        events.moved(order, previous, actor);
        log.info("Order id={} EXPIRED: its hold {} is gone", order.id(), order.reservationId());
        return order;
    }
}
