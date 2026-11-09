package com.oriah.notification.events;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import com.oriah.events.EventHeaders;

/**
 * An order event as order's outbox relay publishes it on oriah.order: the
 * payload exactly as stored, keyed by the order id, with every envelope
 * header the relay sets (shared/oriah-events README, "What a published
 * message looks like").
 *
 * <p>Tests build their messages here so that what reaches the consumer is
 * what the real relay sends -- including the headers the Inbox checks
 * before it records anything.
 */
final class Relayed {

    private Relayed() {
    }

    static MessageBuilder<byte[]> orderEvent(UUID eventId, String eventType, UUID orderId, long seq, byte[] payload) {
        return MessageBuilder.withPayload(payload)
                .setHeader(EventHeaders.EVENT_ID, eventId.toString())
                .setHeader(EventHeaders.EVENT_TYPE, eventType)
                .setHeader(EventHeaders.AGGREGATE_TYPE, "order")
                .setHeader(EventHeaders.AGGREGATE_ID, orderId.toString())
                .setHeader(EventHeaders.SEQ, Long.toString(seq))
                .setHeader(EventHeaders.OUTBOX_ID, Long.toString(seq))
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-09T07:30:00Z")
                .setHeader(EventHeaders.RECORDED_AT, "2026-11-09T07:30:00.125Z")
                .setHeader(EventHeaders.ACTOR, "user:kc-alice")
                .setHeader(EventHeaders.SCHEMA_VERSION, "1")
                .setHeader(EventHeaders.PRODUCER, "order")
                .setHeader(EventHeaders.KAFKA_KEY, orderId.toString().getBytes(StandardCharsets.UTF_8));
    }

    static Message<byte[]> orderEvent(String eventType, UUID orderId, long seq, byte[] payload) {
        return orderEvent(UUID.randomUUID(), eventType, orderId, seq, payload).build();
    }
}
