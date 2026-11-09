package com.oriah.events;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;

/**
 * Answers replay requests: sends this service's outbox again, from a
 * position, to the asking consumer's own topic -- one bounded step per
 * delivery.
 *
 * <p>Why a consumer asks: one added late (finance, months after orders
 * began) or one that lost its copy needs every fact, and Kafka keeps
 * messages only for a while. The outbox keeps them for good.
 *
 * <p><b>Why steps.</b> Kafka gives a listener a fixed time between two polls
 * ({@code max.poll.interval.ms}); a listener that takes longer is presumed
 * dead, its partitions go to another instance, and the request it was
 * answering is delivered again -- where it would take just as long, for
 * ever. A whole history has no upper bound, so one delivery never answers
 * all of it: it sends at most {@code chunkSize} rows
 * ({@code oriah.events.replay.chunk-size}) and then hands the rest of the
 * replay to a <em>continuation</em>, a request it sends back to
 * {@link ReplayRequest#TOPIC} and answers when Kafka delivers it, like any
 * other. The module refuses to start unless a whole step fits the poll
 * interval, however slowly Kafka answers within its timeouts (see
 * {@link ReplayStepTime}).
 *
 * <p>For each request (or continuation) read from {@link ReplayRequest#TOPIC}:
 * <ol>
 *   <li>a request naming another producer is ignored -- every producer
 *       reads the topic;</li>
 *   <li>a malformed one is refused: it throws, and the module's dead-letter
 *       handling publishes it to the listener's dead-letter topic, at least
 *       once, keeping it uncommitted until that topic has it (see
 *       {@link DeadLetterCustomizer});</li>
 *   <li>a step already answered is ignored: its id
 *       ({@link ReplayRequest#stepId()} -- the requestId for the first step)
 *       is recorded in processed_event under the consumer name
 *       {@value #REQUESTS_CONSUMER}, exactly as the Inbox records an
 *       event;</li>
 *   <li>the first step fixes H = the newest outbox_id now, and starts after
 *       {@code fromOutboxId - 1}; a continuation carries both with it;</li>
 *   <li>up to {@code chunkSize} rows after that position and up to H are
 *       sent, oldest first, to {@code oriah.replay.<consumer>}: the live
 *       message again plus {@code replay-request-id} and
 *       {@code replay-prev-outbox-id} (the row before it in this replay, so
 *       the chain runs unbroken across steps), keyed by the request id
 *       instead of the aggregate id (see {@link #key});</li>
 *   <li>if rows up to H remain, a continuation goes to
 *       {@link ReplayRequest#TOPIC} (H, and the last row sent), keyed by the
 *       request id too, so every step of a replay is on one partition, in
 *       order; otherwise the end-of-replay marker
 *       ({@value ReplayRequest#COMPLETED}) goes to the consumer, with
 *       {@code through-outbox-id} = H and {@code last-outbox-id};</li>
 *   <li>the step's record commits.</li>
 * </ol>
 *
 * <p>Why H is safe to stop at: appends are serialised by the outbox's lock,
 * so a row not yet committed always has a higher id than every committed
 * one. Every row up to H is therefore already there, and anything appended
 * during the replay is above H and goes out live as usual.
 *
 * <p>Each step is one database transaction, and every send in it waits
 * until Kafka has the record (sync sends), so the step's record commits
 * only after its rows and its continuation or marker are on Kafka. If a
 * send fails, or the service dies before the commit, the record rolls back
 * and Kafka delivers the step again: its rows are sent again (the consumer
 * applies each once) and so is its continuation, which the step id then
 * makes harmless -- the copy is answered once. A second copy delivered at
 * the same moment waits on the first one's record and is then ignored. The
 * relay's cursor and the live topics are never touched.
 *
 * <p>Two costs to know about. Every step leaves its id in processed_event
 * for good (the table is append-only), so a replay through H adds about
 * H / chunkSize rows to it. And nothing here knows that a replay has been
 * overtaken: when a consumer restarts mid-replay, {@link CatchUp} asks again
 * under a new request id, while the old request's continuations keep
 * answering, step by step, up to their own H. The consumer applies every
 * record once, so nothing goes wrong, but each such restart can cost up to
 * one more whole replay of sends.
 */
public class OutboxReplayer {

    /** The Inbox consumer name replay steps are deduped under. */
    static final String REQUESTS_CONSUMER = "replay-requests";

    private static final Logger log = LoggerFactory.getLogger(OutboxReplayer.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StreamOperations streams;
    private final ProcessedEvents processed;
    private final String producer;
    private final int chunkSize;

    /**
     * @param chunkSize the most rows one step sends ({@code oriah.events.replay.chunk-size}); 1 or more
     */
    public OutboxReplayer(DataSource dataSource, PlatformTransactionManager transactionManager,
                          StreamOperations streams, String producer, int chunkSize) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(transactionManager);
        this.streams = streams;
        this.processed = new ProcessedEvents(jdbc);
        this.producer = producer;
        this.chunkSize = chunkSize;
    }

    /** The most rows one step sends. */
    int chunkSize() {
        return chunkSize;
    }

    /** The listener: one request or continuation as it arrived on {@link ReplayRequest#TOPIC}. */
    public void handle(Message<byte[]> message) {
        WireRequest wire = WireRequest.read(message.getPayload());
        if (wire.producer() != null && !wire.producer().equals(producer)) {
            log.debug("Replay request {} is for producer '{}', not '{}'; ignored", wire.requestId(), wire.producer(),
                    producer);
            return;
        }
        ReplayRequest request = wire.validated();
        transaction.executeWithoutResult(status -> step(request));
    }

