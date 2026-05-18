package com.oriah.catalog.events;

import java.util.List;
import java.util.UUID;

/**
 * Domain event: a new product was created in catalog. Published on the
 * {@code catalog.product} Kafka topic; consumed by inventory (to register
 * the new variantId in its product_index) and any future subscribers
 * (search-service for indexing, recommender-service, etc.).
 *
 * <p>The event is a snapshot of the product at create time, not a delta.
 * Consumers receive enough state to act without calling back into catalog.
 *
 * <p>Tags are passed as a {@link List} (not a {@code Set}) for stable JSON
 * serialization order — easier to read in topic dumps and consumer logs.
 *
 * @param id       The product's UUID, which doubles as the variantId until
 *                 catalog grows a Variant entity (later iteration).
 * @param title    Display title at create time.
 * @param tags     Tag slugs at create time.
 */
public record ProductCreatedEvent(
        UUID id,
        String title,
        List<String> tags
) {
    /**
     * Event-type marker used by consumers that subscribe to a single topic
     * carrying multiple event types. The convention is {@code <aggregate>.<verb>}
     * matching Eazy Bytes / Spring Cloud Stream tutorials.
     */
    public static final String TYPE = "product.created";
}
