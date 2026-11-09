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
        return append(order, null, actor);
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
        return append(order, previousStatus, actor);
    }

    private AppendedEvent append(Order order, OrderStatus previousStatus, String actor) {
        orders.flush();
        return outbox.append(new EventDraft(
                OrderEvent.AGGREGATE_TYPE,
                order.id().toString(),
                OrderEvent.eventType(order.status()),
                OrderEvent.SCHEMA_VERSION,
                OrderEvent.TOPIC,
                previousStatus == null ? order.placedAt() : order.updatedAt(),
                actor,
                OrderEvent.of(order, previousStatus)));
    }
}
