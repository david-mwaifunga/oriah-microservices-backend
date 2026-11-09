package com.oriah.events;

/**
 * The envelope every relayed message carries, so a consumer can dedupe,
 * order and trace an event without parsing its body. Every envelope value is
 * a string, the one type every binder carries unchanged. The Kafka key is the
 * exception: see {@link #KAFKA_KEY}.
 */
public final class EventHeaders {

    public static final String EVENT_ID = "event-id";
    public static final String EVENT_TYPE = "event-type";
    public static final String AGGREGATE_TYPE = "aggregate-type";
    public static final String AGGREGATE_ID = "aggregate-id";
    public static final String SEQ = "seq";
    public static final String OUTBOX_ID = "outbox-id";
    public static final String OCCURRED_AT = "occurred-at";
    public static final String RECORDED_AT = "recorded-at";
    public static final String ACTOR = "actor";
    public static final String SCHEMA_VERSION = "schema-version";
    public static final String PRODUCER = "producer";

    /** On a replayed record and the end-of-replay marker: the request being answered. */
    public static final String REPLAY_REQUEST_ID = "replay-request-id";
    /**
     * On a replayed record: the outbox-id of the record before it in this
     * replay (fromOutboxId - 1 for the first), so a consumer can tell it has
     * every record of the run with no gap.
     */
    public static final String REPLAY_PREV_OUTBOX_ID = "replay-prev-outbox-id";
    /** On the end-of-replay marker: H, the newest outbox_id when the replay began. */
    public static final String THROUGH_OUTBOX_ID = "through-outbox-id";
    /** On the end-of-replay marker: the last record sent (fromOutboxId - 1 when none was). */
    public static final String LAST_OUTBOX_ID = "last-outbox-id";

    /**
     * Spring Kafka's message key header (KafkaHeaders.KEY), written as a
     * literal so the module does not need spring-kafka to compile. Kafka
     * partitions by the key, and the key is the aggregate id, so every event
     * of one order lands on one partition and is read in order.
     *
     * <p>Its value is the aggregate id as UTF-8 bytes, not a String. The
     * Kafka binder serialises keys with a byte-array serializer and passes
     * this header to it unconverted, so a String key fails every send.
     */
    public static final String KAFKA_KEY = "kafka_messageKey";

    private EventHeaders() {
    }
}
