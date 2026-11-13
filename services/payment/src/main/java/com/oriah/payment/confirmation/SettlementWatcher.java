package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.payment.events.ConfirmationEvents;

import lombok.extern.slf4j.Slf4j;

/**
 * Payment is the detector of money with no outcome (Phase 7).
 *
 * <p>A confirmation stays PENDING until order says it paid the sale or could not ({@link SettlementOutcomes}). If order was down, or a message went
 * astray, nothing else would ever notice: the money is recorded and nothing has acted on it. So while a confirmation has waited longer than the
 * settlement window ({@code oriah.sales.settlement-stuck-after}, five minutes) the watcher ANNOUNCES IT AGAIN: the same confirmation id in a new
 * {@code payment.confirmed} event, which order treats as a repeat if it has already seen it (it is idempotent by confirmation id), so a repeat is
 * harmless and a lost message is recovered.
 *
 * <p>The announcements back off (one window, then two, four, up to an hour) and stop at a limit
 * ({@code oriah.payment.watcher.max-emit-attempts}, ten in all), when it is logged as an ERROR: past that the confirmation stays in the pending list,
 * flagged stuck, for a person. Each confirmation is handled in a transaction of its own, taking its row only if nobody else has it, so a pass never
 * waits behind an outcome being recorded and never announces something that was answered a moment ago.
 */
@Service
@Slf4j
public class SettlementWatcher {

    static final String ACTOR = "system:payment-watcher";
    private static final Duration LONGEST_BACKOFF = Duration.ofHours(1);
    private static final int PER_PASS = 50;

    private record Row(UUID id, UUID orderId, String method, String reference, BigDecimal gross, BigDecimal fee, BigDecimal net, String currency,
                       Instant arrivedAt, String backdateReason, String confirmedBy, Instant confirmedAt, int attempts) {
    }

    private static final String DUE = """
              FROM payment_confirmation c
              LEFT JOIN payment_confirmation_void v ON v.confirmation_id = c.id
             WHERE c.outcome = 'PENDING' AND v.id IS NULL AND c.confirmed_at <= ? AND c.emit_attempts < ?
               AND (c.next_emit_at IS NULL OR c.next_emit_at <= ?)""";

    private final JdbcTemplate jdbc;
    private final SalesSettings sales;
    private final WatcherSettings settings;
    private final ConfirmationEvents events;
    private final TransactionTemplate transaction;

    public SettlementWatcher(JdbcTemplate jdbc, SalesSettings sales, WatcherSettings settings, ConfirmationEvents events, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.sales = sales;
        this.settings = settings;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * One pass, judging by {@code now}.
     *
     * @return how many confirmations were announced again in this pass
     */
    public int reemitDue(Instant now) {
        Timestamp stuckBefore = Timestamp.from(now.minus(sales.settlementStuckAfter()));
        List<UUID> candidates = jdbc.query("SELECT c.id" + DUE + " ORDER BY c.confirmed_at, c.id LIMIT " + PER_PASS, (rs, n) -> rs.getObject("id", UUID.class),
                stuckBefore, settings.maxEmitAttempts(), Timestamp.from(now));
        int announced = 0;
        for (UUID id : candidates) {
            Boolean done = transaction.execute(status -> announceAgain(id, now, stuckBefore));
            if (Boolean.TRUE.equals(done)) {
                announced++;
            }
        }
        return announced;
    }

    /** How many confirmations have waited longer than the settlement window without an outcome (the number a gauge and an alert watch). */
    public long stuckCount(Instant now) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM payment_confirmation c
                  LEFT JOIN payment_confirmation_void v ON v.confirmation_id = c.id
                 WHERE c.outcome = 'PENDING' AND v.id IS NULL AND c.confirmed_at <= ?""", Long.class, Timestamp.from(now.minus(sales.settlementStuckAfter())));
    }

    private boolean announceAgain(UUID id, Instant now, Timestamp stuckBefore) {
        // The row is taken only if nobody has it, and ONLY THEN is it looked at again (a second statement, so it sees what was committed while this
        // asked for the lock): an outcome or a void may have been recorded since the candidates were read.
        if (jdbc.query("SELECT c.id FROM payment_confirmation c WHERE c.id = ? FOR UPDATE OF c SKIP LOCKED", (rs, n) -> rs.getObject("id", UUID.class), id).isEmpty()) {
            return false;
        }
        List<Row> locked = jdbc.query("SELECT c.id, c.order_id, c.method, c.reference, c.gross, c.fee, c.net, c.currency, c.arrived_at, c.backdate_reason, "
                + "c.confirmed_by, c.confirmed_at, c.emit_attempts" + DUE + " AND c.id = ?", (rs, n) -> new Row(rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getString("method"), rs.getString("reference"), rs.getBigDecimal("gross"), rs.getBigDecimal("fee"),
                rs.getBigDecimal("net"), rs.getString("currency"), rs.getTimestamp("arrived_at").toInstant(), rs.getString("backdate_reason"),
                rs.getString("confirmed_by"), rs.getTimestamp("confirmed_at").toInstant(), rs.getInt("emit_attempts")),
                stuckBefore, settings.maxEmitAttempts(), Timestamp.from(now), id);
        if (locked.isEmpty()) {
            return false;
        }
        Row row = locked.getFirst();
        int attempt = row.attempts() + 1;
        events.confirmed(row.id(), row.orderId(), row.method(), row.reference(), row.gross(), row.fee(), row.net(), row.currency(), row.arrivedAt(),
                row.backdateReason(), row.confirmedBy(), row.confirmedAt(), attempt, ACTOR);
        jdbc.update("UPDATE payment_confirmation SET emit_attempts = ?, next_emit_at = ? WHERE id = ?", attempt, Timestamp.from(now.plus(backoff(attempt))), id);
        if (attempt >= settings.maxEmitAttempts()) {
            log.error("confirmation {} (order {}, {} {} {}) has been announced {} times and order has not answered: it needs a person, it stays in the pending list",
                    id, row.orderId(), row.method(), row.gross().toPlainString(), row.currency(), attempt);
        } else {
            log.warn("confirmation {} (order {}) has no outcome after {}: announced again ({} of {})", id, row.orderId(), sales.settlementStuckAfter(), attempt,
                    settings.maxEmitAttempts());
        }
        return true;
    }

    /** The wait after the {@code attempt}th announcement: one settlement window after the first repeat, then twice as long each time, up to an hour. */
    Duration backoff(int attempt) {
        long doublings = Math.max(0, attempt - 2);
        Duration wait = sales.settlementStuckAfter();
        for (long i = 0; i < doublings && wait.compareTo(LONGEST_BACKOFF) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(LONGEST_BACKOFF) > 0 ? LONGEST_BACKOFF : wait;
    }
}
