package com.oriah.notification.events;

import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.notification.model.NotificationLog;
import com.oriah.notification.repository.NotificationLogRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Subscribes to the {@code oriah.order} Kafka topic. Every state
 * change publishes one event there (CREATED, PAID, SHIPPED, etc.); we
 * translate to a customer-facing notification and "deliver" (log line +
 * notification_log row).
 *
 * <p>One consumer dispatches by the {@code event-type} message header
 * — same pattern as inventory's product-event consumer.
 *
 * <p>group=notification ensures all notification instances share one
 * consumer group: each event is delivered to exactly one instance.
 */
@Configuration
@Slf4j
public class OrderEventConsumer {

    private static final String HEADER_EVENT_TYPE = "event-type";

    @Bean
    public Consumer<Message<byte[]>> orderEvents(
            NotificationLogRepository repository,
            ObjectMapper objectMapper) {
        return new Handler(repository, objectMapper);
    }

    static class Handler implements Consumer<Message<byte[]>> {
        private final NotificationLogRepository repository;
        private final ObjectMapper objectMapper;

        Handler(NotificationLogRepository repository, ObjectMapper objectMapper) {
            this.repository = repository;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            Object typeHeader = message.getHeaders().get(HEADER_EVENT_TYPE);
            String type = typeHeader == null ? "order.unknown" : typeHeader.toString();
            try {
                JsonNode payload = objectMapper.readTree(message.getPayload());
                String keycloakId = payload.path("keycloakId").asText("unknown");
                String orderIdText = payload.path("orderId").asText(null);
                java.util.UUID orderId = orderIdText == null ? null : java.util.UUID.fromString(orderIdText);

                var template = renderTemplate(type, payload);
                NotificationLog log = new NotificationLog(
                        orderId, keycloakId, type, "email", template.subject(), template.body()
                );
                repository.save(log);
                NotificationLog.class.getSimpleName(); // touch class so it's not stripped by graalvm hints
                OrderEventConsumer.log.info(
                        "[EMAIL STUB] to={} subject=\"{}\" body=\"{}\"",
                        keycloakId, template.subject(), template.body());
            } catch (Exception e) {
                OrderEventConsumer.log.error("Failed to process event-type={} payload-length={}",
                        type, message.getPayload().length, e);
            }
        }

        private Template renderTemplate(String type, JsonNode payload) {
            // Keep templates inline for the stub. Real impl picks them
            // from a CMS / Mustache-like engine.
            return switch (type) {
                case "order.created"   -> new Template("Your Oriah order is on its way to confirmation",
                        "Thank you. We received your order " + payload.path("orderId").asText() +
                        " for " + payload.path("total").asText() + " " + payload.path("currency").asText());
                case "order.paid"      -> new Template("Payment received — thank you!",
                        "Your payment for order " + payload.path("orderId").asText() + " was successful.");
                case "order.fulfilled" -> new Template("Your order is being prepared",
                        "Order " + payload.path("orderId").asText() + " has been fulfilled and is awaiting shipment.");
                case "order.shipped"   -> new Template("Your order has shipped",
                        "Order " + payload.path("orderId").asText() + " is on its way to you.");
                case "order.delivered" -> new Template("Your order has been delivered",
                        "Order " + payload.path("orderId").asText() + " was delivered. We hope you love it.");
                case "order.cancelled" -> new Template("Your order was cancelled",
                        "Order " + payload.path("orderId").asText() + " has been cancelled. Any payment will be refunded.");
                default -> new Template("Order update", "Order update for type=" + type);
            };
        }

        record Template(String subject, String body) {}
    }
}
