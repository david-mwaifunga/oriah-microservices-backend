package com.oriah.events;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lets a consumer that starts after its producers have been running catch up
 * on everything they published before it existed (finance, Phase 10).
 *
 * <p>Why it is needed: Kafka keeps messages only for a while, so a new
 * consumer reading the live topic from the earliest message still kept has
 * missed the oldest ones. The producer's outbox keeps them for good, and a
 * replay request makes it send them again, to the consumer's own topic
 * {@code oriah.replay.<consumer>}.
 *
 * <p><b>On start</b>, for each producer in
 * {@code oriah.events.inbox.catch-up.producers}:
 * <ul>
 *   <li>no outbox_catch_up row yet: insert one (a new request id,
 *       replayed_through 0) and ask for the whole log;</li>
 *   <li>a row not yet completed: give it a NEW request id and ask again from
 *       replayed_through + 1 -- whatever the last run left unfinished;</li>
 *   <li>a completed row: nothing.</li>
 * </ul>
 * Every request asks from replayed_through + 1, the first one too (from 1,
 * which is the whole log: outbox ids start at 1). That way the first record
 * of every replay names replayed_through as the record before it, and the
 * chain below starts on its first record. (A request "from 0" would name -1,
 * and nothing would ever match.)
 *
 * <p>The live binding starts at the same moment, in a new group, from the
 * earliest offset. There is no cutover: facts are applied once whichever
 * stream brings them first, and projections skip any snapshot older than
 * the one they hold, so the two streams may interleave in any order.
 *
 * <p><b>Each replayed record</b> goes through {@link #once} or
 * {@link #onceInOrder} -- the same Inbox method the consumer uses live --
 * and, in the same transaction, moves replayed_through to the record's
 * outbox-id, but only if
 * <ul>
 *   <li>its replay-request-id is the row's current request (a record left
 *       over from an earlier request still applies, but cannot move it);
 *       and</li>
 *   <li>its replay-prev-outbox-id equals replayed_through: the record
 *       continues an unbroken chain.</li>
 * </ul>
 * So replayed_through always means "every row up to here is applied". A
 * record that fails goes to the dead-letter topic and its change never commits: the
 * chain breaks there and replayed_through stops, while later records still
 * apply (harmlessly, once) but cannot move it. Live deliveries go straight
 * to the Inbox and never touch it.
 *
 * <p><b>The end-of-replay marker</b> completes the catch-up
 * (completed_through = H, completed_at = now) only if its last-outbox-id
 * equals replayed_through -- nothing missing behind it. Otherwise the
 * catch-up stays open, and the next start asks again from where the
 * unbroken chain ended.
 *
 * <p>One consumer per service: outbox_catch_up keeps one row per producer,
 * so only the consumer named in {@code oriah.events.inbox.catch-up.consumer}
 * may use this.
 */
public class CatchUp implements ApplicationListener<ApplicationReadyEvent>, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(CatchUp.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StreamOperations streams;
    private final Inbox inbox;
    private final String consumer;
    private final Set<String> producers;
    private ApplicationContext context;

    public CatchUp(DataSource dataSource, PlatformTransactionManager transactionManager, StreamOperations streams,
                   Inbox inbox, String consumer, Set<String> producers) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(transactionManager);
        this.streams = streams;
        this.inbox = inbox;
        this.consumer = ConsumerName.require(consumer);
        this.producers = new TreeSet<>(producers);
    }

    @Override
    public void setApplicationContext(ApplicationContext context) {
        this.context = context;
    }

    /**
     * The service has started. A context Spring Cloud Stream starts for a
     * binder of its own also announces "ready" to this one; only the
     * service's own start counts.
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (event.getApplicationContext() == context) {
            producers.forEach(this::requestReplay);
        }
    }

    /**
     * Applies one record of the consumer's replay topic as a FACT, through
     * {@link Inbox#once}; or, for the end-of-replay marker, completes the
     * catch-up if nothing is missing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void once(InboxConsumer consumer, Map<String, ?> headers, Runnable effect) {
        replayed(consumer, headers, () -> inbox.once(consumer, headers, effect));
    }

    /**
     * Applies one record of the consumer's replay topic as a PROJECTION'S
     * snapshot, through {@link Inbox#onceInOrder}; or, for the end-of-replay
     * marker, completes the catch-up if nothing is missing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onceInOrder(InboxConsumer consumer, Map<String, ?> headers, Runnable effect) {
        replayed(consumer, headers, () -> inbox.onceInOrder(consumer, headers, effect));
    }

    private void replayed(InboxConsumer caller, Map<String, ?> headers, Runnable throughInbox) {
        if (!consumer.equals(caller.name())) {
            throw new IllegalArgumentException("CatchUp is configured for consumer '" + consumer
                    + "' (oriah.events.inbox.catch-up.consumer), not '" + caller.name()
                    + "': outbox_catch_up keeps one position per producer, for one consumer");
        }
        if (ReplayRequest.COMPLETED.equals(Envelope.optional(headers, EventHeaders.EVENT_TYPE))) {
            complete(Marker.from(headers));
            return;
        }
        // Read before anything is written: a record whose replay headers are
        // missing or garbled is refused with no marker, like a bad envelope.
        Position position = Position.from(headers);
        throughInbox.run();
        advance(position);
    }

    private void advance(Position position) {
        int moved = jdbc.update("""
                UPDATE outbox_catch_up SET replayed_through = :outboxId
                WHERE producer = :producer AND request_id = :requestId AND replayed_through = :previous""",
                new MapSqlParameterSource("producer", position.producer())
                        .addValue("requestId", position.requestId())
                        .addValue("outboxId", position.outboxId())
                        .addValue("previous", position.previous()));
        if (moved == 0) {
            log.debug("Replayed outbox {} from '{}' (request {}) applied; it does not continue the current "
                    + "request's chain, so the catch-up position stays", position.outboxId(), position.producer(),
                    position.requestId());
        }
    }

    private void complete(Marker marker) {
        MapSqlParameterSource row = new MapSqlParameterSource("producer", marker.producer())
                .addValue("requestId", marker.requestId())
                .addValue("through", marker.through())
                .addValue("last", marker.last());
        int completed = jdbc.update("""
                UPDATE outbox_catch_up SET completed_through = :through, completed_at = now()
                WHERE producer = :producer AND request_id = :requestId AND replayed_through = :last
                  AND completed_at IS NULL""", row);
        if (completed == 1) {
            log.info("Catch-up of '{}' from '{}' is complete through outbox {}", consumer, marker.producer(),
                    marker.through());
            return;
        }
        List<Map<String, Object>> current = jdbc.queryForList("""
                SELECT request_id, replayed_through, completed_at FROM outbox_catch_up
                WHERE producer = :producer""", row);
        if (!current.isEmpty() && marker.requestId().equals(current.get(0).get("request_id"))
                && current.get(0).get("completed_at") == null) {
            log.warn("Replay {} from '{}' ended at outbox {}, but '{}' has every row only up to {}: a record "
                    + "failed. The catch-up stays open; the next start asks again from there.",
                    marker.requestId(), marker.producer(), marker.last(), consumer,
                    current.get(0).get("replayed_through"));
        }
    }

    private void requestReplay(String producer) {
        UUID requestId = UUID.randomUUID();
        Long from = transaction.execute(status -> nextRequest(producer, requestId));
        if (from == null) {
            log.debug("Catch-up of '{}' from '{}' is complete; nothing to ask", consumer, producer);
            return;
        }
        // Sent after the row commits, so a record answering this request can
        // never arrive before the row names it as current.
        send(new ReplayRequest(requestId, producer, consumer, from));
    }

    /** @return where the new request starts, or null when this producer's catch-up is already complete */
    private Long nextRequest(String producer, UUID requestId) {
        MapSqlParameterSource row = new MapSqlParameterSource("producer", producer).addValue("requestId", requestId);
        jdbc.update("""
                INSERT INTO outbox_catch_up (producer, request_id) VALUES (:producer, :requestId)
                ON CONFLICT DO NOTHING""", row);
        // Locked, so two instances starting at once take turns; the later one's request is the current one.
        Map<String, Object> current = jdbc.queryForMap("""
                SELECT replayed_through, completed_at FROM outbox_catch_up WHERE producer = :producer
                FOR UPDATE""", row);
        if (current.get("completed_at") != null) {
            return null;
        }
        jdbc.update("UPDATE outbox_catch_up SET request_id = :requestId WHERE producer = :producer", row);
        return ((Number) current.get("replayed_through")).longValue() + 1;
    }

    /**
     * A send the binder refuses or throws on fails the start: the service
     * must not run believing it asked. That covers a broker that does not
     * take the request only because sends are synchronous, which is why
     * CatchUp refuses to start without them ({@link SyncSends}).
     *
     * <p>The request is keyed by its request id ({@link ReplayRequest#message()}),
     * as the producer keys every continuation of it, so all the steps of one
     * replay sit on one partition of the requests topic, in order.
     */
    private void send(ReplayRequest request) {
        Message<byte[]> message = request.message();
        boolean accepted;
        try {
            accepted = streams.send(ReplayRequest.TOPIC, message);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not ask '" + request.producer() + "' for a replay (request "
                    + request.requestId() + "); the next start asks again", e);
        }
        if (!accepted) {
            throw new IllegalStateException("The binder did not accept the replay request " + request.requestId()
                    + " to '" + request.producer() + "'; the next start asks again");
        }
        log.info("Asked '{}' to replay its outbox from {} to {} (request {})", request.producer(),
                request.fromOutboxId(), request.destination(), request.requestId());
    }

    /** Where a replayed record sits in its replay: the producer, the request, itself and the record before it. */
    private record Position(String producer, UUID requestId, long outboxId, long previous) {

        static Position from(Map<String, ?> headers) {
            return new Position(
                    Envelope.text(headers, EventHeaders.PRODUCER, Integer.MAX_VALUE),
                    Envelope.uuid(headers, EventHeaders.REPLAY_REQUEST_ID),
                    Envelope.positive(headers, EventHeaders.OUTBOX_ID),
                    Envelope.whole(headers, EventHeaders.REPLAY_PREV_OUTBOX_ID));
        }
    }

    /** The end-of-replay marker: H, and the last record the replay actually sent. */
    private record Marker(String producer, UUID requestId, long through, long last) {

        static Marker from(Map<String, ?> headers) {
            return new Marker(
                    Envelope.text(headers, EventHeaders.PRODUCER, Integer.MAX_VALUE),
                    Envelope.uuid(headers, EventHeaders.REPLAY_REQUEST_ID),
                    Envelope.whole(headers, EventHeaders.THROUGH_OUTBOX_ID),
                    Envelope.whole(headers, EventHeaders.LAST_OUTBOX_ID));
        }
    }
}
