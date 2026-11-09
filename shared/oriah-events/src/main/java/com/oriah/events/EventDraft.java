package com.oriah.events;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * One fact a service is about to append to its outbox, before the outbox
 * gives it an id, a position and a sequence number.
 *
 * <p>Refused at construction, before any database work, when it lacks what
 * the log must always carry:
 * <ul>
 *   <li>{@code occurredAt} -- when it happened in the real world. Required,
 *       never defaulted to "now": a sale keyed in a day late happened
 *       yesterday, and the log must say so.</li>
 *   <li>{@code actor} -- who did it: {@code user:<keycloak subject>} for a
 *       person, {@code system:<name>} for the service acting on its own
 *       (a reconciler, a scheduled job).</li>
 *   <li>{@code payload} -- what it says; written as JSON by {@link EventJson}.
 *       Every amount in it states its currency, and one event never mixes
 *       currencies.</li>
 * </ul>
 * Names are checked against their column sizes so an over-long id fails
 * here with a sentence, not later as a database error.
 *
 * @param aggregateType the kind of thing the event is about, e.g. "order"
 * @param aggregateId   which one, e.g. the order id
 * @param eventType     e.g. "order.paid"
 * @param schemaVersion the payload's contract version, from 1
 * @param topic         where the relay publishes it; must be one of the
 *                      service's {@code oriah.events.topics}
 */
public record EventDraft(
        String aggregateType,
        String aggregateId,
        String eventType,
        int schemaVersion,
        String topic,
        Instant occurredAt,
        String actor,
        Object payload) {

    private static final Pattern ACTOR = Pattern.compile("(user|system):\\S.*");

    public EventDraft {
        requireName("aggregateType", aggregateType, 64);
        requireName("aggregateId", aggregateId, 64);
        requireName("eventType", eventType, 128);
        requireName("topic", topic, 128);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion starts at 1, was " + schemaVersion);
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required: when the fact happened is never guessed");
        }
        requireName("actor", actor, 128);
        if (!ACTOR.matcher(actor).matches()) {
            throw new IllegalArgumentException(
                    "actor must be 'user:<subject>' or 'system:<name>', was '" + actor + "'");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
    }

    private static void requireName(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " is " + value.length() + " characters; its column holds " + maxLength);
        }
    }
}
