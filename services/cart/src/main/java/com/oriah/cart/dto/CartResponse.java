package com.oriah.cart.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.oriah.cart.model.Cart;

public record CartResponse(
        UUID id,
        Instant updatedAt,
        BigDecimal subtotal,
        List<CartItemResponse> items
) {
    public static CartResponse from(Cart c) {
        return new CartResponse(
                c.id(),
                c.updatedAt(),
                c.subtotal(),
                c.items().stream().map(CartItemResponse::from).toList()
        );
    }
}
