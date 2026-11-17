package com.oriah.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.HoldState;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The order's side of a payment through ZynlePay (Phase 13), in the short transactions it is made of. {@link ProviderPaymentHoldService} runs
 * them around the call to inventory, which is never made inside a transaction (no lock and no connection is held while inventory answers).
 *
 * <p>The order carries the attempt in {@code provider_payment_id}. While it does, nothing else pays or ends the order; see
 * {@link #refuseWhileInFlight}. Everything here runs under the order's lock ({@link OrderLocks}), like every other change to an order.
 */
@Service
@Slf4j
public class ProviderPaymentHolds {

    private final OrderLocks locks;
    private final OrderRepository repository;
    private final OrderClaims claims;
    private final Clock clock;
    private final Duration minHoldLeft;

    public ProviderPaymentHolds(OrderLocks locks, OrderRepository repository, OrderClaims claims, Clock clock,
                                @Value("${oriah.sales.provider-payment-min-hold-left:PT2M}") Duration minHoldLeft) {
        this.locks = locks;
        this.repository = repository;
        this.claims = claims;
        this.clock = clock;
        this.minHoldLeft = minHoldLeft;
    }

    /** What {@link #begin} found: the order, and whether its hold still has to be stretched for this attempt. */
    public record Taken(Order order, boolean needsExtension) {
    }

    /** The one refusal every other door gives while an attempt is in flight. */
    public static void refuseWhileInFlight(Order order) {
        if (order.providerPaymentId() != null) {
            throw new SalesProblem(HttpStatus.CONFLICT, "provider-payment-in-progress",
                    "a payment through ZynlePay is in progress for this order: it cannot be changed, cancelled or paid another way until that payment "
                            + "has finished").with("providerPaymentId", order.providerPaymentId());
        }
    }

    /**
     * Takes the attempt. The same attempt again is the same answer; another is refused unless {@code supersedes} names the one the order carries
     * (payment says so only of an attempt that is finished in its own records, to let a retry after a decline through before the FAILED event
     * has arrived).
     *
     * @throws ResourceNotFoundException the order does not exist or is not the owner's (the same 404 for both)
     * @throws SalesProblem              409: {@code payment-being-applied}, {@code payment-already-submitted}, {@code order-not-awaiting-payment},
     *                                   {@code order-not-held}, {@code hold-expired}, {@code hold-too-short}, {@code provider-payment-in-progress}
     */
    @Transactional
    public Taken begin(String owner, UUID orderId, UUID attemptId, UUID supersedes) {
        Order order = locks.lock(orderId).filter(o -> o.isOwnedBy(owner))
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId.toString()));
        if (order.settlingConfirmationId() != null) {
            throw new SalesProblem(HttpStatus.CONFLICT, "payment-being-applied",
                    "a confirmed payment is being applied to this order right now: look at it again in a moment");
        }
        if (order.status() == OrderStatus.PAYMENT_SUBMITTED) {
            throw new SalesProblem(HttpStatus.CONFLICT, "payment-already-submitted",
                    "you already gave a payment reference for this order (" + order.claimMethod() + " " + order.claimReference()
                            + "); staff will confirm it or tell you if they cannot find it");
        }
        if (order.status() != OrderStatus.CREATED) {
            throw new SalesProblem(HttpStatus.CONFLICT, "order-not-awaiting-payment",
                    "this order is " + order.status() + ": only an order waiting for payment can be paid").with("orderStatus", order.status().name());
        }
        if (order.holdState() != HoldState.HELD) {
            throw new SalesProblem(HttpStatus.CONFLICT, "order-not-held", "this order does not hold its pieces: place it again");
        }
        if (attemptId.equals(order.providerPaymentId())) {
            return new Taken(order, order.holdExtensionPending());
        }
        if (order.providerPaymentId() != null && !order.providerPaymentId().equals(supersedes)) {
            refuseWhileInFlight(order);
        }
        Instant now = clock.instant();
        if (order.holdExpiresAt() != null && !order.holdExpiresAt().isAfter(now)) {
            throw new SalesProblem(HttpStatus.CONFLICT, "hold-expired", "the time this order held its pieces for has passed: place it again");
        }
        if (order.holdExpiresAt() != null && order.holdExpiresAt().isBefore(now.plus(minHoldLeft))) {
            throw tooShort();
        }
        order.takeProviderPayment(attemptId);
        repository.flush();
        log.info("Order id={} took provider payment {}", order.id(), attemptId);
        return new Taken(order, true);
    }

    /**
     * Inventory stretched the hold to {@code expiresAt}. If that leaves too little time for a customer to approve (inventory's own cap), the order
     * does not keep the attempt: that is committed here, and the caller raises the refusal (raised in this transaction it would undo the letting go).
     *
     * @return the order, or empty when the hold is too short and the order has let the attempt go
     */
    @Transactional
    public java.util.Optional<Order> extended(UUID orderId, UUID attemptId, Instant expiresAt) {
        Order order = locks.lock(orderId).orElseThrow(() -> new IllegalStateException("order " + orderId + " vanished"));
        if (!attemptId.equals(order.providerPaymentId())) {
            // The attempt ended while inventory was answering (payment gave it up, or its money came): it is not "taken", and payment must not go
            // on to ask ZynlePay for money on this answer.
            throw new SalesProblem(HttpStatus.CONFLICT, "provider-payment-ended",
                    "this payment was ended while it was being started: start a new one from the order");
        }
        if (expiresAt.isBefore(clock.instant().plus(minHoldLeft))) {
            order.endProviderPayment(attemptId);
            return java.util.Optional.empty();
        }
        if (order.holdExtensionPending()) {
            order.holdExtended(expiresAt);
            log.info("Order id={} hold stretched to {} for provider payment {}", order.id(), order.holdExpiresAt(), attemptId);
        }
        return java.util.Optional.of(order);
    }

    /** The hold job's finish of a stretch that could not be made when the attempt was taken: no problem to raise, nobody is waiting. */
    @Transactional
    public void extendedLater(UUID orderId, Instant expiresAt) {
        Order order = locks.lock(orderId).orElse(null);
        if (order == null || !order.holdExtensionPending() || order.status() != OrderStatus.CREATED) {
            return;
        }
        if (order.providerPaymentId() != null && expiresAt.isBefore(clock.instant().plus(minHoldLeft))) {
            // The same rule as when the attempt is taken: pieces held for too short a time are not paid for. The order lets the attempt go, so
            // the repeat of the start is refused and nothing is asked of ZynlePay.
            log.info("Order id={} lets go of provider payment {}: its hold could be stretched only to {}", order.id(), order.providerPaymentId(), expiresAt);
            order.endProviderPayment(order.providerPaymentId());
            return;
        }
        order.holdExtended(expiresAt);
        log.info("Order id={} hold stretched to {} (pending extension finished)", order.id(), order.holdExpiresAt());
    }

    /** Inventory says the hold is gone: the order EXPIRES, as for a claim. */
    public void lapsed(UUID orderId, String actor) {
        claims.lapsed(orderId, actor);
    }

    /** {@code payment.provider_payment_updated} FAILED: the order lets go of that attempt, and of no other. */
    @Transactional
    public void failed(UUID orderId, UUID attemptId) {
        locks.lock(orderId).ifPresent(order -> {
            if (order.endProviderPayment(attemptId)) {
                log.info("Order id={} let go of provider payment {}: it failed", order.id(), attemptId);
            }
        });
    }

    static SalesProblem tooShort() {
        return new SalesProblem(HttpStatus.CONFLICT, "hold-too-short",
                "this order's pieces are held for too short a time to pay for it now: place it again and pay straight away");
    }
}
