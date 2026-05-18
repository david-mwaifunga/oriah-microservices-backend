package com.oriah.inventory.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for initial inventory seeding via
 * {@code POST /api/v1/admin/inventory}. Both fields required.
 */
public record InventoryCreateRequest(
        @NotNull(message = "variantId must not be null")
        UUID variantId,

        @NotNull(message = "onHand must not be null")
        @Min(value = 0, message = "onHand must be >= 0")
        Integer onHand
) {}
