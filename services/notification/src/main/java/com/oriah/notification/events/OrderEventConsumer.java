package com.oriah.notification.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.events.EventHeaders;
import com.oriah.events.Inbox;
import com.oriah.events.InboxConsumer;
import com.oriah.notification.model.NotificationLog;
import com.oriah.notification.repository.NotificationLogRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Subscribes to the {@code oriah.order} Kafka topic. Every state
 * change publishes one event there (CREATED, PAID, SHIPPED, etc.); we
 * translate to a customer-facing notification and "deliver" (log line +
 * notification_log row).
 *
 * <p>One consumer dispatches by the {@code event-type} message header.
 *
 * <p>group=notification ensures all notification instances share one
 * consumer group: each event is delivered to exactly one instance.
 *
 * <h2>Each event is sent once</h2>
 *
 * Kafka delivers at least once, so the same event can arrive twice. Each
 * event is one message to a person -- a fact, not a copy of the order's
 * state -- so it goes through {@link Inbox#once}: the record that it was sent
 * (processed_event) commits in the same transaction as its notification_log
 * row, and a second delivery finds the record and sends nothing.
 *
 * <h2>Nothing is swallowed</h2>
 *
 * An event that cannot be applied -- a type or payload version this consumer
 * does not declare, a missing or garbled envelope header, a body that breaks
 * the contract -- throws. The transaction rolls back with its record, and the
 * binder publishes it to {@code oriah.order.dlq.notification}: on the first
 * try for a refusal like these, which would fail the same way again, and after
 * its retries for anything else, such as the database being away
 * (application.yaml). Logging and carrying on, as this consumer once did,
 * lost such an event for good.
 */
@Configuration
@Slf4j
public class OrderEventConsumer {

    /**
     * Every type order publishes on oriah.order -- {@code order.<status>} for
     * each OrderStatus -- and each has a message to send, so all are handled
     * and none is ignored. Payload version 1 is the full order snapshot
     * ({@link OrderEventPayload}).
     */
    static final InboxConsumer NOTIFICATION = InboxConsumer.named("notification")
            .handles("order.created", 1)
            .handles("order.paid", 1)
            .handles("order.fulfilled", 1)
            .handles("order.shipped", 1)
            .handles("order.delivered", 1)
            .handles("order.cancelled", 1);

    @Bean
    public Consumer<Message<byte[]>> orderEvents(
            Inbox inbox,
            NotificationLogRepository repository,
            ObjectMapper objectMapper) {
        return new Handler(inbox, repository, objectMapper);
    }

    static class Handler implements Consumer<Message<byte[]>> {
        private final Inbox inbox;
        private final NotificationLogRepository repository;
        private final ObjectMapper objectMapper;

        Handler(Inbox inbox, NotificationLogRepository repository, ObjectMapper objectMapper) {
            this.inbox = inbox;
            this.repository = repository;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            inbox.once(NOTIFICATION, message.getHeaders(), () -> notify(message));
        }

        /** Runs only for a handled type the Inbox has not seen; the Inbox checked the headers. */
        private void notify(Message<byte[]> message) {
            String type = header(message, EventHeaders.EVENT_TYPE);
            OrderEventPayload payload = read(message.getPayload());

            var template = renderTemplate(type, payload);
            NotificationLog log = new NotificationLog(
                    payload.orderId(), payload.keycloakId(), type, "email", template.subject(), template.body()
            );
            repository.save(log);
            OrderEventConsumer.log.info(
                    "[EMAIL STUB] to={} subject=\"{}\" body=\"{}\"",
                    payload.keycloakId(), template.subject(), template.body());
        }

        private OrderEventPayload read(byte[] body) {
            try {
                return objectMapper.readValue(body, OrderEventPayload.class);
            } catch (IOException e) {
                throw new UncheckedIOException("order event body does not match the contract", e);
            }
        }

        /** A header as text: a String as the binder hands it over, or raw UTF-8 bytes as Kafka carries it. */
        private static String header(Message<?> message, String name) {
            Object value = message.getHeaders().get(name);
            return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
        }

        private Template renderTemplate(String type, OrderEventPayload payload) {
            // Keep templates inline for the stub. Real impl picks them
            // from a CMS / Mustache-like engine.
            return switch (type) {
                case "order.created"   -> new Template("Your Oriah order is on its way to confirmation",
                        "Thank you. We received your order " + payload.orderId() +
                        " for " + payload.total().toPlainString() + " " + payload.currency());
                case "order.paid"      -> new Template("Payment received — thank you!",
                        "Your payment for order " + payload.orderId() + " was successful.");
                case "order.fulfilled" -> new Template("Your order is being prepared",
                        "Order " + payload.orderId() + " has been fulfilled and is awaiting shipment.");
                case "order.shipped"   -> new Template("Your order has shipped",
                        "Order " + payload.orderId() + " is on its way to you.");
                case "order.delivered" -> new Template("Your order has been delivered",
                        "Order " + payload.orderId() + " was delivered. We hope you love it.");
                case "order.cancelled" -> new Template("Your order was cancelled",
                        "Order " + payload.orderId() + " has been cancelled. Any payment will be refunded.");
                // Unreachable while NOTIFICATION and this switch list the same
                // types: the Inbox refuses any other before this runs. If one
                // is ever declared without a template, fail (retry, then the
                // dead-letter topic) rather than send a vague message.
                default -> throw new IllegalStateException("no template for declared event type " + type);
            };
        }

        record Template(String subject, String body) {}
    }
}
