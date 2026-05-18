package com.oriah.inventory.events;

import java.util.List;
import java.util.UUID;

/**
 * Inventory's local view of catalog's {@code product.updated} event. See
 * {@link ProductCreatedEvent} for why this is duplicated instead of
 * shared via a library.
 */
public record ProductUpdatedEvent(
        UUID id,
        String title,
        String description,
        List<String> tags
) {
    public static final String TYPE = "product.updated";
}
