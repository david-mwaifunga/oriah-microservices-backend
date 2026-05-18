package com.oriah.inventory.events;

import java.util.List;
import java.util.UUID;

/**
 * Inventory's local view of catalog's {@code product.created} event.
 *
 * <p>Deliberately duplicated rather than pulled from a shared library
 * — services owning their own DTOs avoids coordination on every event
 * shape change. If catalog adds a new optional field, Jackson will skip
 * it here without forcing a coordinated release. Both sides agree on
 * the wire shape via a contract test in a future cycle.
 */
public record ProductCreatedEvent(
        UUID id,
        String title,
        List<String> tags
) {
    public static final String TYPE = "product.created";
}