    private void step(ReplayRequest request) {
        if (!processed.markFirst(REQUESTS_CONSUMER, request.stepId())) {
            log.info("Replay request {} from '{}': the step after outbox {} was already answered; this copy is "
                    + "ignored", request.requestId(), request.consumer(), after(request));
            return;
        }
        long through = request.continuation() ? request.throughOutboxId() : newestOutboxId();
        long after = after(request);
        if (!request.continuation()) {
            log.info("Replaying outbox {}..{} to {} for request {}, up to {} rows a step", request.fromOutboxId(),
                    through, request.destination(), request.requestId(), chunkSize);
        }

        // One row more than a step sends: if it is there, rows remain up to H.
        List<OutboxRow> rows = jdbc.query("SELECT " + OutboxRow.COLUMNS + """
                 FROM outbox
                WHERE outbox_id > :after AND outbox_id <= :through
                ORDER BY outbox_id
                LIMIT :limit""",
                new MapSqlParameterSource("after", after)
                        .addValue("through", through)
                        .addValue("limit", chunkSize + 1),
                OutboxRow::read);
        boolean more = rows.size() > chunkSize;

        long previous = after;
        for (OutboxRow row : more ? rows.subList(0, chunkSize) : rows) {
            send(request, "outbox row " + row.outboxId(), request.destination(), row.message(producer)
                    .setHeader(EventHeaders.KAFKA_KEY, key(request))
                    .setHeader(EventHeaders.REPLAY_REQUEST_ID, request.requestId().toString())
                    .setHeader(EventHeaders.REPLAY_PREV_OUTBOX_ID, Long.toString(previous))
                    .build());
            previous = row.outboxId();
        }

        if (more) {
            send(request, "the continuation after outbox " + previous, ReplayRequest.TOPIC,
                    request.continueAfter(through, previous).message());
            log.info("Replay for request {} sent outbox {}..{}; it continues after {} in a step of its own",
                    request.requestId(), after + 1, previous, previous);
        } else {
            send(request, "the end-of-replay marker", request.destination(), marker(request, through, previous));
            log.info("Replay for request {} sent through outbox {} (last row {})", request.requestId(), through,
                    previous);
        }
    }

    /** Where the step starts: after the last row already sent, or before the first row asked for. */
    private static long after(ReplayRequest request) {
        return request.continuation() ? request.afterOutboxId() : request.fromOutboxId() - 1;
    }

    private long newestOutboxId() {
        return jdbc.getJdbcOperations().queryForObject("SELECT coalesce(max(outbox_id), 0) FROM outbox", Long.class);
    }

    /**
     * The end of the run. The body repeats the headers for whoever reads the
     * topic by eye; consumers read the headers.
     */
    private Message<byte[]> marker(ReplayRequest request, long through, long last) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", request.requestId().toString());
        body.put("producer", producer);
        body.put("throughOutboxId", through);
        body.put("lastOutboxId", last);
        return MessageBuilder.withPayload(EventJson.write(body).getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.KAFKA_KEY, key(request))
                .setHeader(MessageHeaders.CONTENT_TYPE, "application/json")
                .setHeader(EventHeaders.EVENT_TYPE, ReplayRequest.COMPLETED)
                .setHeader(EventHeaders.PRODUCER, producer)
                .setHeader(EventHeaders.REPLAY_REQUEST_ID, request.requestId().toString())
                .setHeader(EventHeaders.THROUGH_OUTBOX_ID, Long.toString(through))
                .setHeader(EventHeaders.LAST_OUTBOX_ID, Long.toString(last))
                .build();
    }

    /**
     * The key of every record of one replay, and of its marker: the request
     * id, as bytes (the Kafka binder takes a key only as bytes).
     *
     * <p>Why not the live key, the aggregate id: Kafka puts a record on a
     * partition by its key, and the consumer's replay topic may well have
     * several -- the consumer's binder creates it with one per consumer
     * thread. Keyed by aggregate, one replay would be split across them and
     * read out of order, the consumer's gap-free chain would stop at the
     * first record that came early, and the catch-up could never complete.
     * One key for the whole run puts it on one partition, in the order it
     * was sent, however many the topic has.
     */
    private static byte[] key(ReplayRequest request) {
        return request.requestId().toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A send the binder refuses or throws on stops the step, so its record rolls back and Kafka delivers it again. */
    private void send(ReplayRequest request, String what, String destination, Message<byte[]> message) {
        boolean accepted;
        try {
            accepted = streams.send(destination, message);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Replay for request " + request.requestId() + " stopped at " + what
                    + ": it could not be sent to " + destination, e);
        }
        if (!accepted) {
            throw new IllegalStateException("Replay for request " + request.requestId() + " stopped at " + what
                    + ": the binder did not accept it for " + destination);
        }
    }

    /**
     * The request as it arrived, every field optional, so that one meant for
     * another producer can be recognised and ignored before it is judged.
     */
    record WireRequest(String requestId, String producer, String consumer, Long fromOutboxId,
                       Long throughOutboxId, Long afterOutboxId) {

        static WireRequest read(byte[] body) {
            try {
                return EventJson.mapper().readValue(body, WireRequest.class);
            } catch (JacksonException e) {
                throw new IllegalArgumentException("replay request is not readable JSON: "
                        + new String(body, StandardCharsets.UTF_8), e);
            }
        }

        ReplayRequest validated() {
            if (fromOutboxId == null) {
                throw new IllegalArgumentException("replay request " + requestId + " has no fromOutboxId");
            }
            return new ReplayRequest(uuid(), producer, consumer, fromOutboxId, throughOutboxId, afterOutboxId);
        }

        private UUID uuid() {
            if (requestId == null) {
                return null;
            }
            try {
                return UUID.fromString(requestId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("replay request's requestId is not a UUID: '" + requestId + "'", e);
            }
        }
    }
}
