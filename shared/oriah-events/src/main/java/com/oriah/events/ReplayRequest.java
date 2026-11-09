package com.oriah.events;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;

/**
 * A late consumer asking one producer to send its outbox again -- or the
 * producer's own note to itself to carry on with a replay it has begun.
 *
 * <p>On the wire it is this JSON, sent to {@value #TOPIC}:
 * {@code {"requestId": "<uuid>", "producer": "order", "consumer": "finance", "fromOutboxId": 1}}.
 * Every producer service reads that topic; only the one named in
 * {@code producer} answers, by sending its outbox rows from
 * {@code fromOutboxId} onwards to {@link #destination()}, then an
 * end-of-replay marker of type {@value #COMPLETED}.
 *
 * <p><b>Steps and continuations.</b> The producer does not answer the whole
 * request in one go: one delivery sends at most
 * {@code oriah.events.replay.chunk-size} rows (see {@link OutboxReplayer}).
 * Why: a delivery must finish inside the time Kafka allows a listener between
 * two polls, and a whole history could take far longer. When rows are left,
 * the producer ends the step by sending this same request back to
 * {@value #TOPIC} with two more fields, and answers that the same way:
 * <ul>
 *   <li>{@code throughOutboxId} -- H, the newest outbox row when the replay
 *       began; the replay ends there;</li>
 *   <li>{@code afterOutboxId} -- the last row already sent; the next step
 *       starts after it.</li>
 * </ul>
 * A consumer's own request has neither; a continuation has both.
 *
 * <p>Refused at construction when it could not be answered safely: no id
 * (a duplicate could not be told apart), no producer, a consumer name that
 * is not a valid topic suffix ({@link ConsumerName}), a negative position,
 * or continuation fields that do not fit together.
 *
 * @param requestId       identifies this replay; every step of it, and every record sent, carries it
 * @param producer        the service asked, its {@code oriah.events.producer}
 * @param consumer        the service asking; names the topic the replay goes to
 * @param fromOutboxId    the first outbox position wanted; 0 means from the start
 * @param throughOutboxId on a continuation, H: the last outbox position this replay sends; null otherwise
 * @param afterOutboxId   on a continuation, the last outbox position already sent; null otherwise
 */
public record ReplayRequest(UUID requestId, String producer, String consumer, long fromOutboxId,
                            Long throughOutboxId, Long afterOutboxId) {

    /** Where requests (and the producers' continuations) are sent. */
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
        if ((throughOutboxId == null) != (afterOutboxId == null)) {
            throw new IllegalArgumentException("a replay request's throughOutboxId and afterOutboxId come together "
                    + "(a producer's continuation has both, a consumer's request neither), but request " + requestId
                    + " has throughOutboxId " + throughOutboxId + " and afterOutboxId " + afterOutboxId);
        }
        if (afterOutboxId != null && afterOutboxId < fromOutboxId - 1) {
            // It would send rows before the first one the consumer asked for.
            throw new IllegalArgumentException("a replay request's afterOutboxId is fromOutboxId - 1 or more ("
                    + (fromOutboxId - 1) + "), was " + afterOutboxId);
        }
        if (throughOutboxId != null && throughOutboxId < afterOutboxId) {
            // H is where the replay ends; a step cannot start beyond it.
            throw new IllegalArgumentException("a replay request's throughOutboxId is afterOutboxId or more ("
                    + afterOutboxId + "), was " + throughOutboxId);
        }
    }

    /** A consumer's request: from a position, no continuation fields. */
    public ReplayRequest(UUID requestId, String producer, String consumer, long fromOutboxId) {
        this(requestId, producer, consumer, fromOutboxId, null, null);
    }

    /** True for a producer's continuation of a replay it has begun; false for a consumer's request. */
    boolean continuation() {
        return afterOutboxId != null;
    }

    /**
     * What the producer records once it has answered this step, so a copy of
     * it delivered again is ignored (in processed_event, as the Inbox
     * records an event).
     *
     * <p>The first step is the request itself: its requestId. A continuation
     * is named after the request and the position it continues from -- a
     * name-based UUID of the two -- so the same continuation sent twice (a
     * step answered again after a crash sends its continuation again) has
     * the same id and is answered once, while the next step has an id of its
     * own. A name-based UUID (version 3) can never equal a request id, which
     * is random (version 4).
     */
    UUID stepId() {
        if (!continuation()) {
            return requestId;
        }
        return UUID.nameUUIDFromBytes(("oriah-replay-step:" + requestId + ":" + afterOutboxId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** The continuation that carries this replay on after {@code lastSent}, ending at {@code through}. */
    ReplayRequest continueAfter(long through, long lastSent) {
        return new ReplayRequest(requestId, producer, consumer, fromOutboxId, through, lastSent);
    }

    /**
     * The request as it travels on {@value #TOPIC}: its fields as JSON (the
     * continuation fields only on a continuation), keyed by the request id.
     *
     * <p>Why the key: Kafka puts a record on a partition by its key, and
     * keeps the order only within a partition. With one key, the consumer's
     * request and every continuation of it land on one partition of the
     * requests topic, in the order they were sent, so the steps of a replay
     * are answered one after another. It goes as bytes, the only key type
     * the Kafka binder accepts (see {@link EventHeaders#KAFKA_KEY}).
     */
    Message<byte[]> message() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId.toString());
        body.put("producer", producer);
        body.put("consumer", consumer);
        body.put("fromOutboxId", fromOutboxId);
        if (continuation()) {
            body.put("throughOutboxId", throughOutboxId);
            body.put("afterOutboxId", afterOutboxId);
        }
        return MessageBuilder.withPayload(EventJson.write(body).getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.KAFKA_KEY, requestId.toString().getBytes(StandardCharsets.UTF_8))
                .setHeader(MessageHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /** The consumer's own replay topic, {@code oriah.replay.<consumer>}. */
    public String destination() {
        return "oriah.replay." + consumer;
    }
}
