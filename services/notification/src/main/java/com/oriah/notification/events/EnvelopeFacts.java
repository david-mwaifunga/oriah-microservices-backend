package com.oriah.notification.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.springframework.messaging.Message;

import com.oriah.events.EventHeaders;

/**
 * What notification reads from an event's envelope, in one place: which event it is, what it is about, where it stands in that thing's history
 * ({@code seq}: the outbox's own count per order), when it happened and when its producer wrote it down.
 *
 * <p>The shared module's own envelope reader is not public, and it does not need the two times; notification does, because whether a message is still
 * worth sending is judged from the producer's {@code recorded-at}, never from the moment Kafka happened to deliver it.
 *
 * <p>A header that is missing or cannot be read is an {@link IllegalArgumentException} that names the header and never repeats its value: the
 * binding does not retry that exception, so the event goes straight to the dead-letter topic, and the message of the exception travels with it.
 */
public record EnvelopeFacts(UUID eventId, String eventType, UUID aggregateId, long seq, Instant occurredAt, Instant recordedAt) {

    public static EnvelopeFacts from(Message<?> message) {
        return new EnvelopeFacts(
                id(message, EventHeaders.EVENT_ID),
                text(message, EventHeaders.EVENT_TYPE),
                id(message, EventHeaders.AGGREGATE_ID),
                number(message, EventHeaders.SEQ),
                time(message, EventHeaders.OCCURRED_AT),
                time(message, EventHeaders.RECORDED_AT));
    }

    private static UUID id(Message<?> message, String header) {
        try {
            return UUID.fromString(text(message, header));
        } catch (IllegalArgumentException e) {
            throw unreadable(header, "an id");
        }
    }

    private static long number(Message<?> message, String header) {
        try {
            return Long.parseLong(text(message, header));
        } catch (NumberFormatException e) {
            throw unreadable(header, "a whole number");
        }
    }

    private static Instant time(Message<?> message, String header) {
        try {
            return Instant.parse(text(message, header));
        } catch (DateTimeParseException e) {
            throw unreadable(header, "a time");
        }
    }

    /** A header as text, whether the binder handed it over as text or (Kafka) as UTF-8 bytes. */
    private static String text(Message<?> message, String header) {
        Object value = message.getHeaders().get(header);
        String text = switch (value) {
            case String s -> s;
            case byte[] b -> new String(b, StandardCharsets.UTF_8);
            case null, default -> null;
        };
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("the event has no '" + header + "' header");
        }
        return text.trim();
    }

    private static IllegalArgumentException unreadable(String header, String what) {
        return new IllegalArgumentException("the event's '" + header + "' header is not " + what);
    }
}
