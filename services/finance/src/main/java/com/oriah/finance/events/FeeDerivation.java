package com.oriah.finance.events;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.ledger.AnomalyService;
import com.oriah.finance.ledger.AnomalyService.Severity;
import com.oriah.finance.ledger.ExpenseRecords;
import com.oriah.finance.ledger.LedgerWriter;
import com.oriah.finance.ledger.LedgerWriter.Entry;

/**
 * Everything the books say about ONE confirmation's money, worked out from ALL the facts finance holds about it, so the order three events arrive in (it came in, it was
 * voided, it paid a sale) can never change the answer. It runs under the confirmation's lock, after any of the events has recorded its fact:
 *
 * <ul>
 *   <li>the fee payment charged posts ONCE, as a cost of the day the money arrived, from the confirmation (never from a sale's own copy of the fee);</li>
 *   <li>a void takes that fee back when the confirmation was voided and never paid a sale, IN THE MONTH THE FEE WAS POSTED (a reversal carries its original's day);</li>
 *   <li>a voided confirmation that turns out to have paid a sale keeps its fee (put back if it was taken back) and raises {@code paid-after-void}: it did pay the sale;</li>
 *   <li>money that could not pay a sale is a liability beside the books from the day it ARRIVED, never revenue, and a void of it clears exactly what was held;</li>
 *   <li>a sale whose fee is not the fee payment charged is raised, both facts standing.</li>
 * </ul>
 *
 * <p>Rows only ever ADD (each under a key of its own), so a transient state, such as a fee taken back and later put back, is netted by the next row, never edited.
 */
@Component
public class FeeDerivation {

    static final String FEE_DESCRIPTION = "Payment charge on a confirmation";
    static final String VOID_REASON = "the confirmation was voided";
    static final String NEVER_ARRIVED_REASON = "the money never arrived";

    private final JdbcTemplate jdbc;
    private final LedgerWriter ledger;
    private final AnomalyService anomalies;
    private final ExpenseRecords expenses;
    private final BusinessCalendar calendar;
    private final PayoutDerivation payouts;

    public FeeDerivation(JdbcTemplate jdbc, LedgerWriter ledger, AnomalyService anomalies, ExpenseRecords expenses, BusinessCalendar calendar, PayoutDerivation payouts) {
        this.payouts = payouts;
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.anomalies = anomalies;
        this.expenses = expenses;
        this.calendar = calendar;
    }

    private record Fact(UUID orderId, String method, BigDecimal gross, BigDecimal fee, Instant arrivedAt, Instant voidedAt, Instant heldAt, String outcome, BigDecimal owedGross,
                        String actor, Instant sourceRecordedAt, UUID eventId) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void derive(UUID conf, EventContext ctx) {
        Fact confirmed = fact(conf, "CONFIRMED");
        Fact voided = fact(conf, "VOIDED");
        Fact held = fact(conf, "HELD");
        Fact sold = fact(conf, "SALE");

        Long feeEntry = null;
        if (confirmed != null && confirmed.fee().signum() > 0) {
            feeEntry = postFee(conf, confirmed);
        }
        if (voided != null && confirmed != null && feeEntry != null && sold == null) {
            takeFeeBack(conf, confirmed, voided, feeEntry);
        }
        if (voided != null && sold != null) {
            if (feeEntry != null && keyExists("fee-void:" + conf) && !keyExists("fee-reinstated:" + conf)) {
                putFeeBack(conf, confirmed, sold, feeEntry);
            }
            if (moneyNeverArrived(conf) || refundPaidOut(conf)) {
                // What became of the money is known (the owner said it never arrived, or it did arrive and has been paid back): the question is settled, and is
                // not asked again.
                anomalies.autoResolve("paid-after-void", conf.toString());
            } else {
                anomalies.raise("paid-after-void", conf.toString(), Severity.ERROR,
                        Map.of("confirmationId", conf.toString(), "orderId", String.valueOf(sold.orderId())), ctx.eventId());
            }
        }
        if (confirmed != null && sold != null && confirmed.fee().compareTo(sold.fee()) != 0) {
            anomalies.raise("fee-mismatch", conf.toString(), Severity.ERROR,
                    Map.of("confirmationId", conf.toString(), "chargedFee", confirmed.fee().toPlainString(), "saleFee", sold.fee().toPlainString()), ctx.eventId());
        }
        Long heldEntry = null;
        if (held != null) {
            heldEntry = postHeld(conf, confirmed, held, ctx);
        }
        if (voided != null && held != null && heldEntry != null && "HELD".equals(voided.outcome())) {
            clearHeld(conf, held, voided, heldEntry);
        }
        if (moneyNeverArrived(conf)) {
            removeFeeThatNeverCame(conf);
        }
        // Held money that was returned before finance heard it was held can be cleared now.
        payouts.deriveForConfirmation(conf);
    }

