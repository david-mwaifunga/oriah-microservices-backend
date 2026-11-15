package com.oriah.finance.events;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.events.inbound.PaymentConfirmed;
import com.oriah.finance.events.inbound.PaymentHeld;
import com.oriah.finance.events.inbound.PaymentVoided;
import com.oriah.finance.ledger.AnomalyService;
import com.oriah.finance.ledger.AnomalyService.Severity;
import com.oriah.finance.ledger.Locks;

import tools.jackson.core.JacksonException;

/**
 * Applies what payment says about money: {@code payment.confirmed} (it came in), {@code payment.voided} (it was taken back) and {@code payment.held} (it could not pay a
 * sale and is owed back). Each records its FACT, then {@link FeeDerivation} works out what the books say about that confirmation from every fact held, under the
 * confirmation's lock: so the three facts can arrive in any order, from different topics, and the books agree.
 *
 * <p>Refused whole (an {@link IllegalArgumentException}, nothing written): anything that is not ZMW, an amount finer than an ngwee or below nothing, a net that is not gross less
 * fee, an envelope that does not say the day the money arrived. A confirmation announced again with other figures is raised ({@code confirmation-differs}); the first stands.
 * The reference, the staff member and every reason are never read.
 */
@Component
public class PaymentApplier {

    private final JdbcTemplate jdbc;
    private final Locks locks;
    private final FeeDerivation derivation;
    private final AnomalyService anomalies;

    public PaymentApplier(JdbcTemplate jdbc, Locks locks, FeeDerivation derivation, AnomalyService anomalies) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.derivation = derivation;
        this.anomalies = anomalies;
    }

    @Transactional
    public void apply(EventContext ctx, byte[] payload) {
        switch (ctx.type()) {
            case "payment.confirmed" -> confirmed(ctx, read(payload, PaymentConfirmed.class));
            case "payment.voided" -> voided(ctx, read(payload, PaymentVoided.class));
            case "payment.held" -> held(ctx, read(payload, PaymentHeld.class));
            default -> throw new IllegalArgumentException("finance does not apply " + ctx.type());
        }
    }

    private void confirmed(EventContext ctx, PaymentConfirmed p) {
        need(p.confirmationId(), "confirmationId");
        need(p.orderId(), "orderId");
        need(p.arrivedAt(), "arrivedAt");
        currency(p.currency());
        money(p.gross(), "gross", true);
        money(p.fee(), "fee", false);
        money(p.net(), "net", false);
        if (p.gross().subtract(p.fee()).compareTo(p.net()) != 0) {
            throw new IllegalArgumentException("the net of the payment is not its gross less its fee");
        }
        if (ctx.occurredAt() == null || !ctx.occurredAt().truncatedTo(ChronoUnit.MICROS).equals(p.arrivedAt().truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("the envelope says the money arrived at " + ctx.occurredAt() + " but the body says arrivedAt " + p.arrivedAt());
        }
        locks.writer();
        locks.confirmation(p.confirmationId());
        int inserted = jdbc.update("""
                INSERT INTO confirmation_fact (confirmation_id, kind, order_id, method, gross, fee, arrived_at, actor, source_recorded_at, source_event_id)
                VALUES (?, 'CONFIRMED', ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (confirmation_id, kind) DO NOTHING""",
                p.confirmationId(), p.orderId(), p.method(), p.gross(), p.fee(), Timestamp.from(p.arrivedAt()), ctx.actor(), instant(ctx.recordedAt()), ctx.eventId());
        if (inserted == 0) {
            Map<String, Object> held = jdbc.queryForMap("SELECT gross, fee, order_id FROM confirmation_fact WHERE confirmation_id = ? AND kind = 'CONFIRMED'", p.confirmationId());
            if (((BigDecimal) held.get("gross")).compareTo(p.gross()) != 0 || ((BigDecimal) held.get("fee")).compareTo(p.fee()) != 0 || !p.orderId().equals(held.get("order_id"))) {
                anomalies.raise("confirmation-differs", p.confirmationId().toString(), Severity.ERROR,
                        Map.of("confirmationId", p.confirmationId().toString(), "attempt", p.attempt()), ctx.eventId());
            }
        }
        derivation.derive(p.confirmationId(), ctx);
    }

    private void voided(EventContext ctx, PaymentVoided p) {
        need(p.confirmationId(), "confirmationId");
        need(p.orderId(), "orderId");
        need(p.voidedAt(), "voidedAt");
        currency(p.currency());
        money(p.gross(), "gross", true);
        money(p.fee(), "fee", false);
        money(p.net(), "net", false);
        if (!"PENDING".equals(p.outcome()) && !"HELD".equals(p.outcome())) {
            throw new IllegalArgumentException("a confirmation is voided while PENDING or HELD, was " + p.outcome());
        }
        locks.writer();
        locks.confirmation(p.confirmationId());
        jdbc.update("""
                INSERT INTO confirmation_fact (confirmation_id, kind, order_id, method, gross, fee, arrived_at, voided_at, outcome, actor, source_recorded_at, source_event_id)
                VALUES (?, 'VOIDED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (confirmation_id, kind) DO NOTHING""",
                p.confirmationId(), p.orderId(), p.method(), p.gross(), p.fee(), instant(p.arrivedAt()), Timestamp.from(p.voidedAt()), p.outcome(), ctx.actor(),
                instant(ctx.recordedAt()), ctx.eventId());
        derivation.derive(p.confirmationId(), ctx);
    }

    private void held(EventContext ctx, PaymentHeld p) {
        need(p.confirmationId(), "confirmationId");
        need(p.orderId(), "orderId");
        need(p.heldAt(), "heldAt");
        currency(p.currency());
        money(p.owedGross(), "owedGross", true);
        locks.writer();
        locks.confirmation(p.confirmationId());
        jdbc.update("""
                INSERT INTO confirmation_fact (confirmation_id, kind, order_id, owed_gross, arrived_at, held_at, held_reason, actor, source_recorded_at, source_event_id)
                VALUES (?, 'HELD', ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (confirmation_id, kind) DO NOTHING""",
                p.confirmationId(), p.orderId(), p.owedGross(), instant(p.arrivedAt()), Timestamp.from(p.heldAt()), p.reason(), ctx.actor(), instant(ctx.recordedAt()), ctx.eventId());
        derivation.derive(p.confirmationId(), ctx);
    }

    // ------------------------------------------------------------------ what it refuses

    private static <T> T read(byte[] payload, Class<T> type) {
        try {
            return EventJson.mapper().readValue(payload, type);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("a payment event cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private static void need(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("a payment event has no " + name);
        }
    }

    private static void currency(String currency) {
        if (!"ZMW".equals(currency)) {
            throw new IllegalArgumentException("payment events are in ZMW only, was " + currency);
        }
    }

    private static void money(BigDecimal amount, String name, boolean positive) {
        need(amount, name);
        SaleApplier.ngwee(amount, name);
        if (positive ? amount.signum() <= 0 : amount.signum() < 0) {
            throw new IllegalArgumentException(name + (positive ? " is above nothing" : " is not below nothing") + ", was " + amount.toPlainString());
        }
    }

    private static Timestamp instant(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    @SuppressWarnings("unused")
    private static UUID unused() {
        return null;
    }
}
