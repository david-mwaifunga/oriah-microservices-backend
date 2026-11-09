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
 * position, to the asking consumer's own topic.
 *
 * <p>Why a consumer asks: one added late (finance, months after orders
 * began) or one that lost its copy needs every fact, and Kafka keeps
 * messages only for a while. The outbox keeps them for good.
 *
 * <p>For each request read from {@link ReplayRequest#TOPIC}:
 * <ol>
 *   <li>a request naming another producer is ignored -- every producer
 *       reads the topic;</li>
 *   <li>a malformed one is refused (it throws, so the binder retries it and
 *       then dead-letters it);</li>
 *   <li>a request already answered is ignored: its requestId is recorded
 *       in processed_event under the consumer name {@value #REQUESTS_CONSUMER},
 *       exactly as the Inbox records an event;</li>
 *   <li>H = the newest outbox_id now. Every row from the asked position
 *       through H is sent, oldest first, to {@code oriah.replay.<consumer>}:
 *       the live message again plus {@code replay-request-id} and
 *       {@code replay-prev-outbox-id}, keyed by the request id instead of
 *       the aggregate id (see {@link #key});</li>
 *   <li>then one end-of-replay marker ({@value ReplayRequest#COMPLETED})
 *       with {@code through-outbox-id} = H and {@code last-outbox-id}, under
 *       the same key.</li>
 * </ol>
 *
 * <p>Why H is safe to stop at: appends are serialised by the outbox's lock,
 * so a row not yet committed always has a higher id than every committed
 * one. Every row up to H is therefore already there, and anything appended
 * during the replay is above H and goes out live as usual.
 *
 * <p>The whole answer is one database transaction, so the "answered" record
 * commits only after the marker has been sent. If a send fails (or the
 * service dies part-way), the record rolls back and the redelivered request
 * replays the whole range again; the consumer applies duplicates once. A
 * second copy delivered at the same moment waits on the first one's record
 * and is then ignored. The relay's cursor and the live topics are never
 * touched.
 */
public class OutboxReplayer {

    /** The Inbox consumer name replay requests are deduped under. */
    static final String REQUESTS_CONSUMER = "replay-requests";

    private static final Logger log = LoggerFactory.getLogger(OutboxReplayer.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StreamOperations streams;
    private final ProcessedEvents processed;
    private final String producer;
    private final int pageSize;

    /**
     * @param pageSize how many rows are read from the outbox at a time; the
     *                 whole range is still sent in one answer
     */
    public OutboxReplayer(DataSource dataSource, PlatformTransactionManager transactionManager,
                          StreamOperations streams, String producer, int pageSize) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(transactionManager);
        this.streams = streams;
        this.processed = new ProcessedEvents(jdbc);
        this.producer = producer;
        this.pageSize = pageSize;
    }

    /** The listener: one request as it arrived on {@link ReplayRequest#TOPIC}. */
    public void handle(Message<byte[]> message) {
        WireRequest wire = WireRequest.read(message.getPayload());
        if (wire.producer() != null && !wire.producer().equals(producer)) {
            log.debug("Replay request {} is for producer '{}', not '{}'; ignored", wire.requestId(), wire.producer(),
                    producer);
            return;
        }
        ReplayRequest request = wire.validated();
        transaction.executeWithoutResult(status -> replay(request));
    }

    private void replay(ReplayRequest request) {
        if (!processed.markFirst(REQUESTS_CONSUMER, request.requestId())) {
            log.info("Replay request {} from '{}' was already answered; this copy is ignored",
                    request.requestId(), request.consumer());
            return;
        }
        long through = jdbc.getJdbcOperations().queryForObject("SELECT coalesce(max(outbox_id), 0) FROM outbox",
                Long.class);
        log.info("Replaying outbox {}..{} to {} for request {}", request.fromOutboxId(), through,
                request.destination(), request.requestId());

        long previous = request.fromOutboxId() - 1;
        List<OutboxRow> page;
        do {
            page = jdbc.query("SELECT " + OutboxRow.COLUMNS + """
                     FROM outbox
                    WHERE outbox_id > :previous AND outbox_id <= :through
                    ORDER BY outbox_id
                    LIMIT :limit""",
                    new MapSqlParameterSource("previous", previous)
                            .addValue("through", through)
                            .addValue("limit", pageSize),
                    OutboxRow::read);
            for (OutboxRow row : page) {
                send(request, "outbox row " + row.outboxId(), row.message(producer)
                        .setHeader(EventHeaders.KAFKA_KEY, key(request))
                        .setHeader(EventHeaders.REPLAY_REQUEST_ID, request.requestId().toString())
                        .setHeader(EventHeaders.REPLAY_PREV_OUTBOX_ID, Long.toString(previous))
                        .build());
                previous = row.outboxId();
            }
        } while (page.size() == pageSize);

        send(request, "the end-of-replay marker", marker(request, through, previous));
        log.info("Replay for request {} sent through outbox {} (last row {})", request.requestId(), through, previous);
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

    /** A send the binder refuses or throws on stops the whole answer, so its record rolls back. */
    private void send(ReplayRequest request, String what, Message<byte[]> message) {
        boolean accepted;
        try {
            accepted = streams.send(request.destination(), message);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Replay for request " + request.requestId() + " stopped at " + what
                    + ": it could not be sent to " + request.destination(), e);
        }
        if (!accepted) {
            throw new IllegalStateException("Replay for request " + request.requestId() + " stopped at " + what
                    + ": the binder did not accept it for " + request.destination());
        }
    }

    /**
     * The request as it arrived, every field optional, so that one meant for
     * another producer can be recognised and ignored before it is judged.
     */
    record WireRequest(String requestId, String producer, String consumer, Long fromOutboxId) {

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
            return new ReplayRequest(uuid(), producer, consumer, fromOutboxId);
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
