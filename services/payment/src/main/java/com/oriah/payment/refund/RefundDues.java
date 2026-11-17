package com.oriah.payment.refund;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.payment.confirmation.Staff;
import com.oriah.payment.events.RefundClosedEvent;
import com.oriah.payment.events.RefundDueRecordedEvent;
import com.oriah.payment.events.RefundEvents;
import com.oriah.payment.exception.PaymentProblem;

import lombok.extern.slf4j.Slf4j;

/**
 * What payment owes customers for corrected sales (Phase 12). Order announces a correction of a paid sale (a paid order cancelled before it left, or pieces that came
 * back) and what it owes; payment keeps ONE debt per correction, under the correction's own id, and announces it. The debt is the only thing a refund can be paid
 * against: nobody types an amount to pay back.
 *
 * <p>What is owed comes out of the money that paid the sale: the confirmation must be payment's own, for that order, and have paid a sale (SETTLED), and all the
 * debts on it together are never more than it brought in. A correction that breaks one of these is refused as a broken event (it is parked for a person), never
 * turned into a debt.
 *
 * <p><b>A debt in question.</b> When the money record that paid the sale was cancelled (voided) after it paid, nobody knows whether the money ever arrived. Such a
 * debt is IN_QUESTION and cannot be paid until the owner says, once: it arrived (the debt is owed), or it never did (the debt is closed, and announced so the books
 * forget the money and its fee).
 *
 * <p>Locks: the confirmation's row first, then the debt's, everywhere in payment.
 */
@Service
@Slf4j
public class RefundDues {

    private static final Set<String> KINDS = Set.of("CANCELLATION", "RETURN");
    private static final Set<String> BEARERS = Set.of("CUSTOMER", "ORIAH");
    static final List<String> STATUSES = List.of("OWED", "IN_QUESTION", "PAID", "CLOSED");

    private static final String SELECT = """
            SELECT d.id, d.kind, d.order_id, d.confirmation_id, d.owed_gross, d.currency, d.fee_bearer, d.owed_since, d.status,
                   k.money_arrived, k.checked_at,
                   m.id AS payout_id, m.paid_at, m.method AS payout_method, m.customer_fee, m.net, m.send_charge,
                   c.method AS original_method, c.reference AS original_reference, c.fee AS original_fee, c.arrived_at AS original_arrived_at
              FROM refund_due d
              JOIN payment_confirmation c ON c.id = d.confirmation_id
              LEFT JOIN refund_due_check k ON k.refund_due_id = d.id
              LEFT JOIN money_out m ON m.id = d.paid_money_out_id""";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RefundEvents events;

