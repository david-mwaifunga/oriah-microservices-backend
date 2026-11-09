package com.oriah.events;

import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the outbox to Kafka, in outbox_id order, never skipping a row.
 *
 * <p>One round is one database transaction:
 * <ol>
 *   <li>lock the cursor row ({@code FOR UPDATE SKIP LOCKED}) -- if another
 *       instance of the service holds it, this one skips the round instead
 *       of waiting, so two instances never publish the same rows at
 *       once;</li>
 *   <li>read the next rows after the cursor, oldest first;</li>
 *   <li>send each to its topic, and after each send the binder accepts,
 *       move the cursor to that row;</li>
 *   <li>stop at the first send that answers false or throws -- the cursor
 *       stays on the last row that did go, and the failed row is tried
 *       again next round;</li>
 *   <li>commit.</li>
 * </ol>
 *
 * <p>This is "at least once": if the commit itself fails after a send
 * succeeded, the row is sent again next round, with the same event-id, and
 * consumers dedupe it. It is never "at most once": a row is not passed over
 * until a send of it has been accepted.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StreamOperations streams;
    private final String producer;
    private final int batchSize;

    public OutboxRelay(DataSource dataSource, PlatformTransactionManager transactionManager,
                       StreamOperations streams, String producer, int batchSize) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(transactionManager);
        this.streams = streams;
        this.producer = producer;
        this.batchSize = batchSize;
    }

    /**
     * The relay's own heartbeat: one round every {@code oriah.events.relay.delay}
     * milliseconds after the previous one ends. The first round also waits one
     * delay, so nothing publishes while the service is still starting up. A
     * round that fails is logged by the scheduler and the next one tries again.
     */
    @Scheduled(fixedDelayString = "${oriah.events.relay.delay:500}",
            initialDelayString = "${oriah.events.relay.delay:500}")
    public void poll() {
        relayBatch();
    }

    /** @return how many rows this round published (0 when another relay had the cursor) */
    public int relayBatch() {
        Integer sent = transaction.execute(status -> publishBatch());
        return sent == null ? 0 : sent;
    }

    private int publishBatch() {
        List<Long> cursor = jdbc.getJdbcOperations().queryForList(
                "SELECT last_outbox_id FROM outbox_relay_cursor WHERE relay = 'main' FOR UPDATE SKIP LOCKED",
                Long.class);
        if (cursor.isEmpty()) {
            return 0;
        }
        List<OutboxRow> rows = jdbc.query("SELECT " + OutboxRow.COLUMNS + """
                 FROM outbox
                WHERE outbox_id > :last
                ORDER BY outbox_id
                LIMIT :limit""",
                new MapSqlParameterSource("last", cursor.get(0)).addValue("limit", batchSize),
                OutboxRow::read);

        int sent = 0;
        for (OutboxRow row : rows) {
            if (!send(row)) {
                break;
            }
            jdbc.update("""
                    UPDATE outbox_relay_cursor SET last_outbox_id = :outboxId, updated_at = now()
                    WHERE relay = 'main'""", new MapSqlParameterSource("outboxId", row.outboxId()));
            sent++;
        }
        return sent;
    }

    private boolean send(OutboxRow row) {
        try {
            if (streams.send(row.topic(), row.message(producer).build())) {
                return true;
            }
            log.warn("Outbox row {} ({} {}) was not accepted by the binder for {}; it is retried next round",
                    row.outboxId(), row.eventType(), row.eventId(), row.topic());
        } catch (RuntimeException e) {
            log.warn("Outbox row {} ({} {}) could not be sent to {}; it is retried next round",
                    row.outboxId(), row.eventType(), row.eventId(), row.topic(), e);
        }
        return false;
    }
}
