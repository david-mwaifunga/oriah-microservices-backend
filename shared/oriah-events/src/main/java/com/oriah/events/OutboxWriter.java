package com.oriah.events;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends one fact to the service's outbox, inside the caller's own
 * transaction.
 *
 * <p>Why inside the caller's transaction: the fact and the change it
 * describes must commit together or not at all. If the order row commits,
 * its "order.created" is in the log; if it rolls back, so does the event,
 * and nothing is ever published about an order that does not exist. So an
 * append with no transaction open is refused ({@code MANDATORY}).
 *
 * <p>Each append, in order: refuse a topic the service has not declared;
 * take the outbox's advisory lock (held to the end of the transaction, so
 * appends from different transactions queue up and ids follow commit
 * order); number the event within its aggregate (1, 2, 3 ...); insert.
 *
 * <p>How to call it: make your own change, flush it (so a constraint
 * violation fails first, before the lock is taken), then append as the
 * transaction's last write, so the lock is held as briefly as possible.
 */
public class OutboxWriter {

    /**
     * The advisory lock every append takes: the letters "ORIAHOUT" read as
     * one number. An advisory lock is scoped to one database and each
     * service has its own, so one constant serves every service. Nothing
     * else in a service may use this number for a lock of its own.
     */
    static final long OUTBOX_LOCK_KEY = 0x4F52_4941_484F_5554L;

    private final NamedParameterJdbcTemplate jdbc;
    private final Set<String> topics;

    public OutboxWriter(DataSource dataSource, Set<String> topics) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.topics = Set.copyOf(topics);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AppendedEvent append(EventDraft draft) {
        if (!topics.contains(draft.topic())) {
            throw new IllegalArgumentException("topic '" + draft.topic() + "' is not one of this service's "
                    + "oriah.events.topics " + new TreeSet<>(topics));
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("aggregateType", draft.aggregateType())
                .addValue("aggregateId", draft.aggregateId());

        // Wait for any other appending transaction to finish, and hold every
        // later one back until this one commits or rolls back (Postgres lets
        // the lock go at the end of the transaction, never earlier). So ids
        // are handed out in commit order, and the relay's single "published
        // up to N" can never step over a row that commits late. It also makes
        // the max(seq) + 1 below safe against a concurrent append.
        jdbc.query("SELECT pg_advisory_xact_lock(:key)",
                new MapSqlParameterSource("key", OUTBOX_LOCK_KEY), rs -> null);

        // The next number in this aggregate's own history. The UNIQUE
        // (aggregate_type, aggregate_id, seq) constraint is the backstop.
        long seq = jdbc.queryForObject("""
                SELECT coalesce(max(seq), 0) + 1 FROM outbox
                WHERE aggregate_type = :aggregateType AND aggregate_id = :aggregateId""", params, Long.class);

        UUID eventId = UUID.randomUUID();
        params.addValue("eventId", eventId)
                .addValue("seq", seq)
                .addValue("eventType", draft.eventType())
                .addValue("schemaVersion", draft.schemaVersion())
                .addValue("topic", draft.topic())
                .addValue("occurredAt", OffsetDateTime.ofInstant(draft.occurredAt(), ZoneOffset.UTC))
                .addValue("actor", draft.actor())
                .addValue("payload", EventJson.write(draft.payload()));
        long outboxId = jdbc.queryForObject("""
                INSERT INTO outbox (event_id, aggregate_type, aggregate_id, seq, event_type, schema_version,
                                    topic, occurred_at, actor, payload)
                VALUES (:eventId, :aggregateType, :aggregateId, :seq, :eventType, :schemaVersion,
                        :topic, :occurredAt, :actor, CAST(:payload AS json))
                RETURNING outbox_id""", params, Long.class);
        return new AppendedEvent(outboxId, eventId, seq);
    }
}
