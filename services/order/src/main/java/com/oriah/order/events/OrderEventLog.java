package com.oriah.order.events;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.AppendedEvent;
import com.oriah.events.EventDraft;
import com.oriah.events.OutboxWriter;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

/**
 * Writes order's events to its log, the outbox, inside the transaction that
 * changed the order. The relay publishes the log on {@code oriah.order}.
 *
 * <h2>Why this replaced sending to Kafka directly</h2>
 *
 * The old publisher sent from inside the transaction, before the order row
 * was even written: a placement that then failed was still announced, so
 * notification could email a customer about an order that does not exist;
 * and a crash between the commit and the send lost the message for good. An
 * outbox row commits with the order or rolls back with it, and the relay
 * publishes only what committed.
 *
 * <h2>The order of the writes is the point</h2>
 *
 * Every method first flushes the order's own pending writes, so a constraint
 * the order breaks fails here, before anything is appended; then appends, as
 * the transaction's last write. The append takes a lock that holds every
 * other appending transaction until this one commits (that is how the relay
 * keeps the log in commit order), so it is taken as late, and held as
 * briefly, as possible. Callers do not flush: this does it for them, so no
 * caller can get the order wrong.
 *
 * <p>{@code MANDATORY}: there is no correct way to record an order's change
 * outside the transaction that made it.
 */
@Component
public class OrderEventLog {

    private final OutboxWriter outbox;
    private final OrderRepository orders;

    public OrderEventLog(OutboxWriter outbox, OrderRepository orders) {
        this.outbox = outbox;
        this.orders = orders;
    }

    /**
     * {@code order.created}, at the moment the order was placed.
     *
     * @param actor who placed it: {@code user:<keycloak subject>}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendedEvent placed(Order order, String actor) {
        return append(order, null, actor, null, null);
    }

    /**
     * {@code order.<new status>}, at the moment the order moved
     * ({@link Order#transitionTo} stamps it).
     *
     * @param previousStatus the status it moved from
     * @param actor          who moved it: the customer, or the staff member
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendedEvent moved(Order order, OrderStatus previousStatus, String actor) {
        if (order.status() == OrderStatus.SHIPPED) {
            // A SHIPPED event without the dispatch would hide the courier fare from finance: it is appended only by shipped().
            throw new IllegalStateException("order.shipped is appended with its dispatch, through shipped()");
        }
        return append(order, previousStatus, actor, null, null);
    }

    /**
     * {@code order.<new status>} for a dispatch step, dated to the day the step TRUE happened ({@code occurredAt}), which may be earlier than the moment it
     * was typed. Never SHIPPED: that carries the dispatch, see {@link #shipped}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendedEvent moved(Order order, OrderStatus previousStatus, String actor, java.time.Instant occurredAt) {
        if (order.status() == OrderStatus.SHIPPED) {
            throw new IllegalStateException("order.shipped is appended with its dispatch, through shipped()");
        }
        return append(order, previousStatus, actor, occurredAt, null);
    }

    /**
     * {@code order.shipped}, dated to the day the parcel went, with the dispatch: the fare Oriah paid the courier (when it paid one) and who paid the
     * courier. The one place the fare is published.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendedEvent shipped(Order order, OrderStatus previousStatus, String actor, java.time.Instant occurredAt, OrderEvent.Dispatch dispatch) {
        return append(order, previousStatus, actor, occurredAt, OrderEvent.shipped(order, previousStatus, dispatch));
    }

    /**
     * {@code order.payment_submitted}: the shopper's claim to have paid, once the hold has been stretched for it. A fact about the claim, in
     * the shape payment reads ({@link PaymentSubmittedEvent}), at the moment the claim was made.
     *
     * @param actor the shopper, {@code user:<keycloak subject>}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendedEvent claimSubmitted(Order order, String actor) {
        orders.flush();
        return outbox.append(new EventDraft(
                OrderEvent.AGGREGATE_TYPE,
                order.id().toString(),
                PaymentSubmittedEvent.EVENT_TYPE,
                PaymentSubmittedEvent.SCHEMA_VERSION,
                OrderEvent.TOPIC,
                order.claimSubmittedAt(),
                actor,
                PaymentSubmittedEvent.of(order)));
    }

    /**
     * {@code order.paid} (version 2), at the moment the money ARRIVED: the sale as it was made and the confirmation that paid it. Written in the
     * transaction that made the order PAID. Called again for the same confirmation it is the SAME answer announced again ({@code republished}).
     *
     * @param previousStatus what the order was before it was paid; null when announcing the same answer again
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendedEvent paid(Order order, OrderStatus previousStatus, OrderPaidEvent.Payment payment, boolean republished, String actor) {
        orders.flush();
        return outbox.append(new EventDraft(
                OrderEvent.AGGREGATE_TYPE,
                order.id().toString(),
                OrderPaidEvent.TYPE,
                OrderPaidEvent.SCHEMA_VERSION,
                OrderEvent.TOPIC,
                payment.arrivedAt(),
                actor,
                OrderPaidEvent.of(order, previousStatus, payment, republished)));
    }

    /**
     * {@code order.payment_unsettled}: confirmed money that could not pay the sale. Keyed by the order id the money NAMED, which may be no order at
     * all (a mistyped id): the event is about the money, and payment must hear of it either way.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendedEvent paymentUnsettled(java.util.UUID orderId, java.util.UUID confirmationId, String reason, String currency,
                                          java.time.Instant occurredAt, String actor) {
        return outbox.append(new EventDraft(
                OrderEvent.AGGREGATE_TYPE,
                orderId.toString(),
                PaymentUnsettledEvent.TYPE,
                PaymentUnsettledEvent.SCHEMA_VERSION,
                OrderEvent.TOPIC,
                occurredAt,
                actor,
                new PaymentUnsettledEvent(confirmationId, orderId, reason, currency)));
    }

    private AppendedEvent append(Order order, OrderStatus previousStatus, String actor, java.time.Instant occurredAt, OrderEvent payload) {
        orders.flush();
        return outbox.append(new EventDraft(
                OrderEvent.AGGREGATE_TYPE,
                order.id().toString(),
                OrderEvent.eventType(order.status()),
                OrderEvent.SCHEMA_VERSION,
                OrderEvent.TOPIC,
                occurredAt != null ? occurredAt : (previousStatus == null ? order.placedAt() : order.updatedAt()),
                actor,
                payload != null ? payload : OrderEvent.of(order, previousStatus)));
    }
}
