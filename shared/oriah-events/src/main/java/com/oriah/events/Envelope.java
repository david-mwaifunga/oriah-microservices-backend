package com.oriah.events;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * The envelope headers a consumer needs, read and checked before anything
 * is written. A message missing one, or carrying one that does not parse,
 * is refused with an IllegalArgumentException naming the header -- so it is
 * retried and then dead-lettered, never half-applied.
 *
 * <p>A header value may be a String (what the relay sets, and what Spring
 * Cloud Stream hands a consumer) or raw bytes (how Kafka itself carries
 * every header); bytes are read as UTF-8.
 *
 * <p>The readers below are shared with {@link CatchUp}, which checks the
 * replay headers the same way.
 */
record Envelope(UUID eventId, String eventType, String aggregateType, String aggregateId, long seq,
                int schemaVersion) {

    /** The column sizes of processed_aggregate's key, so an over-long id fails here, not as a database error. */
    private static final int MAX_ID = 64;

    static Envelope from(Map<String, ?> headers) {
        return new Envelope(
                uuid(headers, EventHeaders.EVENT_ID),
                text(headers, EventHeaders.EVENT_TYPE, Integer.MAX_VALUE),
                text(headers, EventHeaders.AGGREGATE_TYPE, MAX_ID),
                text(headers, EventHeaders.AGGREGATE_ID, MAX_ID),
                positive(headers, EventHeaders.SEQ),
                (int) positive(headers, EventHeaders.SCHEMA_VERSION));
    }

    /** The header as text, or null when it is absent. */
    static String optional(Map<String, ?> headers, String name) {
        Object value = headers.get(name);
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : value == null ? null : value.toString();
    }

    static String text(Map<String, ?> headers, String name, int maxLength) {
        String text = optional(headers, name);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("header '" + name + "' is missing or blank");
        }
        if (text.length() > maxLength) {
            throw new IllegalArgumentException("header '" + name + "' is " + text.length()
                    + " characters; at most " + maxLength + " are kept");
        }
        return text;
    }

    static UUID uuid(Map<String, ?> headers, String name) {
        String text = text(headers, name, Integer.MAX_VALUE);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("header '" + name + "' is not a UUID: '" + text + "'", e);
        }
    }

    static long positive(Map<String, ?> headers, String name) {
        String text = text(headers, name, Integer.MAX_VALUE);
        long number;
        try {
            number = name.equals(EventHeaders.SCHEMA_VERSION) ? Integer.parseInt(text) : Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("header '" + name + "' is not a whole number: '" + text + "'", e);
        }
        if (number < 1) {
            throw new IllegalArgumentException("header '" + name + "' starts at 1, was " + number);
        }
        return number;
    }

    /** Any whole number: a replay position may be 0, or -1 for a replay asked from 0. */
    static long whole(Map<String, ?> headers, String name) {
        String text = text(headers, name, Integer.MAX_VALUE);
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("header '" + name + "' is not a whole number: '" + text + "'", e);
        }
    }
}
