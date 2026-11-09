package com.oriah.inventory.events;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.messaging.support.MessageBuilder;

import com.oriah.events.EventHeaders;

/**
 * A product event as catalog's outbox relay publishes it on catalog.product:
 * the payload exactly as stored, keyed by the product id, with every envelope
 * header the relay sets (shared/oriah-events README, "What a published
 * message looks like").
 *
 * <p>Tests build their messages here so that what reaches the consumer is
 * what the real relay sends -- including the headers the Inbox checks
 * before it records anything, and the per-product seq it orders by.
 */
final class Relayed {

    private Relayed() {
    }

    static <T> MessageBuilder<T> envelope(MessageBuilder<T> message, UUID eventId, String eventType, UUID productId,
                                          long seq) {
        return message
                .setHeader(EventHeaders.EVENT_ID, eventId.toString())
                .setHeader(EventHeaders.EVENT_TYPE, eventType)
                .setHeader(EventHeaders.AGGREGATE_TYPE, "product")
                .setHeader(EventHeaders.AGGREGATE_ID, productId.toString())
                .setHeader(EventHeaders.SEQ, Long.toString(seq))
                .setHeader(EventHeaders.OUTBOX_ID, Long.toString(seq))
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-09T07:30:00Z")
                .setHeader(EventHeaders.RECORDED_AT, "2026-11-09T07:30:00.125Z")
                .setHeader(EventHeaders.ACTOR, "user:kc-maya")
                .setHeader(EventHeaders.SCHEMA_VERSION, "1")
                .setHeader(EventHeaders.PRODUCER, "catalog")
                .setHeader(EventHeaders.KAFKA_KEY, productId.toString().getBytes(StandardCharsets.UTF_8));
    }

    static MessageBuilder<byte[]> productEvent(UUID eventId, String eventType, UUID productId, long seq,
                                               String payload) {
        return envelope(MessageBuilder.withPayload(payload.getBytes(StandardCharsets.UTF_8)),
                eventId, eventType, productId, seq);
    }

    static MessageBuilder<byte[]> productEvent(String eventType, UUID productId, long seq, String payload) {
        return productEvent(UUID.randomUUID(), eventType, productId, seq, payload);
    }
}