    /** A refund of this confirmation's sale was paid out and still stands (it was not cancelled in payment). */
    private boolean refundPaidOut(UUID conf) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM payout_fact p WHERE p.confirmation_id = ? AND p.kind = 'PAID_OUT' AND p.purpose = 'REFUND'
                   AND NOT EXISTS (SELECT 1 FROM payout_fact v WHERE v.payout_id = p.payout_id AND v.kind = 'VOIDED')""", Long.class, conf) > 0;
    }

    // ------------------------------------------------------------------ money that never arrived (Phase 12)

    /** A refund owed for this confirmation was closed without a payout: the owner confirmed the money never came in. */
    private boolean moneyNeverArrived(UUID conf) {
        return jdbc.queryForObject("SELECT count(*) FROM refund_close_fact WHERE confirmation_id = ?", Long.class, conf) > 0;
    }

    /**
     * A fee on money that never arrived was never charged, so whichever fee row stands is reversed, on that row's own day: the fee that was put back when the cancelled
     * money record turned out to have paid a sale ({@code fee-reinstated}), or the fee itself when the sale was heard of first and the fee was never taken back.
     */
    private void removeFeeThatNeverCame(UUID conf) {
        Map<String, Object> close = jdbc.queryForList("SELECT order_id, actor, source_event_id FROM refund_close_fact WHERE confirmation_id = ? ORDER BY closed_at LIMIT 1", conf)
                .get(0);
        for (String[] candidate : new String[][] {{"fee-reinstated:" + conf, "fee-unreinstated:" + conf}, {"fee:" + conf, "fee-never-came:" + conf}}) {
            List<Map<String, Object>> standing = jdbc.queryForList("""
                    SELECT entry_id, amount, occurred_at, effect_day FROM ledger_entry l
                     WHERE entry_key = ? AND NOT EXISTS (SELECT 1 FROM ledger_entry r WHERE r.reverses_entry_id = l.entry_id)""", candidate[0]);
            if (standing.isEmpty()) {
                continue;
            }
            Map<String, Object> fee = standing.get(0);
            var posted = ledger.post(Entry.of(candidate[1], "EXPENSE", ((BigDecimal) fee.get("amount")).negate(), ((Timestamp) fee.get("occurred_at")).toInstant(),
                    ((java.sql.Date) fee.get("effect_day")).toLocalDate(), "REFUND_CLOSED", (String) close.get("actor")).category("PAYMENT_CHARGES")
                    .event((UUID) close.get("source_event_id"), "payment", null).order((UUID) close.get("order_id")).confirmation(conf)
                    .reverses(((Number) fee.get("entry_id")).longValue()));
            if (posted.inserted()) {
                expenses.posted(posted.entryId(), "REVERSAL", "PAYMENT_CHARGES", FEE_DESCRIPTION, NEVER_ARRIVED_REASON, (UUID) close.get("order_id"), conf);
            }
            return;
        }
    }

    // ------------------------------------------------------------------ the fee

    private Long postFee(UUID conf, Fact confirmed) {
        var posted = ledger.post(Entry.of("fee:" + conf, "EXPENSE", confirmed.fee(), confirmed.arrivedAt(), calendar.dayOf(confirmed.arrivedAt()), "PAYMENT_CONFIRMED", confirmed.actor())
                .category("PAYMENT_CHARGES").sourceRecordedAt(confirmed.sourceRecordedAt()).event(confirmed.eventId(), "payment", null).order(confirmed.orderId()).confirmation(conf));
        if (posted.inserted()) {
            expenses.posted(posted.entryId(), "ADD", "PAYMENT_CHARGES", FEE_DESCRIPTION, null, confirmed.orderId(), conf);
        }
        return posted.entryId();
    }

    private void takeFeeBack(UUID conf, Fact confirmed, Fact voided, long feeEntry) {
        if (reversed(feeEntry)) {
            return;   // someone has already taken this fee back by hand: it is not taken back twice
        }
        var posted = ledger.post(Entry.of("fee-void:" + conf, "EXPENSE", confirmed.fee().negate(), confirmed.arrivedAt(), calendar.dayOf(confirmed.arrivedAt()), "PAYMENT_VOIDED",
                voided.actor()).category("PAYMENT_CHARGES").sourceRecordedAt(voided.sourceRecordedAt()).event(voided.eventId(), "payment", null).order(confirmed.orderId())
                .confirmation(conf).reverses(feeEntry));
        if (posted.inserted()) {
            expenses.posted(posted.entryId(), "REVERSAL", "PAYMENT_CHARGES", FEE_DESCRIPTION, VOID_REASON, confirmed.orderId(), conf);
        }
    }

    private void putFeeBack(UUID conf, Fact confirmed, Fact sold, long feeEntry) {
        var posted = ledger.post(Entry.of("fee-reinstated:" + conf, "EXPENSE", confirmed.fee(), confirmed.arrivedAt(), calendar.dayOf(confirmed.arrivedAt()), "PAYMENT_CONFIRMED",
                sold.actor()).category("PAYMENT_CHARGES").sourceRecordedAt(sold.sourceRecordedAt()).event(sold.eventId(), "payment", null).order(confirmed.orderId()).confirmation(conf));
        if (posted.inserted()) {
            expenses.posted(posted.entryId(), "ADD", "PAYMENT_CHARGES", FEE_DESCRIPTION, null, confirmed.orderId(), conf);
        }
    }

    // ------------------------------------------------------------------ held money

    private Long postHeld(UUID conf, Fact confirmed, Fact held, EventContext ctx) {
        if (confirmed != null && (confirmed.gross().compareTo(held.owedGross()) != 0 || !Objects.equals(confirmed.orderId(), held.orderId()))) {
            anomalies.raise("held-differs-from-confirmation", conf.toString(), Severity.ERROR, Map.of("confirmationId", conf.toString(), "confirmedGross", confirmed.gross().toPlainString(),
                    "heldAmount", held.owedGross().toPlainString(), "sameOrder", Objects.equals(confirmed.orderId(), held.orderId())), ctx.eventId());
        }
        Instant arrived = held.arrivedAt() != null ? held.arrivedAt() : confirmed != null ? confirmed.arrivedAt() : null;
        if (arrived == null) {
            // An event written before the field existed, and the confirmation has not been heard of yet: the day it was learned could be another month, and a row is never moved.
            // Wait: the confirmation, when it arrives, derives this again and books it on the day the money arrived.
            anomalies.raise("held-without-arrival-date", conf.toString(), Severity.ERROR, Map.of("confirmationId", conf.toString()), ctx.eventId());
            return null;
        }
        anomalies.autoResolve("held-without-arrival-date", conf.toString());
        return ledger.post(Entry.of("held:" + conf, "MEMO_HELD_MONEY", held.owedGross(), arrived, calendar.dayOf(arrived), "PAYMENT_HELD", held.actor())
                .sourceRecordedAt(held.sourceRecordedAt()).event(held.eventId(), "payment", null).order(held.orderId()).confirmation(conf)).entryId();
    }

    private void clearHeld(UUID conf, Fact held, Fact voided, long heldEntry) {
        Map<String, Object> memo = jdbc.queryForMap("SELECT occurred_at, effect_day FROM ledger_entry WHERE entry_id = ?", heldEntry);
        ledger.post(Entry.of("held-void:" + conf, "MEMO_HELD_MONEY", held.owedGross().negate(), ((Timestamp) memo.get("occurred_at")).toInstant(),
                ((java.sql.Date) memo.get("effect_day")).toLocalDate(), "PAYMENT_VOIDED", voided.actor()).sourceRecordedAt(voided.sourceRecordedAt())
                .event(voided.eventId(), "payment", null).order(held.orderId()).confirmation(conf).reverses(heldEntry));
    }

    // ------------------------------------------------------------------ reading what is held

    private Fact fact(UUID conf, String kind) {
        List<Fact> found = jdbc.query("""
                SELECT order_id, method, gross, fee, arrived_at, voided_at, held_at, outcome, owed_gross, actor, source_recorded_at, source_event_id
                  FROM confirmation_fact WHERE confirmation_id = ? AND kind = ?""",
                (rs, n) -> new Fact(rs.getObject("order_id", UUID.class), rs.getString("method"), rs.getBigDecimal("gross"), rs.getBigDecimal("fee"), instant(rs.getTimestamp("arrived_at")),
                        instant(rs.getTimestamp("voided_at")), instant(rs.getTimestamp("held_at")), rs.getString("outcome"), rs.getBigDecimal("owed_gross"), rs.getString("actor"),
                        instant(rs.getTimestamp("source_recorded_at")), rs.getObject("source_event_id", UUID.class)), conf, kind);
        return found.isEmpty() ? null : found.get(0);
    }

    private boolean keyExists(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE entry_key = ?", Long.class, key) > 0;
    }

    private boolean reversed(long entryId) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE reverses_entry_id = ?", Long.class, entryId) > 0;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