    public RefundDues(JdbcTemplate jdbc, Clock clock, RefundEvents events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Order's correction of a paid sale: record what is owed for it. Told again, it changes nothing.
     *
     * @param correctionId the correction's id, which is the debt's
     * @param owedSince    when the correction happened
     * @throws IllegalArgumentException the event itself is malformed (a kind or a bearer nobody knows, an amount below nothing)
     * @throws IllegalStateException    the correction cannot be owed: no such confirmation, not this order's, never paid a sale, another currency, more than the
     *                                  money that came in, or the same correction announced with other figures
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void owe(UUID correctionId, String kind, UUID orderId, UUID confirmationId, BigDecimal owedAsSent, String currency, String feeBearer, Instant owedSince,
                    UUID sourceEventId, String actor) {
        // An amount read from JSON keeps whatever decimals it was written with (225.0, 225.00): kept and announced with exactly two, like every amount.
        if (owedAsSent.signum() < 0 || owedAsSent.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("what is owed is a whole number of ngwee and not below nothing, was " + owedAsSent.toPlainString());
        }
        BigDecimal owed = owedAsSent.setScale(2, java.math.RoundingMode.UNNECESSARY);
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("a correction is a CANCELLATION or a RETURN, was " + kind);
        }
        if (!BEARERS.contains(feeBearer)) {
            throw new IllegalArgumentException("the sending charge is borne by the CUSTOMER or by ORIAH, was " + feeBearer);
        }
        if (owed.signum() == 0) {
            log.info("Correction {} of order {} owes nothing: no debt", correctionId, orderId);
            return;
        }
        // The confirmation first: everything about the money of one sale is taken one after the other.
        record Money(UUID orderId, BigDecimal gross, String currency, String outcome) {
        }
        Money money = jdbc.query("SELECT order_id, gross, currency, outcome FROM payment_confirmation WHERE id = ? FOR UPDATE",
                (rs, n) -> new Money(rs.getObject("order_id", UUID.class), rs.getBigDecimal("gross"), rs.getString("currency"), rs.getString("outcome")), confirmationId)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("correction " + correctionId + " names confirmation " + confirmationId
                        + ", which payment does not have: nothing can be owed out of it"));
        if (!money.orderId().equals(orderId)) {
            throw new IllegalStateException("correction " + correctionId + " is of order " + orderId + ", but confirmation " + confirmationId + " was recorded for another order");
        }
        if (!"SETTLED".equals(money.outcome())) {
            throw new IllegalStateException("correction " + correctionId + " names confirmation " + confirmationId + ", which never paid a sale (" + money.outcome() + ")");
        }
        if (!money.currency().equals(currency)) {
            throw new IllegalStateException("correction " + correctionId + " is in " + currency + ", the money that paid the sale in " + money.currency());
        }
        List<Map<String, Object>> held = jdbc.queryForList("SELECT kind, order_id, confirmation_id, owed_gross, fee_bearer FROM refund_due WHERE id = ?", correctionId);
        if (!held.isEmpty()) {
            Map<String, Object> was = held.get(0);
            if (!kind.equals(was.get("kind")) || !orderId.equals(was.get("order_id")) || !confirmationId.equals(was.get("confirmation_id"))
                    || ((BigDecimal) was.get("owed_gross")).compareTo(owed) != 0 || !feeBearer.equals(was.get("fee_bearer"))) {
                throw new IllegalStateException("correction " + correctionId + " was already recorded as a debt with other figures: the first stands, and this one is refused");
            }
            return;
        }
        BigDecimal already = jdbc.queryForObject("SELECT coalesce(sum(owed_gross), 0) FROM refund_due WHERE confirmation_id = ?", BigDecimal.class, confirmationId);
        if (already.add(owed).compareTo(money.gross()) > 0) {
            throw new IllegalStateException("correction " + correctionId + " would owe " + already.add(owed).toPlainString() + " out of confirmation " + confirmationId
                    + ", which brought in " + money.gross().toPlainString() + ": never more is owed than the money that came");
        }
        // Read after the lock, in a statement of its own: a void that committed while this waited for the row is seen.
        boolean voided = jdbc.queryForObject("SELECT count(*) FROM payment_confirmation_void WHERE confirmation_id = ?", Long.class, confirmationId) > 0;
        String status = voided ? "IN_QUESTION" : "OWED";
        Instant now = now();
        Instant since = owedSince.truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO refund_due (id, kind, order_id, confirmation_id, owed_gross, currency, fee_bearer, owed_since, source_event_id, created_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                correctionId, kind, orderId, confirmationId, owed, currency, feeBearer, Timestamp.from(since), sourceEventId, Timestamp.from(now), status);
        events.dueRecorded(new RefundDueRecordedEvent(correctionId, kind, orderId, confirmationId, owed, currency, feeBearer, status, since, now), actor);
        if (voided) {
            log.error("Refund {} of {} {} for order {} is IN QUESTION: the money record that paid the sale was cancelled. The owner must say whether the money arrived",
                    correctionId, owed.toPlainString(), currency, orderId);
        } else {
            log.info("Refund {} of {} {} is owed for order {} ({})", correctionId, owed.toPlainString(), currency, orderId, kind);
        }
    }

    /**
     * The owner's word on a debt in question, once.
     *
     * @throws PaymentProblem 404 {@code refund-due-not-found}; 409 {@code already-checked}, {@code not-in-question}
     */
    @Transactional
    public RefundDueView moneyCheck(UUID dueId, boolean moneyArrived, String reason, Staff staff) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 10 || why.length() > 200) {
            throw new IllegalArgumentException("the reason must be 10 to 200 characters: say what you looked at");
        }
        UUID confirmation = jdbc.query("SELECT confirmation_id FROM refund_due WHERE id = ?", (rs, n) -> rs.getObject(1, UUID.class), dueId).stream().findFirst()
                .orElseThrow(() -> notFound(dueId));
        jdbc.query("SELECT id FROM payment_confirmation WHERE id = ? FOR UPDATE", rs -> { }, confirmation);
        Map<String, Object> due = jdbc.queryForMap("SELECT status, order_id, owed_gross, currency FROM refund_due WHERE id = ? FOR UPDATE", dueId);
        if (jdbc.queryForObject("SELECT count(*) FROM refund_due_check WHERE refund_due_id = ?", Long.class, dueId) > 0) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "already-checked", "it was already said whether the money of refund " + dueId + " arrived: that is said once");
        }
        if (!"IN_QUESTION".equals(due.get("status"))) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "not-in-question", "refund " + dueId + " is " + due.get("status") + ": there is nothing to check");
        }
        Instant now = now();
        jdbc.update("INSERT INTO refund_due_check (id, refund_due_id, money_arrived, reason, checked_by, checked_by_name, checked_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), dueId, moneyArrived, why, staff.actor(), staff.name(), Timestamp.from(now));
        if (moneyArrived) {
            jdbc.update("UPDATE refund_due SET status = 'OWED' WHERE id = ?", dueId);
        } else {
            jdbc.update("UPDATE refund_due SET status = 'CLOSED' WHERE id = ?", dueId);
            events.closed(new RefundClosedEvent(dueId, (UUID) due.get("order_id"), confirmation, (BigDecimal) due.get("owed_gross"), (String) due.get("currency"),
                    RefundClosedEvent.MONEY_NEVER_ARRIVED, now, now), staff.actor());
        }
        // Which debt, who, and what was said. Never the reason: a person typed it.
        log.info("Refund {} checked by {}: the money {}", dueId, staff.actor(), moneyArrived ? "arrived, so it is owed" : "never arrived, so it is closed");
        return get(dueId);
    }

    /** @param status OWED, IN_QUESTION, PAID, CLOSED or ALL; the oldest debt first */
    @Transactional(readOnly = true)
    public List<RefundDueView> list(String status) {
        String wanted = status == null ? "OWED" : status.strip().toUpperCase(java.util.Locale.ROOT);
        if (!"ALL".equals(wanted) && !STATUSES.contains(wanted)) {
            throw new IllegalArgumentException("status must be OWED, IN_QUESTION, PAID, CLOSED or ALL, was '" + status + "'");
        }
        return "ALL".equals(wanted) ? jdbc.query(SELECT + " ORDER BY d.owed_since, d.id", this::view)
                : jdbc.query(SELECT + " WHERE d.status = ? ORDER BY d.owed_since, d.id", this::view, wanted);
    }

    @Transactional(readOnly = true)
    public RefundDueView get(UUID dueId) {
        return jdbc.query(SELECT + " WHERE d.id = ?", this::view, dueId).stream().findFirst().orElseThrow(() -> notFound(dueId));
    }

    static PaymentProblem notFound(UUID dueId) {
        return new PaymentProblem(HttpStatus.NOT_FOUND, "refund-due-not-found", "there is no refund owed under " + dueId);
    }

    private RefundDueView view(ResultSet rs, int row) throws SQLException {
        Timestamp checkedAt = rs.getTimestamp("checked_at");
        UUID payout = rs.getObject("payout_id", UUID.class);
        return new RefundDueView(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("order_id", UUID.class), rs.getObject("confirmation_id", UUID.class),
                rs.getBigDecimal("owed_gross"), rs.getString("currency"), rs.getString("fee_bearer"), rs.getTimestamp("owed_since").toInstant(), rs.getString("status"),
                checkedAt == null ? null : new RefundDueView.Check(rs.getBoolean("money_arrived"), checkedAt.toInstant()),
                payout == null ? null : new RefundDueView.Payout(payout, rs.getTimestamp("paid_at").toInstant(), rs.getString("payout_method"),
                        rs.getBigDecimal("customer_fee"), rs.getBigDecimal("net"), rs.getBigDecimal("send_charge")),
                rs.getString("original_method"), rs.getString("original_reference"), rs.getBigDecimal("original_fee"),
                rs.getTimestamp("original_arrived_at").toInstant());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
