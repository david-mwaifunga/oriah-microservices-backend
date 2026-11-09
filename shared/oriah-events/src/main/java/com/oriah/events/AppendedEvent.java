package com.oriah.events;

import java.util.UUID;

/**
 * What the outbox gave an appended event: its position in the service's log
 * ({@code outboxId}), its identity everywhere downstream ({@code eventId})
 * and its place in its own aggregate's history ({@code seq}: 1, 2, 3 ...).
 */
public record AppendedEvent(long outboxId, UUID eventId, long seq) {
}
