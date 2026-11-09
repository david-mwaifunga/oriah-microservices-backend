package com.oriah.events;

import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The "applied" marker in processed_event: one row per (consumer, event).
 * Shared by the Inbox and by the replayer, which dedupes replay requests the
 * same way under the consumer name {@code replay-requests}.
 *
 * <p>Always written inside the caller's transaction, so it commits with the
 * effect it records or rolls back with it.
 */
final class ProcessedEvents {

    private final NamedParameterJdbcTemplate jdbc;

    ProcessedEvents(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true when this is the first time; false when the marker already
     *         exists (the event was applied before). A concurrent delivery of
     *         the same event waits here until the first one commits or rolls
     *         back, then gets its answer -- so exactly one of them applies it.
     */
    boolean markFirst(String consumer, UUID eventId) {
        return jdbc.update("""
                INSERT INTO processed_event (consumer, event_id) VALUES (:consumer, :eventId)
                ON CONFLICT DO NOTHING""",
                new MapSqlParameterSource("consumer", consumer).addValue("eventId", eventId)) == 1;
    }
}
