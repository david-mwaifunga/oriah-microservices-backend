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
        List<OrderItemResponse> items,
        UUID deliveryTownId,
        String deliveryPayer,
        /** When the hold on the pieces lapses unless the shopper pays or submits a payment reference; absent on an order with no hold. */
        Instant holdExpiresAt,
        /** What delivery would have cost, when staff made it free for this order (the delivery revenue given up); absent when it was not waived. */
        BigDecimal deliveryWaivedAmount,
        /** Who waived it, {@code user:<keycloak subject>}; staff's views only (see {@link #forStaff}), absent when not waived. */
        String deliveryWaivedBy,
        /** Why: staff's own words, kept for staff; the shopper's views leave it out. */
        String deliveryWaiverReason,
        /** When it was waived; staff's views only. */
        Instant deliveryWaivedAt
) {
    /**
     * The order as its shopper may read it. A waiver shows as the amount the shopper was spared ({@code deliveryWaivedAmount}) and the lower total; who gave
     * it and why are staff's notes, never in an answer to the shopper (the claim's, the placement's, the order's, the list).
     */
    public static OrderResponse from(Order o) {
        return build(o, false);
    }

    /** The order as staff read it: everything {@link #from} has, and who waived delivery, why and when. */
    public static OrderResponse forStaff(Order o) {
        return build(o, true);
    }

    private static OrderResponse build(Order o, boolean staff) {
        return new OrderResponse(
                o.id(), o.keycloakId(), o.status(),
                o.subtotal(), o.shippingAmount(), o.discountAmount(), o.total(), o.currency(),
                o.deliveryOption(),
                o.paymentIntentId(), o.shippingAddressId(),
                o.placedAt(), o.updatedAt(),
                o.items().stream().map(OrderItemResponse::from).toList(),
                o.deliveryTownId(), o.deliveryPayer(), o.holdExpiresAt(),
                o.deliveryWaivedAmount(),
                staff ? o.deliveryWaivedBy() : null,
                staff ? o.deliveryWaiverReason() : null,
                staff ? o.deliveryWaivedAt() : null
        );
    }
}
