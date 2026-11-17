package com.oriah.finance.events;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.ledger.ExpenseRecords;
import com.oriah.finance.ledger.LedgerWriter;
import com.oriah.finance.ledger.LedgerWriter.Entry;

/**
 * What the books say about money that went out (Phase 12), worked out from the facts held about one payout, after each fact lands: that it was paid out, that it was
 * cancelled, and the thing it pays back (a correction's debt, or money held for a customer). They reach finance from different topics in any order.
 *
 * <ul>
 *   <li>what the provider charged Oriah to send the money is a payment charge, and the part the customer bore is a reduction, both on the day it was paid: booked at
 *       once, whatever else is known;</li>
 *   <li>the debt (or the held money) is cleared only once finance knows it was owed (or held), so what is owed, and what is held, never reads below nothing;</li>
 *   <li>a payout cancelled in payment negates exactly what the payout booked, on the PAYOUT's day; one that is heard of first waits for its payout.</li>
 * </ul>
 *
 * No row of a payout names a confirmation: the sending charge of a refund must never be read as the fee of the payment that came in. Rows only ever add, each under a
 * key of its own, so running it again changes nothing.
 */
@Component
public class PayoutDerivation {

    static final String SEND_DESCRIPTION = "Charge for sending money back to a customer";
    static final String RECOVERY_DESCRIPTION = "Sending charge borne by the customer";
    static final String VOID_REASON = "payout cancelled in payment";

    private final JdbcTemplate jdbc;
    private final LedgerWriter ledger;
    private final ExpenseRecords expenses;
    private final BusinessCalendar calendar;

    public PayoutDerivation(JdbcTemplate jdbc, LedgerWriter ledger, ExpenseRecords expenses, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.expenses = expenses;
        this.calendar = calendar;
    }

    private record Fact(String purpose, UUID debtId, UUID orderId, UUID confirmationId, BigDecimal gross, BigDecimal customerFee, BigDecimal sendCharge, Instant paidAt,
                        String actor, UUID eventId) {
    }

    /** Every payout of an order: run when something a payout may be waiting for has landed (a correction). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deriveForOrder(UUID orderId) {
        jdbc.queryForList("SELECT DISTINCT payout_id FROM payout_fact WHERE order_id = ?", UUID.class, orderId).forEach(this::derive);
    }

    /** Every payout of a confirmation: run when its held money has been booked. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deriveForConfirmation(UUID confirmationId) {
        jdbc.queryForList("SELECT DISTINCT payout_id FROM payout_fact WHERE confirmation_id = ?", UUID.class, confirmationId).forEach(this::derive);
    }

    /** The caller holds the writer, confirmation and order locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void derive(UUID payoutId) {
        Fact paid = fact(payoutId, "PAID_OUT");
        if (paid == null) {
            return;   // a cancellation heard of before its payout: nothing to negate yet
        }
        LocalDate day = calendar.dayOf(paid.paidAt());
        String prefix = "payout:" + payoutId + ":";

        if (paid.sendCharge().signum() > 0) {
            var posted = ledger.post(row(prefix + "send", "EXPENSE", paid.sendCharge(), paid, day, payoutId).category("PAYMENT_CHARGES"));
            if (posted.inserted()) {
                expenses.postedForPayout(posted.entryId(), "ADD", "PAYMENT_CHARGES", SEND_DESCRIPTION, null, paid.orderId(), payoutId);
            }
        }
        if (paid.customerFee().signum() > 0) {
            var posted = ledger.post(row(prefix + "recovery", "EXPENSE", paid.customerFee().negate(), paid, day, payoutId).category("REFUND_FEE_RECOVERED"));
            if (posted.inserted()) {
                expenses.postedForPayout(posted.entryId(), "RECOVERY", "REFUND_FEE_RECOVERED", RECOVERY_DESCRIPTION, null, paid.orderId(), payoutId);
            }
        }
        if ("REFUND".equals(paid.purpose())) {
            // The debt's id is the correction's.
            if (jdbc.queryForObject("SELECT count(*) FROM sale_correction WHERE correction_id = ?", Long.class, paid.debtId()) > 0) {
                ledger.post(row(prefix + "owed", "MEMO_REFUNDS_OWED", paid.gross().negate(), paid, day, payoutId).correction(paid.debtId()));
            }
        } else if (keyExists("held:" + paid.confirmationId())) {
            ledger.post(row(prefix + "held", "MEMO_HELD_MONEY", paid.gross().negate(), paid, day, payoutId));
        }

        Fact voided = fact(payoutId, "VOIDED");
        if (voided != null) {
            for (String tail : List.of("owed", "held", "send", "recovery")) {
                negate(payoutId, tail, paid, voided);
            }
        }
    }

    private Entry row(String key, String account, BigDecimal amount, Fact paid, LocalDate day, UUID payoutId) {
        return Entry.of(key, account, amount, paid.paidAt(), day, "PAID_OUT", paid.actor()).event(paid.eventId(), "payment", null).order(paid.orderId()).payout(payoutId);
    }

    /** The exact negative of one row the payout booked, on that row's own day, once. */
    private void negate(UUID payoutId, String tail, Fact paid, Fact voided) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT entry_id, account, category, amount, occurred_at, effect_day, correction_id FROM ledger_entry l
                 WHERE entry_key = ? AND NOT EXISTS (SELECT 1 FROM ledger_entry r WHERE r.reverses_entry_id = l.entry_id)""", "payout:" + payoutId + ":" + tail);
        if (found.isEmpty()) {
            return;
        }
        Map<String, Object> original = found.get(0);
        String category = (String) original.get("category");
        var posted = ledger.post(Entry.of("payout-void:" + payoutId + ":" + tail, (String) original.get("account"), ((BigDecimal) original.get("amount")).negate(),
                ((Timestamp) original.get("occurred_at")).toInstant(), ((java.sql.Date) original.get("effect_day")).toLocalDate(), "PAYOUT_VOIDED", voided.actor())
                .category(category).event(voided.eventId(), "payment", null).order(paid.orderId()).payout(payoutId).correction((UUID) original.get("correction_id"))
                .reverses(((Number) original.get("entry_id")).longValue()));
        if (category != null && posted.inserted()) {
            expenses.postedForPayout(posted.entryId(), "REVERSAL", category, "PAYMENT_CHARGES".equals(category) ? SEND_DESCRIPTION : RECOVERY_DESCRIPTION, VOID_REASON,
                    paid.orderId(), payoutId);
        }
    }

    private Fact fact(UUID payoutId, String kind) {
        List<Fact> found = jdbc.query("""
                SELECT purpose, debt_id, order_id, confirmation_id, gross, customer_fee, send_charge, paid_at, actor, source_event_id
                  FROM payout_fact WHERE payout_id = ? AND kind = ?""",
                (rs, n) -> new Fact(rs.getString("purpose"), rs.getObject("debt_id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getObject("confirmation_id", UUID.class), rs.getBigDecimal("gross"), rs.getBigDecimal("customer_fee"), rs.getBigDecimal("send_charge"),
                        rs.getTimestamp("paid_at").toInstant(), rs.getString("actor"), rs.getObject("source_event_id", UUID.class)), payoutId, kind);
        return found.isEmpty() ? null : found.get(0);
    }

    private boolean keyExists(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE entry_key = ?", Long.class, key) > 0;
    }
}
