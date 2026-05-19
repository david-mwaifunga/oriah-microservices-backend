package com.oriah.order.events;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.order.model.OrderStatus;

/**
 * Wire shape published on the {@code oriah.order} Kafka topic for every
 * order state change. One event type, the {@code newStatus} field is
 * what consumers branch on. {@code previousStatus} is included for
 * audit / debugging consumers.
 */
public record OrderEvent(
        UUID orderId,
        String keycloakId,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        BigDecimal total,
        String currency
) {
    public static final String TOPIC = "oriah.order";
}
