package com.oriah.order.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.order.dto.PlaceOrderRequest;
import com.oriah.order.events.OrderEvent;
import com.oriah.order.events.OrderEventPublisher;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class OrderService {

    private final OrderRepository repository;
    // Still needed by transition(), which publishes its own status changes.
    // Placement's publish moved to OrderWriter with the persist it belongs to.
    private final OrderEventPublisher events;
    private final PaymentIntentVerifier paymentVerifier;
    private final OrderPricingService pricing;
    private final OrderWriter writer;

    public OrderService(OrderRepository repository,
                        OrderEventPublisher events,
                        PaymentIntentVerifier paymentVerifier,
                        OrderPricingService pricing,
                        OrderWriter writer) {
        this.repository = repository;
        this.events = events;
        this.paymentVerifier = paymentVerifier;
        this.pricing = pricing;
        this.writer = writer;
    }

    /**
     * Resolves one of the caller's own orders and proves the payment claim
     * against payment-service, without taking a transaction.
     *
     * <h2>Why this is a separate step rather than part of attachPayment</h2>
     *
     * It makes an outbound HTTP call, so it must not run inside a transaction:
     * holding a database connection open across a network call to another
     * service is how a slow dependency becomes an exhausted connection pool.
     * {@code attachPayment} and {@code transition} keep their own
     * {@code @Transactional} boundaries and are still invoked through the
     * proxy by the controller -- calling them from here instead would be
     * self-invocation, which bypasses the proxy, and the attach would silently
     * never be flushed.
     *
     * @return the caller's order, or empty when it does not exist or is not
     *         theirs -- the controller answers both with the same 404
     * @throws IllegalStateException   the claim is false (409)
     * @throws org.springframework.web.server.ResponseStatusException
     *         payment could not be consulted (503)
     */
    public Optional<Order> verifyPaymentClaim(String keycloakId, UUID orderId, UUID paymentIntentId) {
        Optional<Order> own = repository.findById(orderId)
                .filter(o -> o.keycloakId().equals(keycloakId));
        // Payment is deliberately NOT consulted for an order that is not the
        // caller's: the 404 must not depend on anything downstream, and a
        // probe for someone else's order should not reach payment at all.
        own.ifPresent(order -> paymentVerifier.verifySettledFor(order, paymentIntentId));
        return own;
    }

    /**
     * Places an order at CATALOG's prices.
     *
     * <h2>Deliberately NOT @Transactional</h2>
     *
     * It was, and it stored {@code req.items()} verbatim -- the hole this
     * closes. Pricing now requires an outbound call to catalog, and this
     * method must not hold a database connection while that happens; the rule
     * is stated on {@link #verifyPaymentClaim} below and it applies here for
     * the same reason.
     *
     * <p>So the work is split across two other beans and this method only
     * orders them. Both calls cross a Spring proxy, which is the point:
     * pricing and persisting as two methods on THIS bean would have been
     * self-invocation, the {@code @Transactional} would have done nothing, and
     * the only symptom would have been a transaction that silently was not
     * one.
     *
     * <h2>What the caller still controls, and what they do not</h2>
     *
     * They choose the variants and the quantities. They do not choose the
     * price, the title or the currency: those come from catalog. A price or
     * currency they STATE is checked against catalog's and refuses on
     * disagreement -- see {@link OrderPricingService} for why checking beats
     * ignoring -- so their figure can only ever cause a refusal, never a lower
     * charge.
     *
     * <p>What this does NOT fix: {@code PaymentIntentVerifier} still cannot
     * compare {@code intent.amount} to {@code order.total}, because the order
     * still has no discount or shipping while checkout charges
     * {@code afterDiscount + shipping}. A 0.01 intent therefore still settles
     * a correctly-priced order. That needs an authoritative-money marker per
     * row so orders placed before this change stay payable, and it is a
     * separate change.
     */
    public Order placeOrder(String keycloakId, PlaceOrderRequest req) {
        OrderPricingService.PricedOrder priced = pricing.price(req);
        return writer.persist(keycloakId, req.shippingAddressId(), priced);
    }

    @Transactional
    public Optional<Order> attachPayment(String keycloakId, UUID orderId, UUID paymentIntentId) {
        return repository.findById(orderId)
                .filter(o -> o.keycloakId().equals(keycloakId))
                .map(o -> {
                    o.attachPaymentIntent(paymentIntentId);
                    log.info("Attached PaymentIntent id={} to order id={}", paymentIntentId, o.id());
                    return o;
                });
    }

    /**
     * Drive the FSM. Caller-allowed transitions (USER): CREATED -> CANCELLED.
     * Admin-allowed transitions (ORDER_ADMIN): everything else.
     * Authorization is enforced at the controller layer via @PreAuthorize.
     */
    @Transactional
    public Optional<Order> transition(UUID orderId, OrderStatus targetStatus) {
        return repository.findById(orderId).map(o -> {
            OrderStatus prev = o.status();
            o.transitionTo(targetStatus);
            log.info("Order id={} {} -> {}", o.id(), prev, targetStatus);
            events.publish(new OrderEvent(o.id(), o.keycloakId(), prev, targetStatus, o.total(), o.currency()));
            return o;
        });
    }

    @Transactional(readOnly = true)
    public Optional<Order> findById(UUID id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public Page<Order> findMine(String keycloakId, Pageable pageable) {
        return repository.findByKeycloakIdOrderByPlacedAtDesc(keycloakId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Order> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }
}
