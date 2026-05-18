package com.oriah.inventory.events;

import java.util.UUID;

/**
 * Inventory's local view of catalog's {@code product.deleted} event.
 */
public record ProductDeletedEvent(UUID id) {
    public static final String TYPE = "product.deleted";
}
