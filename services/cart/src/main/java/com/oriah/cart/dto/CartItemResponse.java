package com.oriah.cart.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.cart.model.CartItem;

public record CartItemResponse(
        UUID id,
        UUID variantId,
        String title,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
    public static CartItemResponse from(CartItem i) {
        return new CartItemResponse(
                i.id(),
                i.variantId(),
                i.title(),
                i.quantity(),
                i.unitPrice(),
                i.unitPrice().multiply(BigDecimal.valueOf(i.quantity()))
        );
    }
}
