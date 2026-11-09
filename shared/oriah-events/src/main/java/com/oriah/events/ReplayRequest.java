package com.oriah.events;

import java.util.UUID;

/**
 * A late consumer asking one producer to send its outbox again.
 *
 * <p>On the wire it is this JSON, sent to {@value #TOPIC}:
 * {@code {"requestId": "<uuid>", "producer": "order", "consumer": "finance", "fromOutboxId": 0}}.
 * Every producer service reads that topic; only the one named in
 * {@code producer} answers, by sending its outbox rows from
 * {@code fromOutboxId} onwards to {@link #destination()}, then an
 * end-of-replay marker of type {@value #COMPLETED}.
 *
 * <p>Refused at construction when it could not be answered safely: no id
 * (a duplicate could not be told apart), no producer, a consumer name that
 * is not a valid topic suffix ({@link ConsumerName}), or a negative
 * position.
 *
 * @param requestId    identifies this request; a redelivered copy is answered once
 * @param producer     the service asked, its {@code oriah.events.producer}
 * @param consumer     the service asking; names the topic the replay goes to
 * @param fromOutboxId the first outbox position wanted; 0 means from the start
 */
public record ReplayRequest(UUID requestId, String producer, String consumer, long fromOutboxId) {

    /** Where requests are sent. */
    public static final String TOPIC = "oriah.outbox.replay-requests";

    /** The event-type header of the end-of-replay marker. */
    public static final String COMPLETED = "outbox.replay.completed";

    public ReplayRequest {
        if (requestId == null) {
            throw new IllegalArgumentException("a replay request needs a requestId");
        }
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("a replay request needs the producer it asks");
        }
        ConsumerName.require(consumer);
        if (fromOutboxId < 0) {
            throw new IllegalArgumentException("a replay request's fromOutboxId is 0 or more, was " + fromOutboxId);
        }
    }

    /** The consumer's own replay topic, {@code oriah.replay.<consumer>}. */
    public String destination() {
        return "oriah.replay." + consumer;
    }
}
