package com.oriah.finance.events;

import java.time.Instant;
import java.util.UUID;

/**
 * What the envelope of one event says, apart from its body: who sent it, which thing it is about, when it happened and when its producer recorded it. Built once from
 * the message headers by the consumer; an applier reads nothing else from the transport.
 *
 * @param eventId       the event's identity everywhere (finance dedupes on it)
 * @param type          for example {@code order.paid}
 * @param aggregateType which kind of thing it is about, as the producer wrote it
 * @param aggregateId   which one
 * @param seq           the event's number in its aggregate's history
 * @param outboxId      the producer's outbox position (null when unknown)
 * @param occurredAt    when it happened in the real world
 * @param recordedAt    when its producer wrote it down
 * @param actor         {@code user:<subject>} or {@code system:<name>}
 * @param producer      the service that wrote it
 * @param schemaVersion the payload version the producer wrote it in
 */
public record EventContext(UUID eventId, String type, String aggregateType, String aggregateId, long seq, Long outboxId, Instant occurredAt, Instant recordedAt,
                           String actor, String producer, int schemaVersion) {
}
