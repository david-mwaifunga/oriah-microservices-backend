package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.payment.events.ConfirmationEvents;

import lombok.extern.slf4j.Slf4j;

/**
 * What became of confirmed money, as order tells payment (Phase 7): it paid a sale (SETTLED), or it could not and is owed back to the customer (HELD).
 *
 * <p>Payment is the detector of money with no outcome: a confirmation stays PENDING until one of these arrives, and the watcher announces it again
 * while it does. Each is safe to apply twice, and refuses to contradict what is recorded: money that paid a sale is never held, held money is never
 * said to have paid one (that would be a disagreement between order and payment, so it is logged as an ERROR for someone to look at, not "fixed"),
 * and a confirmation payment never recorded cannot be held. A confirmation that was voided is no longer money to give back.
 */
@Service
@Slf4j
public class SettlementOutcomes {

    static final Set<String> REASONS = Set.of("WRONG_AMOUNT", "ORDER_CANCELLED", "PIECE_SOLD", "ALREADY_PAID", "ORDER_UNKNOWN");

    /** The reason kept on a SETTLED confirmation that had been voided before order's answer arrived: a disagreement a person must put right. */
    public static final String SETTLED_AFTER_VOID = "SETTLED_AFTER_VOID";

    private record Row(UUID orderId, BigDecimal gross, String currency, String outcome, String outcomeReason, boolean voided) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ConfirmationEvents events;

    public SettlementOutcomes(JdbcTemplate jdbc, Clock clock, ConfirmationEvents events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    /** Order says a sale was paid by this confirmation. */
    @Transactional
    public void settled(UUID confirmationId) {
        Row row = lock(confirmationId);
        if (row == null) {
            log.error("order says confirmation {} paid a sale, but payment has no such confirmation", confirmationId);
            return;
        }
        if ("SETTLED".equals(row.outcome())) {
            return;
        }
        if ("HELD".equals(row.outcome())) {
            log.error("order says confirmation {} paid a sale, but payment holds that money for the customer ({}): the two disagree, look at order {}",
                    confirmationId, row.outcomeReason(), row.orderId());
            return;
        }
        // Money that paid a sale is SETTLED whatever happened to the confirmation since: it did pay the sale. If it had been voided in the meantime
        // (order paid, and the answer was still on its way when a person voided it) the record says so for good, so the disagreement between the
        // books and the sale is never lost: the reference was freed and the fee reversed for money that paid after all.
        jdbc.update("UPDATE payment_confirmation SET outcome = 'SETTLED', outcome_reason = ?, outcome_at = ? WHERE id = ?",
                row.voided() ? SETTLED_AFTER_VOID : null, Timestamp.from(now()), confirmationId);
        if (row.voided()) {
            log.error("a sale was paid by confirmation {}, which had been voided: its reference was freed and its fee reversed for money that paid "
                    + "order {} after all. Marked {}; someone must put the books right", confirmationId, row.orderId(), SETTLED_AFTER_VOID);
        }
        log.info("Confirmation {} settled: it paid order {}", confirmationId, row.orderId());
    }

    /**
     * Order says this confirmation's money could not pay the sale.
     *
     * @throws IllegalArgumentException for a reason payment does not know: a broken event
     */
    @Transactional
    public void unsettled(UUID confirmationId, String reason) {
        if (reason == null || !REASONS.contains(reason)) {
            throw new IllegalArgumentException("reason must be one of " + new java.util.TreeSet<>(REASONS) + ", was '" + reason + "'");
        }
        Row row = lock(confirmationId);
        if (row == null) {
            log.error("order says confirmation {} could not pay a sale ({}), but payment has no such confirmation: nothing to hold", confirmationId, reason);
            return;
        }
        if (row.voided()) {
            log.info("confirmation {} was voided: its money is not held ({})", confirmationId, reason);
            return;
        }
        if ("SETTLED".equals(row.outcome())) {
            log.error("order says confirmation {} could not pay a sale ({}), but payment recorded it as paying one: the two disagree, look at order {}",
                    confirmationId, reason, row.orderId());
            return;
        }
        if ("HELD".equals(row.outcome())) {
            return;
        }
        Instant now = now();
        jdbc.update("UPDATE payment_confirmation SET outcome = 'HELD', outcome_reason = ?, outcome_at = ? WHERE id = ?", reason, Timestamp.from(now), confirmationId);
        jdbc.update("""
                INSERT INTO held_money (id, confirmation_id, order_id, reason, owed_gross, currency, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'OWED', ?)""", UUID.randomUUID(), confirmationId, row.orderId(), reason, row.gross(), row.currency(), Timestamp.from(now));
        events.held(confirmationId, row.orderId(), reason, row.gross(), row.currency(), now, "system:payment");
        log.info("Confirmation {} held for the customer: {} {} owed ({})", confirmationId, row.gross().toPlainString(), row.currency(), reason);
    }

    /**
     * Takes the confirmation's row, THEN looks at what has been done to it. Two statements on purpose: a statement that waits for a row lock keeps the
     * snapshot it started with for everything but that row, so a void that commits while this waits would be missed by a void-flag read in the same
     * statement, and voided money would be held as owed.
     */
    private Row lock(UUID confirmationId) {
        boolean found = !jdbc.query("SELECT id FROM payment_confirmation WHERE id = ? FOR UPDATE", (rs, n) -> rs.getObject("id", UUID.class), confirmationId).isEmpty();
        if (!found) {
            return null;
        }
        return jdbc.query("""
                SELECT c.order_id, c.gross, c.currency, c.outcome, c.outcome_reason,
                       EXISTS (SELECT 1 FROM payment_confirmation_void v WHERE v.confirmation_id = c.id) AS voided
                  FROM payment_confirmation c WHERE c.id = ?""",
                (rs, n) -> new Row(rs.getObject("order_id", UUID.class), rs.getBigDecimal("gross"), rs.getString("currency"), rs.getString("outcome"),
                        rs.getString("outcome_reason"), rs.getBoolean("voided")), confirmationId).stream().findFirst().orElse(null);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
