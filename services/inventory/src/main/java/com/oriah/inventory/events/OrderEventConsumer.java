package com.oriah.inventory.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.events.EventHeaders;
import com.oriah.events.Inbox;
import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.service.ReservationService;

import lombok.extern.slf4j.Slf4j;

/**
 * An order that is cancelled, or expires, lets go of its hold (Phase 7). Order's {@code order.cancelled} and {@code order.expired} say WHICH
 * reservation the order held at that moment ({@code reservationId}); inventory releases that id and nothing else. An event that arrives late
 * or twice can therefore never touch a newer reservation of the same order, which the order may have taken since (a payment after a lapse
 * takes a new one).
 *
 * <p>These are facts, not snapshots, so each is applied once, in any order ({@link Inbox#once}). Releasing is itself exactly-once: an
 * event for a reservation that is already released, sold or expired changes nothing, and one for a reservation this inventory never made
 * (an order whose hold was refused) is not an error. An event that names no reservation (an order that never held one, or one written
 * before orders said which) changes nothing too. A reservation id that is not an id is a broken event: refused, and parked on the
 * dead-letter topic on its first delivery.
 *
 * <p>Every other order event type is declared and ignored ({@link ProductEventConsumer#INVENTORY}): a replay of the order log must not
 * stall on a type inventory does not care about, and a type nobody declared is refused so a new one is noticed.
 */
@Configuration
@Slf4j
public class OrderEventConsumer {

    /** What every order event is about, spelled as order writes it into its outbox. */
    static final String ORDER = "order";
    static final String CANCELLED = "order.cancelled";
    static final String EXPIRED = "order.expired";

    @Bean
    public Consumer<Message<byte[]>> orderEvents(Inbox inbox, ReservationService reservations, ObjectMapper objectMapper) {
        return new Handler(inbox, reservations, objectMapper);
    }

    /** Inner handler, so {@code @Transactional} goes through the proxy (a {@code @Bean} method on this class does not). */
    static class Handler implements Consumer<Message<byte[]>> {
        private final Inbox inbox;
        private final ReservationService reservations;
        private final ObjectMapper objectMapper;

        Handler(Inbox inbox, ReservationService reservations, ObjectMapper objectMapper) {
            this.inbox = inbox;
            this.reservations = reservations;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            inbox.once(ProductEventConsumer.INVENTORY, message.getHeaders(), () -> release(message));
        }

        /** Runs only for a handled type the Inbox has not seen before. */
        private void release(Message<byte[]> message) {
            JsonNode body = read(message);
            JsonNode named = body.get("reservationId");
            if (named == null || named.isNull() || named.asText().isBlank()) {
                log.info("{} for order {} names no reservation: nothing to release", header(message, EventHeaders.EVENT_TYPE),
                        header(message, EventHeaders.AGGREGATE_ID));
                return;
            }
            UUID reservationId;
            try {
                reservationId = UUID.fromString(named.asText());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("reservationId '" + named.asText() + "' is not an id", e);
            }
            try {
                reservations.release(reservationId, "system:inventory");
            } catch (CommitmentProblem problem) {
                if (problem.status() != HttpStatus.NOT_FOUND) {
                    throw problem;
                }
                log.info("{} for order {} names reservation {}, which this inventory never made", header(message, EventHeaders.EVENT_TYPE),
                        header(message, EventHeaders.AGGREGATE_ID), reservationId);
            }
        }

        private JsonNode read(Message<byte[]> message) {
            try {
                return objectMapper.readTree(message.getPayload());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String header(Message<byte[]> message, String name) {
            Object value = message.getHeaders().get(name);
            return value == null ? null : value.toString();
        }
    }
}
