package com.oriah.events;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;

/**
 * One outbox row as the relay and the replayer read it, and the message it
 * becomes. Both build the message here, so a replayed record is the live
 * message again, byte for byte, with the replay's own headers added (and
 * its own key: see OutboxReplayer).
 */
record OutboxRow(long outboxId, UUID eventId, String aggregateType, String aggregateId, long seq,
                 String eventType, int schemaVersion, String topic, OffsetDateTime occurredAt,
                 OffsetDateTime recordedAt, String actor, String payload) {

    /** The select list that {@link #read} maps; the payload comes back as its stored text. */
    static final String COLUMNS = """
            outbox_id, event_id, aggregate_type, aggregate_id, seq, event_type, schema_version,
            topic, occurred_at, recorded_at, actor, payload::text AS payload""";

    static OutboxRow read(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxRow(
                rs.getLong("outbox_id"),
                rs.getObject("event_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getLong("seq"),
                rs.getString("event_type"),
                rs.getInt("schema_version"),
                rs.getString("topic"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("recorded_at", OffsetDateTime.class),
                rs.getString("actor"),
                rs.getString("payload"));
    }

    /** The message with its full envelope, still open for the caller to add headers to. */
    MessageBuilder<byte[]> message(String producer) {
        // The body is the stored text, untouched: the bytes published are
        // the bytes the outbox recorded. The key goes as bytes too: the
        // Kafka binder hands it straight to a byte-array key serializer, and
        // a String key would make every send fail.
        return MessageBuilder.withPayload(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.KAFKA_KEY, aggregateId.getBytes(StandardCharsets.UTF_8))
                .setHeader(MessageHeaders.CONTENT_TYPE, "application/json")
                .setHeader(EventHeaders.EVENT_ID, eventId.toString())
                .setHeader(EventHeaders.EVENT_TYPE, eventType)
                .setHeader(EventHeaders.AGGREGATE_TYPE, aggregateType)
                .setHeader(EventHeaders.AGGREGATE_ID, aggregateId)
                .setHeader(EventHeaders.SEQ, Long.toString(seq))
                .setHeader(EventHeaders.OUTBOX_ID, Long.toString(outboxId))
                .setHeader(EventHeaders.OCCURRED_AT, occurredAt.toInstant().toString())
                .setHeader(EventHeaders.RECORDED_AT, recordedAt.toInstant().toString())
                .setHeader(EventHeaders.ACTOR, actor)
                .setHeader(EventHeaders.SCHEMA_VERSION, Integer.toString(schemaVersion))
                .setHeader(EventHeaders.PRODUCER, producer);
    }
}
