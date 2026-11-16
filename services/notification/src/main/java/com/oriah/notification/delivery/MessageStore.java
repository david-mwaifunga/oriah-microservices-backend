package com.oriah.notification.delivery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.oriah.notification.model.RecipientKind;
import com.oriah.notification.model.StatusReason;

/**
 * Every change to a message after it was written down, as guarded SQL. Each method is ONE statement in its own (autocommit) transaction.
 *
 * <p><b>Why not load the entity and save it.</b> A message can be looked at by two dispatchers at once (two instances, or one that was slow
 * and one that took over), and a send can take a minute. So nothing here trusts what it read earlier: each statement says in its WHERE what
 * must still be true, and reports whether it changed a row.
 * <ul>
 *   <li>A row is taken with {@link #claim}: it counts the try and pushes {@code next_attempt_at} past the lease, so nobody else takes it while
 *       the send runs. It answers the row's new {@code attempts}.</li>
 *   <li>Every outcome of a send is written only {@code WHERE status = 'RECORDED' AND attempts = :claimed}: an outcome that arrives after the
 *       lease ran out and somebody else took the row changes nothing.</li>
 * </ul>
 * No connection is held between the claim and the outcome: the send in between runs with no transaction at all.
 */
@Component
public class MessageStore {

    /**
     * A message waiting to be sent, as the dispatcher needs it.
     *
     * @param keycloakId      the customer whose message it is; null for a staff row
     * @param eventSeq        its event's place in the order's history; null for a row no event caused
     * @param attempts        tries so far
     * @param maybeHandedOver true once a try handed the body over and got no answer
     * @param staffTo         only on a staff row: the shop mailbox it is for
     */
    public record Due(UUID id, RecipientKind recipientKind, UUID orderId, String keycloakId, String eventType, String subject, String body,
                      Long eventSeq, int attempts, boolean maybeHandedOver, Instant sendBefore, String staffTo, UUID alertId,
                      String alertNotice) {

        /** Never the words or a mailbox: this is what a log line would print. */
        @Override
        public String toString() {
            return "Due[" + id + " " + recipientKind + " " + eventType + "]";
        }
    }

    private final JdbcTemplate jdbc;

    public MessageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The messages that may be sent now: waiting, and due. Messages to staff first, then the oldest.
     *
     * <p>A customer's messages about one order go out in the order their events happened: a message is left out while an EARLIER message of the
     * same order (a lower {@code event_seq}) is still waiting, so "on its way" never overtakes "payment received". Staff rows and rows no event
     * caused are never part of an order's queue. No lock is taken here: {@link #claim} is what takes a row.
     */
    public List<Due> due(Instant now, int limit) {
        return jdbc.query("""
                SELECT m.* FROM notification_log m
                 WHERE m.status = 'RECORDED' AND m.next_attempt_at <= ?
                   AND NOT (m.recipient_kind = 'SHOPPER' AND m.event_seq IS NOT NULL AND EXISTS (
                           SELECT 1 FROM notification_log earlier
                            WHERE earlier.order_id = m.order_id AND earlier.recipient_kind = 'SHOPPER' AND earlier.status = 'RECORDED'
                              AND earlier.event_seq < m.event_seq))
                 ORDER BY (m.recipient_kind = 'STAFF') DESC, m.recorded_at, m.id
                 LIMIT ?""", MessageStore::due, Timestamp.from(now), limit);
    }

    /**
     * Takes a row for one try: counts the try and puts the row out of everybody's reach until the lease runs out.
     *
     * @return the row's new number of tries; empty when the row is no longer waiting or somebody else has it
     */
    public OptionalInt claim(UUID id, Instant now, Duration lease) {
        List<Integer> attempts = jdbc.query("""
                UPDATE notification_log
                   SET attempts = attempts + 1, last_attempt_at = ?, next_attempt_at = ?
                 WHERE id = ? AND status = 'RECORDED' AND next_attempt_at <= ?
                RETURNING attempts""", (row, n) -> row.getInt(1), Timestamp.from(now), Timestamp.from(now.plus(lease)), id, Timestamp.from(now));
        return attempts.isEmpty() ? OptionalInt.empty() : OptionalInt.of(attempts.getFirst());
    }

    /** The mail server took it. @return false when this claim no longer holds the row */
    public boolean markSent(UUID id, int claimed, Instant now, boolean shortForm) {
        return jdbc.update("""
                UPDATE notification_log
                   SET status = 'SENT', status_reason = NULL, status_at = ?, sent_at = ?, next_attempt_at = NULL, short_form = ?, last_error = NULL
                 WHERE id = ? AND status = 'RECORDED' AND attempts = ?""", Timestamp.from(now), Timestamp.from(now), shortForm, id, claimed) == 1;
    }

    /** A try failed in a way that may pass: the row waits until {@code next}. */
    public boolean retryLater(UUID id, int claimed, Instant next, String lastError) {
        return jdbc.update("""
                UPDATE notification_log SET next_attempt_at = ?, last_error = ?
                 WHERE id = ? AND status = 'RECORDED' AND attempts = ?""", Timestamp.from(next), lastError, id, claimed) == 1;
    }

