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
    private final OrderEventPublisher events;
    private final PaymentIntentVerifier paymentVerifier;

    public OrderService(OrderRepository repository, OrderEventPublisher events,
                        PaymentIntentVerifier paymentVerifier) {
        this.repository = repository;
        this.events = events;
        this.paymentVerifier = paymentVerifier;
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

    @Transactional
    public Order placeOrder(String keycloakId, PlaceOrderRequest req) {
        Order order = new Order(keycloakId, req.shippingAddressId(), req.currency());
        req.items().forEach(li -> order.addItem(li.variantId(), li.title(), li.quantity(), li.unitPrice()));
        Order saved = repository.save(order);
        log.info("Placed order id={} keycloakId={} total={} items={}",
                saved.id(), keycloakId, saved.total(), saved.items().size());
        events.publish(new OrderEvent(saved.id(), keycloakId, null, OrderStatus.CREATED, saved.total(), saved.currency()));
        return saved;
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
