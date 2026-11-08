package com.oriah.order.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.order.events.OrderEvent;
import com.oriah.order.events.OrderEventPublisher;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Persists a priced order, and nothing else.
 *
 * <h2>Why this is its own bean</h2>
 *
 * Pricing makes an outbound HTTP call to catalog and must not hold a database
 * connection while it does -- the rule {@code OrderService}'s own javadoc
 * states on {@code verifyPaymentClaim}: "holding a database connection open
 * across a network call to another service is how a slow dependency becomes an
 * exhausted connection pool."
 *
 * <p>Keeping the two on one bean would not achieve that. A non-transactional
 * method calling a {@code @Transactional} method on the SAME bean is
 * self-invocation: Spring's proxy is never crossed, the annotation does
 * nothing, and the only visible symptom is that the thing you thought was
 * transactional silently is not. So pricing lives on
 * {@link OrderPricingService}, persistence lives here, and
 * {@code OrderService.placeOrder} injects both -- two beans, two real proxy
 * crossings.
 *
 * <p>{@code PlaceOrderPricingTest.pricingHappensOutsideTheTransaction} asserts
 * the boundary from the inside, by reading
 * {@code TransactionSynchronizationManager} within the catalog stub, so a
 * later refactor that re-nests the call fails rather than quietly regressing.
 *
 * <h2>This transaction stays short on purpose</h2>
 *
 * Everything in it is local: build the aggregate, save, publish. There is no
 * HTTP here and there should never be.
 *
 * <p>The Kafka publish is still INSIDE the transaction, which is a known and
 * pre-existing problem: an event can be emitted for a transaction that later
 * rolls back, and notification-service renders that event as an email to the
 * shopper. It is deliberately not fixed here. Moving it to an after-commit
 * listener raises its own question -- whether {@code order.created} is a
 * best-effort notification or a durable integration event, which decides
 * between a listener and an outbox -- and this change adds no new reachable
 * cause, because every refusal it introduces happens during pricing, before
 * this method is entered.
 */
@Service
@Slf4j
public class OrderWriter {

    private final OrderRepository repository;
    private final OrderEventPublisher events;

    public OrderWriter(OrderRepository repository, OrderEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /**
     * @param priced the lines as CATALOG prices them -- never as the caller
     *               stated them. The currency is catalog's too: the request's
     *               currency is only ever a claim to be checked, never the
     *               value stored.
     */
    @Transactional
    public Order persist(String keycloakId, UUID shippingAddressId,
                         OrderPricingService.PricedOrder priced) {
        Order order = new Order(keycloakId, shippingAddressId, priced.currency(),
                priced.deliveryOption(), priced.shippingAmount());
        priced.lines().forEach(line ->
                order.addItem(line.variantId(), line.title(), line.quantity(), line.unitPrice()));

        Order saved = repository.save(order);
        log.info("Placed order id={} keycloakId={} subtotal={} total={} {} items={}",
                saved.id(), keycloakId, saved.subtotal(), saved.total(),
                saved.currency(), saved.items().size());
        events.publish(new OrderEvent(saved.id(), keycloakId, null, OrderStatus.CREATED,
                saved.total(), saved.currency()));
        return saved;
    }
}