    /** The body was handed over and no answer came: it may have arrived. The row gets one more try, not before {@code next}. */
    public boolean uncertain(UUID id, int claimed, Instant next) {
        return jdbc.update("""
                UPDATE notification_log SET maybe_handed_over = TRUE, next_attempt_at = ?, last_error = ?
                 WHERE id = ? AND status = 'RECORDED' AND attempts = ?""", Timestamp.from(next), SendResult.AFTER_DATA, id, claimed) == 1;
    }

    /** The end of a row that was tried: its address was refused for good, or it may have arrived and will not be tried again. */
    public boolean fail(UUID id, int claimed, StatusReason reason, String lastError, Instant now) {
        return jdbc.update("""
                UPDATE notification_log SET status = 'FAILED', status_reason = ?, status_at = ?, next_attempt_at = NULL, last_error = ?
                 WHERE id = ? AND status = 'RECORDED' AND attempts = ?""", reason.name(), Timestamp.from(now), lastError, id, claimed) == 1;
    }

    /** A waiting row that is deliberately not sent, decided before any try of this pass. */
    public boolean notSent(UUID id, StatusReason reason, Instant now) {
        return jdbc.update("""
                UPDATE notification_log SET status = 'NOT_SENT', status_reason = ?, status_at = ?, next_attempt_at = NULL
                 WHERE id = ? AND status = 'RECORDED' AND next_attempt_at <= ?""", reason.name(), Timestamp.from(now), id, Timestamp.from(now)) == 1;
    }

    /** A claimed row that turned out not to be sendable (the transport's own sandbox lock turned it back). */
    public boolean notSentAfterClaim(UUID id, int claimed, StatusReason reason, Instant now) {
        return jdbc.update("""
                UPDATE notification_log SET status = 'NOT_SENT', status_reason = ?, status_at = ?, next_attempt_at = NULL
                 WHERE id = ? AND status = 'RECORDED' AND attempts = ?""", reason.name(), Timestamp.from(now), id, claimed) == 1;
    }

    /** Looks at a waiting row again later, without spending a try (its customer's address is not known yet). */
    public boolean waitUntil(UUID id, Instant until, Instant now) {
        return jdbc.update("""
                UPDATE notification_log SET next_attempt_at = ?
                 WHERE id = ? AND status = 'RECORDED' AND next_attempt_at <= ?""", Timestamp.from(until), id, Timestamp.from(now)) == 1;
    }

    /**
     * Ends every waiting row whose deadline has passed. A row whose send may still be running (claimed less than a lease ago) is left for
     * the next pass. How a row ends says what happened to it: it may have arrived; it was tried and given up; its customer's address was
     * never learnt; or it was never tried and is simply too late.
     *
     * @return how many rows ended
     */
    public int sweep(Instant now, Duration lease) {
        return jdbc.update("""
                UPDATE notification_log m
                   SET status = CASE WHEN m.maybe_handed_over OR m.attempts > 0 THEN 'FAILED' ELSE 'NOT_SENT' END,
                       status_reason = CASE
                           WHEN m.maybe_handed_over THEN 'OUTCOME_UNKNOWN'
                           WHEN m.attempts > 0 THEN 'GAVE_UP'
                           WHEN m.recipient_kind = 'SHOPPER'
                                AND NOT EXISTS (SELECT 1 FROM recipient_contact c WHERE c.keycloak_id = m.keycloak_id) THEN 'NO_ADDRESS'
                           ELSE 'TOO_LATE' END,
                       status_at = ?, next_attempt_at = NULL
                 WHERE m.status = 'RECORDED' AND m.send_before <= ?
                   AND (m.last_attempt_at IS NULL OR m.last_attempt_at <= ?)""",
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now.minus(lease)));
    }

    /**
     * Whether a LATER message of the same order exists for a customer (a higher {@code event_seq}) of the given type.
     *
     * @param sentOnly true to count only one a mail server took
     */
    public boolean laterMessage(UUID orderId, long afterSeq, String eventType, boolean sentOnly) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notification_log
                                WHERE order_id = ? AND recipient_kind = 'SHOPPER' AND event_type = ? AND event_seq > ?
                                  AND (? = FALSE OR status = 'SENT'))""", Boolean.class, orderId, eventType, afterSeq, sentOnly));
    }

    private static Due due(ResultSet row, int n) throws SQLException {
        Timestamp sendBefore = row.getTimestamp("send_before");
        long seq = row.getLong("event_seq");
        Long eventSeq = row.wasNull() ? null : seq;
        return new Due(row.getObject("id", UUID.class), RecipientKind.valueOf(row.getString("recipient_kind")), row.getObject("order_id", UUID.class),
                row.getString("keycloak_id"), row.getString("event_type"), row.getString("subject"), row.getString("body"), eventSeq,
                row.getInt("attempts"), row.getBoolean("maybe_handed_over"), sendBefore == null ? null : sendBefore.toInstant(),
                row.getString("staff_to"), row.getObject("alert_id", UUID.class), row.getString("alert_notice"));
    }
}
