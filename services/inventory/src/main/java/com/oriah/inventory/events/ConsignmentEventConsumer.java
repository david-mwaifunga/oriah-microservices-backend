package com.oriah.inventory.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.events.CatchUp;
import com.oriah.events.EventHeaders;
import com.oriah.events.Inbox;
import com.oriah.inventory.service.StockService;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns procurement's {@code consignment.received} into stock: one lot per slice, with its cost stamped on it.
 *
 * <p>Two bindings, one effect. {@code consignmentEvents} reads the live topic {@code oriah.procurement};
 * {@code consignmentReplay} reads {@code oriah.replay.inventory}, where procurement answers the replay inventory asked
 * for when it started, so every receipt that happened before inventory existed still becomes lots, exactly once.
 * Both go through the same Inbox bookkeeping ({@code processed_event}, {@code processed_aggregate}), so an event is
 * applied once whichever stream brings it, and a redelivery changes nothing.
 *
 * <h2>Nothing is repaired, nothing is swallowed</h2>
 *
 * An event that breaks a promise it makes (a snapshot whose slices do not add up to its landed total, a line id that is
 * not the envelope's, a receipt time that is not the event's) is refused: the transaction rolls back with its
 * bookkeeping and the module publishes it to {@code oriah.procurement.dlq.inventory} on its first delivery. Stock made
 * from a wrong event would be a wrong cost for the life of the lot.
 *
 * <h2>Order does not matter</h2>
 *
 * Oldest-first selling is a function of the lots' receipt times, not of the order their events arrive in: a receipt
 * that arrives after newer stock was sold re-cuts those sales to have taken it first ({@code StockService}). So the
 * live and replay streams may interleave in any order.
 *
 * <p>Every OTHER event type procurement ever writes on this topic must be declared by the Inbox declaration
 * ({@code ProductEventConsumer#INVENTORY}) before procurement emits it: a replay delivers every row of the outbox, and
 * an undeclared type is refused and keeps the catch-up open.
 */
@Configuration
@Slf4j
public class ConsignmentEventConsumer {

    /** What every consignment event is about: a consignment line, spelled as procurement writes it. */
    static final String CONSIGNMENT_LINE = "consignment_line";
    static final String RECEIVED = "consignment.received";

    @Bean
    public Consumer<Message<byte[]>> consignmentEvents(Inbox inbox, StockService stock, ObjectMapper objectMapper) {
        Applier applier = new Applier(stock, objectMapper);
        return new Live(inbox, applier);
    }

    @Bean
    public Consumer<Message<byte[]>> consignmentReplay(CatchUp catchUp, StockService stock, ObjectMapper objectMapper) {
        Applier applier = new Applier(stock, objectMapper);
        return new Replay(catchUp, applier);
    }

    /** Inner handlers, so {@code @Transactional} goes through the proxy (a {@code @Bean} method on this class does not). */
    static class Live implements Consumer<Message<byte[]>> {
        private final Inbox inbox;
        private final Applier applier;

        Live(Inbox inbox, Applier applier) {
            this.inbox = inbox;
            this.applier = applier;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            inbox.onceInOrder(ProductEventConsumer.INVENTORY, message.getHeaders(), () -> applier.apply(message));
        }
    }

    static class Replay implements Consumer<Message<byte[]>> {
        private final CatchUp catchUp;
        private final Applier applier;

        Replay(CatchUp catchUp, Applier applier) {
            this.catchUp = catchUp;
            this.applier = applier;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            catchUp.onceInOrder(ProductEventConsumer.INVENTORY, message.getHeaders(), () -> applier.apply(message));
        }
    }

    /** What runs once an event is known to be new: read it, check it against its envelope, make the stock. */
    static class Applier {
        private final StockService stock;
        private final ObjectMapper objectMapper;

        Applier(StockService stock, ObjectMapper objectMapper) {
            this.stock = stock;
            this.objectMapper = objectMapper;
        }

        void apply(Message<byte[]> message) {
            String type = header(message, EventHeaders.EVENT_TYPE);
            if (!RECEIVED.equals(type)) {
                // Unreachable while the declaration and this switch list the same types.
                throw new IllegalStateException("no handling for declared event type " + type);
            }
            ReceiptSnapshot snapshot = read(message);
            String aggregateId = header(message, EventHeaders.AGGREGATE_ID);
            if (snapshot.consignmentLineId() == null || !snapshot.consignmentLineId().toString().equals(aggregateId)) {
                throw new IllegalArgumentException("snapshot of line " + snapshot.consignmentLineId()
                        + " arrived in an envelope for line " + aggregateId);
            }
            Instant occurredAt = instant(header(message, EventHeaders.OCCURRED_AT), EventHeaders.OCCURRED_AT);
            snapshot.validate();
            if (!occurredAt.equals(snapshot.receivedAtInstant())) {
                throw new IllegalArgumentException("receivedAt " + snapshot.receivedAt() + " is not the time the event says it happened ("
                        + occurredAt + ")");
            }
            long outboxId = whole(header(message, EventHeaders.OUTBOX_ID), EventHeaders.OUTBOX_ID);
            String actor = header(message, EventHeaders.ACTOR);
            UUID eventId = UUID.fromString(header(message, EventHeaders.EVENT_ID));
            log.info("Consuming consignment.received line={} variant={} quantity={}", snapshot.consignmentLineId(),
                    snapshot.variantId(), snapshot.quantity());
            stock.applyReceipt(snapshot, new StockService.EventContext(eventId, outboxId, actor, occurredAt));
        }

        private ReceiptSnapshot read(Message<byte[]> message) {
            try {
                return objectMapper.readValue(message.getPayload(), ReceiptSnapshot.class);
            } catch (IOException e) {
                throw new UncheckedIOException("consignment event body does not match ReceiptSnapshot", e);
            }
        }

        private static Instant instant(String value, String header) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(header + " is not a timestamp: " + value);
            }
        }

        private static long whole(String value, String header) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(header + " is not a whole number: " + value);
            }
        }

        /** A header as text: a String as the binder hands it over, or raw UTF-8 bytes as Kafka carries it. */
        private static String header(Message<?> message, String name) {
            Object value = message.getHeaders().get(name);
            if (value == null) {
                throw new IllegalArgumentException("header " + name + " is missing");
            }
            return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
        }
    }
}
