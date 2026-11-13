package com.oriah.order.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.order.dto.PlaceOrderRequest;
import com.oriah.order.events.OrderEventLog;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Every write here names its {@code actor} -- {@code user:<keycloak subject>}
 * of whoever asked: the customer for place, pay and cancel, the member of
 * staff for an admin transition -- and records the change in the event log in
 * the same transaction ({@link OrderEventLog}). The actor is separate from the
 * order's owner because they are not always the same person: staff move a
 * customer's order, and will place phone orders on a customer's behalf.
 */
@Service
@Slf4j
public class OrderService {

    private final OrderRepository repository;
    // Records every status change made here; placement records its own, in
    // OrderWriter, with the persist it belongs to.
    private final OrderEventLog events;
    private final PaymentIntentVerifier paymentVerifier;
    private final OrderPricingService pricing;
    private final OrderWriter writer;
    private final AddressOwnership addresses;

    public OrderService(OrderRepository repository,
                        OrderEventLog events,
                        PaymentIntentVerifier paymentVerifier,
                        OrderPricingService pricing,
                        OrderWriter writer,
                        AddressOwnership addresses) {
        this.repository = repository;
        this.events = events;
        this.paymentVerifier = paymentVerifier;
        this.pricing = pricing;
        this.writer = writer;
        this.addresses = addresses;
    }

    /**
     * Resolves one of the caller's own orders and proves the payment claim
     * against payment-service, without taking a transaction.
     *
     * <h2>Why this is a separate step rather than part of markPaid</h2>
     *
     * It makes an outbound HTTP call, so it must not run inside a transaction:
     * holding a database connection open across a network call to another
     * service is how a slow dependency becomes an exhausted connection pool.
     * {@link #markPaid} keeps its own {@code @Transactional} boundary and is
     * invoked through the proxy by the controller, after this -- calling it
     * from here instead would be self-invocation, which bypasses the proxy,
     * and the order would silently be marked paid with no transaction at all.
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
     * <p>The order's total is authoritative since V4 (subtotal + delivery -
     * discount), and {@code PaymentIntentVerifier} holds a payment to exactly
     * that total.
     *
     * <h2>The address is the caller's, checked first</h2>
     *
     * The shopper chooses the address, but only from their own: customer is
     * asked, with their token, before anything is priced -- so an address that
     * is not theirs, or one nobody could check, costs no call to catalog or
     * shipping and saves nothing ({@link AddressOwnership}). Like pricing, it
     * is a network call, and it happens here, outside any transaction.
     *
     * @param actor who placed it, {@code user:<keycloak subject>}
     */
    public Order placeOrder(String keycloakId, PlaceOrderRequest req, String actor) {
        addresses.requireCallersOwn(req.shippingAddressId());
        OrderPricingService.PricedOrder priced = pricing.price(req);
        return writer.persist(keycloakId, req.shippingAddressId(), priced, actor);
    }

    /**
     * Marks one of the owner's orders paid, after {@link #verifyPaymentClaim}
     * has proved the payment: attaches the payment intent, moves the order to
     * PAID and appends {@code order.paid} -- ONE transaction, so the order is
     * never paid without its payment recorded, nor announced paid without
     * being so. It used to be two transactions (attach, then transition), and
     * a failure between them left a CREATED order holding a payment.
     *
     * <p>An order that is no longer CREATED (cancelled meanwhile, or already
     * paid) refuses with 409, and so does one changed by someone else since
     * this transaction read it (the order's version): exactly one of two
     * simultaneous changes wins, and only the winner is in the log.
     *
     * @return the order, or empty when it does not exist or is not the owner's
     */
    @Transactional
    public Optional<Order> markPaid(String owner, UUID orderId, UUID paymentIntentId, String actor) {
        return repository.findById(orderId)
                .filter(o -> o.keycloakId().equals(owner))
                .map(o -> {
                    OrderStatus prev = o.status();
                    o.attachPaymentIntent(paymentIntentId);
                    o.transitionTo(OrderStatus.PAID);
                    events.moved(o, prev, actor);
                    log.info("Order id={} {} -> PAID with PaymentIntent id={}", o.id(), prev, paymentIntentId);
                    return o;
                });
    }

    /**
     * The owner cancels their own order. Ownership is checked inside the same
     * transaction as the change, so what was checked is what is changed.
     *
     * @return the order, or empty when it does not exist or is not the owner's
     */
    @Transactional
    public Optional<Order> cancel(String owner, UUID orderId, String actor) {
        return repository.findById(orderId)
                .filter(o -> o.keycloakId().equals(owner))
                .map(o -> move(o, OrderStatus.CANCELLED, actor));
    }

    /**
     * Drive the FSM. Caller-allowed transitions (USER): CREATED -> CANCELLED,
     * through {@link #cancel}. Admin-allowed transitions (ORDER_ADMIN):
     * everything else. Authorization is enforced at the controller layer via
     * {@code @PreAuthorize}.
     *
     * @param actor the member of staff, {@code user:<keycloak subject>}
     */
    @Transactional
    public Optional<Order> transition(UUID orderId, OrderStatus targetStatus, String actor) {
        // The order is looked up FIRST: one that does not exist is a 404 whatever is asked of it.
        return repository.findById(orderId).map(o -> {
            if (!OrderStatus.adminMayRequest(targetStatus)) {
                // Only money makes an order PAID (the settlement saga), only a lapsed hold or a rejected claim makes it EXPIRED, only the shopper's claim
                // makes it PAYMENT_SUBMITTED. A person asking for one of them would skip the money, the stock and the receipt.
                throw new IllegalStateException("Order " + o.id() + " cannot be moved to " + targetStatus + " by a person: "
                        + (targetStatus == OrderStatus.PAID ? "only a confirmed payment makes an order paid"
                        : "it is set by the shopper's claim, or by the hold lapsing or a rejected claim"));
            }
            return move(o, targetStatus, actor);
        });
    }

    private Order move(Order order, OrderStatus target, String actor) {
        OrderStatus prev = order.status();
        order.transitionTo(target);
        events.moved(order, prev, actor);
        log.info("Order id={} {} -> {} by {}", order.id(), prev, target, actor);
        return order;
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
