package com.oriah.order.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Caller (the checkout flow) submits a snapshot of what to buy.
 * order-service trusts the supplied prices/titles — validation that
 * the prices match the live catalog happens in the checkout
 * orchestrator (Phase 2). Today this service is just the persister.
 */
public record PlaceOrderRequest(
        @NotNull UUID shippingAddressId,
        @Size(min = 3, max = 3) String currency,
        @NotEmpty @Valid List<LineItem> items
) {
    public record LineItem(
            @NotNull UUID variantId,
            @NotNull String title,
            @NotNull Integer quantity,
            @NotNull java.math.BigDecimal unitPrice
    ) {}
}
