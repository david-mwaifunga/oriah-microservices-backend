package com.oriah.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;

public record OrderResponse(
        UUID id,
        String keycloakId,
        OrderStatus status,
        BigDecimal subtotal,
        BigDecimal total,
        String currency,
        UUID paymentIntentId,
        UUID shippingAddressId,
        Instant placedAt,
        Instant updatedAt,
        List<OrderItemResponse> items
) {
    public static OrderResponse from(Order o) {
        return new OrderResponse(
                o.id(), o.keycloakId(), o.status(),
                o.subtotal(), o.total(), o.currency(),
                o.paymentIntentId(), o.shippingAddressId(),
                o.placedAt(), o.updatedAt(),
                o.items().stream().map(OrderItemResponse::from).toList()
        );
    }
}
