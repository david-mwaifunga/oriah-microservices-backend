package com.oriah.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;

/**
 * An order, with every part of its total: {@code total = subtotal +
 * shippingAmount - discountAmount} (V4).
 *
 * @param shippingAmount what delivery was charged; absent on an order placed
 *                       before V4, where it was never recorded -- unknown,
 *                       never a claim of zero
 * @param discountAmount the discount given; absent on those same older orders
 * @param deliveryOption the option the shopper chose; absent on older orders
 */
public record OrderResponse(
        UUID id,
        String keycloakId,
        OrderStatus status,
        BigDecimal subtotal,
        BigDecimal shippingAmount,
        BigDecimal discountAmount,
        BigDecimal total,
        String currency,
        String deliveryOption,
        UUID paymentIntentId,
        UUID shippingAddressId,
        Instant placedAt,
        Instant updatedAt,
        List<OrderItemResponse> items
) {
    public static OrderResponse from(Order o) {
        return new OrderResponse(
                o.id(), o.keycloakId(), o.status(),
                o.subtotal(), o.shippingAmount(), o.discountAmount(), o.total(), o.currency(),
                o.deliveryOption(),
                o.paymentIntentId(), o.shippingAddressId(),
                o.placedAt(), o.updatedAt(),
                o.items().stream().map(OrderItemResponse::from).toList()
        );
    }
}
