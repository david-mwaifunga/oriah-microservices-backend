package com.oriah.finance.events;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.events.inbound.RefundClosed;
import com.oriah.finance.events.inbound.RefundDueRecorded;
import com.oriah.finance.ledger.AnomalyService;
import com.oriah.finance.ledger.AnomalyService.Severity;
import com.oriah.finance.ledger.Locks;

import tools.jackson.core.JacksonException;

/**
 * Applies what payment says about a refund that is owed (Phase 12).
 *
 * <p>{@code payment.refund_due_recorded}: the debt. It books nothing (the correction already put what is owed beside the books); finance keeps it so the two services can
 * be checked against each other, to the ngwee.
 *
 * <p>{@code payment.refund_closed}: a debt closed without a payout, because the money it would have returned never arrived (the money record was cancelled after it had
 * paid the sale, and the owner confirmed nothing came in). What was owed is cleared, and the provider's fee on that money leaves the books: a fee on money that never
 * arrived was never charged. Both are worked out from the facts held, so the order the facts arrived in does not matter.
 */
@Component
public class RefundDueApplier {

    private static final Set<String> STATUSES = Set.of("OWED", "IN_QUESTION");

    private final JdbcTemplate jdbc;
    private final Locks locks;
    private final AnomalyService anomalies;
    private final CorrectionDerivation corrections;
    private final FeeDerivation fees;

    public RefundDueApplier(JdbcTemplate jdbc, Locks locks, AnomalyService anomalies, CorrectionDerivation corrections, FeeDerivation fees) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.anomalies = anomalies;
        this.corrections = corrections;
        this.fees = fees;
    }

    @Transactional
    public void apply(EventContext ctx, byte[] payload) {
        switch (ctx.type()) {
            case "payment.refund_due_recorded" -> recorded(ctx, read(payload, RefundDueRecorded.class));
            case "payment.refund_closed" -> closed(ctx, read(payload, RefundClosed.class));
            default -> throw new IllegalArgumentException("finance does not apply " + ctx.type());
        }
    }

    private void recorded(EventContext ctx, RefundDueRecorded d) {
        need(d.dueId(), "dueId");
        need(d.orderId(), "orderId");
        need(d.confirmationId(), "confirmationId");
        need(d.owedSince(), "owedSince");
        money(d.owedGross(), d.currency());
        if (d.status() == null || !STATUSES.contains(d.status())) {
            throw new IllegalArgumentException("a refund is OWED or IN_QUESTION, was " + d.status());
        }
        locks.writer();
        locks.confirmation(d.confirmationId());
        locks.order(d.orderId());
        int inserted = jdbc.update("""
                INSERT INTO refund_due_fact (due_id, order_id, confirmation_id, owed_gross, status, owed_since, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (due_id) DO NOTHING""",
                d.dueId(), d.orderId(), d.confirmationId(), d.owedGross(), d.status(), Timestamp.from(d.owedSince()), ctx.eventId());
        if (inserted == 0) {
            BigDecimal held = jdbc.queryForObject("SELECT owed_gross FROM refund_due_fact WHERE due_id = ?", BigDecimal.class, d.dueId());
            if (held.compareTo(d.owedGross()) != 0) {
                anomalies.raise("conflicting-duplicate", d.dueId().toString(), Severity.ERROR, Map.of("dueId", d.dueId().toString(), "event", ctx.eventId().toString()),
                        ctx.eventId());
            }
        }
        corrections.derive(d.orderId(), ctx);
    }

    private void closed(EventContext ctx, RefundClosed c) {
        need(c.dueId(), "dueId");
        need(c.orderId(), "orderId");
        need(c.confirmationId(), "confirmationId");
        need(c.closedAt(), "closedAt");
        money(c.owedGross(), c.currency());
        locks.writer();
        locks.confirmation(c.confirmationId());
        locks.order(c.orderId());
        jdbc.update("""
                INSERT INTO refund_close_fact (due_id, order_id, confirmation_id, owed_gross, closed_at, actor, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (due_id) DO NOTHING""",
                c.dueId(), c.orderId(), c.confirmationId(), c.owedGross(), Timestamp.from(c.closedAt()), ctx.actor(), ctx.eventId());
        // What was owed is cleared once the correction is known; the fee on money that never arrived leaves the books, whichever fee row stands.
        corrections.derive(c.orderId(), ctx);
        fees.derive(c.confirmationId(), ctx);
    }

    private static <T> T read(byte[] payload, Class<T> type) {
        try {
            return EventJson.mapper().readValue(payload, type);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("a refund fact cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private static void need(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("a refund fact has no " + name);
        }
    }

    private static void money(BigDecimal amount, String currency) {
        need(amount, "owedGross");
        if (!"ZMW".equals(currency)) {
            throw new IllegalArgumentException("a refund is in ZMW only, was " + currency);
        }
        SaleApplier.ngwee(amount, "owedGross");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("what is owed is more than nothing, was " + amount.toPlainString());
        }
    }
}
