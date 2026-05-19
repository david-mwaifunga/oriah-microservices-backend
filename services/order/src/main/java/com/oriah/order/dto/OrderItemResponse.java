package com.oriah.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.order.model.OrderItem;

public record OrderItemResponse(
        UUID id,
        UUID variantId,
        String title,
        Integer quantity,
        BigDecimal unitPrice
) {
    public static OrderItemResponse from(OrderItem i) {
        return new OrderItemResponse(i.id(), i.variantId(), i.title(), i.quantity(), i.unitPrice());
    }
}
