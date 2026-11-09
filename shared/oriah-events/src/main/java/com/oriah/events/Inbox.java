package com.oriah.events;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies each received event once in effect, inside the consumer's own
 * transaction.
 *
 * <p>Why inside it: the marker that says "applied" (processed_event) and
 * the effect it records must commit together. Kafka delivers at least once
 * -- after a crash or a rebalance the same event comes again -- so a
 * consumer that applied the effect but lost the marker would apply it twice,
 * and one that kept the marker but lost the effect would never apply it. So
 * both methods refuse to run with no transaction open ({@code MANDATORY}).
 *
 * <p>Pick one per consumer, never a mix:
 * <ul>
 *   <li>{@link #once} -- a FACT consumer: each event is a thing that
 *       happened (a sale, a payment), applied exactly once in any order.</li>
 *   <li>{@link #onceInOrder} -- a PROJECTION: each event is a full snapshot
 *       of its aggregate (a product as it now is), and only the newest
 *       matters, so an older one arriving late is skipped.</li>
 * </ul>
 *
 * <p>Before anything is written, the envelope headers are checked and the
 * event's type, payload version and aggregate type are matched against the
 * consumer's {@link InboxConsumer declaration}. Anything wrong throws, with
 * no marker, and the module's dead-letter handling takes over: the message is tried
 * again or not, as the binding's retryable-exceptions say, then published to
 * the binding's dead-letter topic, and not committed until that topic has it
 * (see {@link DeadLetterCustomizer}).
 */
public class Inbox {

    private final NamedParameterJdbcTemplate jdbc;
    private final ProcessedEvents processed;

    public Inbox(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.processed = new ProcessedEvents(jdbc);
    }

    /**
     * Runs the effect unless this consumer has already applied the event. An
     * ignored type is marked without running the effect. An effect that
     * throws rolls its marker back with it, so the retry applies it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void once(InboxConsumer consumer, Map<String, ?> headers, Runnable effect) {
        Envelope envelope = Envelope.from(headers);
        boolean applies = consumer.applies(envelope);
        if (processed.markFirst(consumer.name(), envelope.eventId()) && applies) {
            effect.run();
        }
    }

    /**
     * As {@link #once}, and also skips a snapshot older than the newest one
     * already applied to the same aggregate.
     *
     * <p>processed_aggregate keeps, per aggregate, the highest seq applied.
     * Its row is never deleted, so it outlives the projection's own row: a
     * product deleted at seq 3 stays deleted when its seq 2 update turns up
     * late.
     *
     * <p>Two deliveries for one aggregate can run at once (seq 2 and seq 3
     * of the same product). Each first inserts a zero row if none exists
     * ({@code ON CONFLICT DO NOTHING}: for a product nobody has seen, the
     * second insert waits for the first transaction, then does nothing), then
     * locks that row ({@code FOR UPDATE}): for a product already known, the
     * second waits there for the first to commit and then reads the seq it
     * wrote. Either way they apply one after the other, and the older one,
     * whichever runs second, is skipped. A plain read could not do that: it
     * would see the old seq while the other transaction was still open.
     *
     * <p>A skipped event still gets its marker: a newer state is already
     * applied, so for this consumer the older event is done with. An ignored
     * type does not move the seq -- its snapshot was not applied, so a
     * handled one before it must not be skipped on its account.
     *
     * <p>The seq is kept under the aggregate-type header, so that header must
     * be right: an event whose aggregate-type is not the one the consumer
     * declared for its type is refused before anything is written (see
     * {@link InboxConsumer#handles}). Accepted, its snapshot would be applied
     * while its seq was recorded under another aggregate, and a later, older
     * snapshot of the real one would then overwrite the newer state.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onceInOrder(InboxConsumer consumer, Map<String, ?> headers, Runnable effect) {
        Envelope envelope = Envelope.from(headers);
        boolean applies = consumer.applies(envelope);
        if (!processed.markFirst(consumer.name(), envelope.eventId()) || !applies) {
            return;
        }
        MapSqlParameterSource aggregate = new MapSqlParameterSource()
                .addValue("consumer", consumer.name())
                .addValue("aggregateType", envelope.aggregateType())
                .addValue("aggregateId", envelope.aggregateId())
                .addValue("seq", envelope.seq());
        jdbc.update("""
                INSERT INTO processed_aggregate (consumer, aggregate_type, aggregate_id, last_seq)
                VALUES (:consumer, :aggregateType, :aggregateId, 0)
                ON CONFLICT DO NOTHING""", aggregate);
        long lastSeq = jdbc.queryForObject("""
                SELECT last_seq FROM processed_aggregate
                WHERE consumer = :consumer AND aggregate_type = :aggregateType AND aggregate_id = :aggregateId
                FOR UPDATE""", aggregate, Long.class);
        if (envelope.seq() <= lastSeq) {
            return;
        }
        effect.run();
        jdbc.update("""
                UPDATE processed_aggregate SET last_seq = :seq
                WHERE consumer = :consumer AND aggregate_type = :aggregateType AND aggregate_id = :aggregateId""",
                aggregate);
    }
}
