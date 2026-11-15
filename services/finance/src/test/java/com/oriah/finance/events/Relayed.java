package com.oriah.finance.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import com.oriah.events.EventHeaders;

/** A message exactly as a producer's relay sends it: the payload as stored, keyed by the aggregate id, with every envelope header the Inbox reads. */
final class Relayed {

    private Relayed() {
    }

    static MessageBuilder<byte[]> of(UUID eventId, String type, String aggregateType, String aggregateId, long seq, long outboxId, Instant occurredAt, String actor, String producer,
                                     int schemaVersion, byte[] body) {
        return MessageBuilder.withPayload(body)
                .setHeader(EventHeaders.EVENT_ID, eventId.toString())
                .setHeader(EventHeaders.EVENT_TYPE, type)
                .setHeader(EventHeaders.AGGREGATE_TYPE, aggregateType)
                .setHeader(EventHeaders.AGGREGATE_ID, aggregateId)
                .setHeader(EventHeaders.SEQ, Long.toString(seq))
                .setHeader(EventHeaders.OUTBOX_ID, Long.toString(outboxId))
                .setHeader(EventHeaders.OCCURRED_AT, occurredAt.toString())
                .setHeader(EventHeaders.RECORDED_AT, occurredAt.plusSeconds(1).toString())
                .setHeader(EventHeaders.ACTOR, actor)
                .setHeader(EventHeaders.SCHEMA_VERSION, Integer.toString(schemaVersion))
                .setHeader(EventHeaders.PRODUCER, producer)
                .setHeader(EventHeaders.KAFKA_KEY, aggregateId.getBytes(StandardCharsets.UTF_8));
    }

    /** The same message, as a catch-up replay delivers it: it names the replay request it answers and the outbox row before it. */
    static Message<byte[]> replayed(Message<byte[]> live, UUID request, long previousOutboxId) {
        return MessageBuilder.fromMessage(live)
                .setHeader(EventHeaders.REPLAY_REQUEST_ID, request.toString())
                .setHeader(EventHeaders.REPLAY_PREV_OUTBOX_ID, Long.toString(previousOutboxId))
                .build();
    }
}
