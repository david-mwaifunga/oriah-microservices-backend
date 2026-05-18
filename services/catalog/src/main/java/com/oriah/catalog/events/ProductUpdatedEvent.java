package com.oriah.catalog.events;

import java.util.List;
import java.util.UUID;

/**
 * Domain event: an existing product was partially updated. Carries the
 * post-update snapshot (not just the deltas) — consumers don't need to
 * track previous state to react.
 *
 * <p>For partial updates where some fields stay unchanged, the unchanged
 * fields are still included in the event with their current values. Keeps
 * the event self-contained; consumers don't need a previous read.
 *
 * @see ProductCreatedEvent
 */
public record ProductUpdatedEvent(
        UUID id,
        String title,
        String description,
        List<String> tags
) {
    public static final String TYPE = "product.updated";
}
