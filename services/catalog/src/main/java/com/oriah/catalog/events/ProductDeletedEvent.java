package com.oriah.catalog.events;

import java.util.UUID;

/**
 * Domain event: a product was permanently removed from catalog. Inventory
 * uses this to remove the corresponding product_index row; downstream
 * services may use it to invalidate caches, deactivate search documents,
 * etc.
 *
 * <p>Only the id is carried because the consumer doesn't need any other
 * field to act ("remove what you have for this id").
 */
public record ProductDeletedEvent(UUID id) {
    public static final String TYPE = "product.deleted";
}
