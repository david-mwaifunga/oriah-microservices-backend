package com.oriah.finance.events;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.ledger.AnomalyService;
import com.oriah.finance.ledger.AnomalyService.Severity;
import com.oriah.finance.ledger.LedgerWriter;
import com.oriah.finance.ledger.LedgerWriter.Entry;

/**
 * Everything about the corrections of one order that depends on MORE than one fact, worked out from all the facts held, after each one lands (Phase 12). The sale, the
 * correction, the debt and its close reach finance from two topics in any order; whoever applies one of them runs this, so the books and the open errors come out the same
 * whatever the order was.
 *
 * <ul>
 *   <li>the waiver's memo is taken back only if the sale booked one (a sale announced before waivers were announced has none: said, not booked);</li>
 *   <li>a debt closed without a payout clears what was owed, once the correction that owed it is known: what is owed never reads below nothing;</li>
 *   <li>a correction that names another price, discount, product or promotion than the sale's line is raised;</li>
 *   <li>more pieces taken back than a line sold is raised;</li>
 *   <li>a debt in payment that is not the amount the correction owes is raised.</li>
 * </ul>
 *
 * Rows only ever add, each under a key of its own, so running it again changes nothing.
 */
@Component
public class CorrectionDerivation {

    private final JdbcTemplate jdbc;
    private final LedgerWriter ledger;
    private final AnomalyService anomalies;
    private final BusinessCalendar calendar;

    public CorrectionDerivation(JdbcTemplate jdbc, LedgerWriter ledger, AnomalyService anomalies, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.anomalies = anomalies;
        this.calendar = calendar;
    }

    private record Correction(UUID id, UUID orderId, UUID confirmationId, Instant occurredAt, BigDecimal waiver, BigDecimal owed, String actor, Instant sourceRecordedAt,
                              UUID eventId) {
    }

    /** The caller holds the writer, confirmation and order locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void derive(UUID orderId, EventContext ctx) {
        List<Correction> corrections = jdbc.query("""
                SELECT correction_id, order_id, confirmation_id, occurred_at, waiver_taken_back, owed_gross, actor, source_recorded_at, source_event_id
                  FROM sale_correction WHERE order_id = ? ORDER BY occurred_at, correction_id""",
                (rs, n) -> new Correction(rs.getObject("correction_id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("confirmation_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getBigDecimal("waiver_taken_back"), rs.getBigDecimal("owed_gross"), rs.getString("actor"),
                        rs.getTimestamp("source_recorded_at") == null ? null : rs.getTimestamp("source_recorded_at").toInstant(),
                        rs.getObject("source_event_id", UUID.class)), orderId);
        for (Correction c : corrections) {
            waiver(c, ctx);
            close(c);
            againstTheSale(c, ctx);
            againstTheDebt(c, ctx);
        }
    }

    private void waiver(Correction c, EventContext ctx) {
        if (c.waiver().signum() <= 0) {
            return;
        }
        if (keyExists("sale:" + c.confirmationId() + ":waived")) {
            ledger.post(Entry.of("corr:" + c.id() + ":waived", "MEMO_DELIVERY_WAIVED", c.waiver().negate(), c.occurredAt(), calendar.dayOf(c.occurredAt()), "SALE_CORRECTED",
                    c.actor()).sourceRecordedAt(c.sourceRecordedAt()).event(c.eventId(), "order", null).order(c.orderId()).confirmation(c.confirmationId()).correction(c.id()));
            anomalies.autoResolve("waiver-not-recorded-on-sale", c.id().toString());
        } else if (jdbc.queryForObject("SELECT count(*) FROM sale WHERE confirmation_id = ?", Long.class, c.confirmationId()) > 0) {
            anomalies.raise("waiver-not-recorded-on-sale", c.id().toString(), Severity.WARN,
                    Map.of("correctionId", c.id().toString(), "orderId", c.orderId().toString(), "waiverTakenBack", c.waiver().toPlainString()), ctx.eventId());
        }
    }

    /** A debt closed without a payout: what was owed is cleared on the day it was closed. The debt's id is the correction's. */
    private void close(Correction c) {
        if (c.owed().signum() <= 0) {
            return;
        }
        jdbc.query("SELECT closed_at, actor, source_event_id FROM refund_close_fact WHERE due_id = ?", rs -> {
            Instant closedAt = rs.getTimestamp("closed_at").toInstant();
            ledger.post(Entry.of("close:" + c.id() + ":owed", "MEMO_REFUNDS_OWED", c.owed().negate(), closedAt, calendar.dayOf(closedAt), "REFUND_CLOSED", rs.getString("actor"))
                    .event(rs.getObject("source_event_id", UUID.class), "payment", null).order(c.orderId()).correction(c.id()));
        }, c.id());
    }

    private void againstTheSale(Correction c, EventContext ctx) {
        Long differing = jdbc.queryForObject("""
                SELECT count(*) FROM sale_correction_line l JOIN sale_line s ON s.sale_line_id = l.sale_line_id
                 WHERE l.correction_id = ? AND (s.list_price <> l.unit_price OR s.discount_per_piece <> l.discount_per_piece OR s.product_id <> l.product_id
                        OR s.variant_id <> l.variant_id OR s.promotion_id IS DISTINCT FROM l.promotion_id OR s.confirmation_id <> ?)""",
                Long.class, c.id(), c.confirmationId());
        if (differing != null && differing > 0) {
            anomalies.raise("reversal-differs-from-sale", c.id().toString(), Severity.ERROR,
                    Map.of("correctionId", c.id().toString(), "orderId", c.orderId().toString(), "linesThatDiffer", differing), ctx.eventId());
        }
        jdbc.query("""
                SELECT s.sale_line_id, s.quantity AS sold,
                       (SELECT coalesce(sum(b.quantity), 0) FROM sale_correction_line b WHERE b.sale_line_id = s.sale_line_id) AS back
                  FROM sale_line s WHERE s.sale_line_id IN (SELECT sale_line_id FROM sale_correction_line WHERE correction_id = ?)""", rs -> {
            if (rs.getInt("back") > rs.getInt("sold")) {
                anomalies.raise("correction-exceeds-sale", rs.getString("sale_line_id"), Severity.ERROR,
                        Map.of("saleLineId", rs.getString("sale_line_id"), "sold", rs.getInt("sold"), "takenBack", rs.getInt("back")), ctx.eventId());
            }
        }, c.id());
    }

    private void againstTheDebt(Correction c, EventContext ctx) {
        jdbc.query("SELECT owed_gross FROM refund_due_fact WHERE due_id = ?", rs -> {
            BigDecimal debt = rs.getBigDecimal("owed_gross");
            if (debt.compareTo(c.owed()) != 0) {
                anomalies.raise("debt-differs-from-correction", c.id().toString(), Severity.ERROR,
                        Map.of("dueId", c.id().toString(), "paymentOwes", debt.toPlainString(), "correctionOwes", c.owed().toPlainString()), ctx.eventId());
            }
        }, c.id());
    }

    private boolean keyExists(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE entry_key = ?", Long.class, key) > 0;
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
