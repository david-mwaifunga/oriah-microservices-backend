package com.oriah.finance.events;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.events.inbound.MoneyOut;
import com.oriah.finance.ledger.AnomalyService;
import com.oriah.finance.ledger.AnomalyService.Severity;
import com.oriah.finance.ledger.Locks;

import tools.jackson.core.JacksonException;

/**
 * Applies {@code payment.paid_out} and {@code payment.payout_voided} (Phase 12): money that went out to a customer (a refund, or held money returned), and a payout
 * cancelled in payment. Each is kept as a fact, once, and the books are then worked out from the facts held ({@link PayoutDerivation}), so the order they arrive in does
 * not matter.
 *
 * <p>What it refuses (an {@link IllegalArgumentException}, nothing written): anything that is not ZMW, an amount finer than an ngwee, a net that is not the gross less
 * what the customer bore, a customer bearing more than the provider charged, a purpose that is neither a refund nor held money, an envelope date that is not the day the
 * money went out (or, for a cancellation, the moment it was cancelled).
 */
@Component
public class PayoutApplier {

    private static final Set<String> PURPOSES = Set.of("REFUND", "HELD_MONEY");

    private final JdbcTemplate jdbc;
    private final Locks locks;
    private final AnomalyService anomalies;
    private final PayoutDerivation payouts;
    private final FeeDerivation fees;

    public PayoutApplier(JdbcTemplate jdbc, Locks locks, AnomalyService anomalies, PayoutDerivation payouts, FeeDerivation fees) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.anomalies = anomalies;
        this.payouts = payouts;
        this.fees = fees;
    }

    @Transactional
    public void apply(EventContext ctx, byte[] payload) {
        boolean voided = switch (ctx.type()) {
            case "payment.paid_out" -> false;
            case "payment.payout_voided" -> true;
            default -> throw new IllegalArgumentException("finance does not apply " + ctx.type());
        };
        MoneyOut m = read(payload);
        validate(ctx, m, voided);

        locks.writer();
        locks.confirmation(m.confirmationId());
        locks.order(m.orderId());

        String kind = voided ? "VOIDED" : "PAID_OUT";
        int inserted = jdbc.update("""
                INSERT INTO payout_fact (payout_id, kind, purpose, debt_id, order_id, confirmation_id, method, gross, customer_fee, send_charge, paid_at, voided_at, actor,
                                         source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (payout_id, kind) DO NOTHING""",
                m.payoutId(), kind, m.purpose(), m.debtId(), m.orderId(), m.confirmationId(), m.method(), m.gross(), m.customerFee(), m.sendCharge(),
                Timestamp.from(m.paidAt()), voided ? Timestamp.from(m.voidedAt()) : null, ctx.actor(), ctx.eventId());
        if (inserted == 0) {
            Map<String, Object> held = jdbc.queryForMap("SELECT gross, customer_fee, send_charge, debt_id FROM payout_fact WHERE payout_id = ? AND kind = ?", m.payoutId(), kind);
            if (((BigDecimal) held.get("gross")).compareTo(m.gross()) != 0 || ((BigDecimal) held.get("customer_fee")).compareTo(m.customerFee()) != 0
                    || ((BigDecimal) held.get("send_charge")).compareTo(m.sendCharge()) != 0 || !m.debtId().equals(held.get("debt_id"))) {
                anomalies.raise("conflicting-duplicate", m.payoutId().toString(), Severity.ERROR, Map.of("payoutId", m.payoutId().toString(), "event", ctx.eventId().toString()),
                        ctx.eventId());
            }
        }
        payouts.derive(m.payoutId());
        if ("REFUND".equals(m.purpose())) {
            // A sale whose money record had been cancelled and whose refund is now paid (or un-paid): the open question about that money is answered (or open again).
            fees.derive(m.confirmationId(), ctx);
        }
    }

    private static MoneyOut read(byte[] payload) {
        try {
            return EventJson.mapper().readValue(payload, MoneyOut.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("a payout cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private static void validate(EventContext ctx, MoneyOut m, boolean voided) {
        need(m.payoutId(), "payoutId");
        need(m.debtId(), "debtId");
        need(m.orderId(), "orderId");
        need(m.confirmationId(), "confirmationId");
        need(m.method(), "method");
        need(m.paidAt(), "paidAt");
        if (m.purpose() == null || !PURPOSES.contains(m.purpose())) {
            throw new IllegalArgumentException("a payout is a REFUND or HELD_MONEY, was " + m.purpose());
        }
        if (!"ZMW".equals(m.currency())) {
            throw new IllegalArgumentException("a payout is in ZMW only, was " + m.currency());
        }
        money(m.gross(), "gross");
        money(m.customerFee(), "customerFee");
        money(m.net(), "net");
        money(m.sendCharge(), "sendCharge");
        if (m.gross().signum() <= 0) {
            throw new IllegalArgumentException("a payout pays back more than nothing");
        }
        if (m.gross().subtract(m.customerFee()).compareTo(m.net()) != 0) {
            throw new IllegalArgumentException("what the customer received is the amount paid back less the charge they bore, and " + m.net().toPlainString() + " is not");
        }
        if (m.customerFee().compareTo(m.sendCharge()) > 0) {
            throw new IllegalArgumentException("a customer never bears more than the provider charged to send the money");
        }
        Instant told = voided ? m.voidedAt() : m.paidAt();
        need(told, voided ? "voidedAt" : "paidAt");
        if (ctx.occurredAt() == null || !ctx.occurredAt().truncatedTo(ChronoUnit.MICROS).equals(told.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("the envelope says " + ctx.occurredAt() + " but the body says " + told);
        }
    }

    private static void need(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("a payout has no " + name);
        }
    }

    private static void money(BigDecimal amount, String name) {
        need(amount, name);
        SaleApplier.ngwee(amount, name);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(name + " is not below nothing, was " + amount.toPlainString());
        }
    }
}
