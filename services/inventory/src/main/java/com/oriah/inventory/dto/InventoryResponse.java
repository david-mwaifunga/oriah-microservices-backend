package com.oriah.inventory.dto;

import java.util.UUID;

import com.oriah.inventory.model.Inventory;

/**
 * Wire-format response for a single variant's inventory. Includes the
 * computed {@code available} field so callers (catalog's PDP, the cart
 * service) don't have to subtract themselves.
 */
public record InventoryResponse(
        UUID variantId,
        int onHand,
        int reserved,
        int available
) {

    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(
                inventory.variantId(),
                inventory.onHand(),
                inventory.reserved(),
                inventory.available()
        );
    }
}
