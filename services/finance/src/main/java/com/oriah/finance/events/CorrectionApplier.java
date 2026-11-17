package com.oriah.finance.events;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.events.inbound.SaleCorrected;
import com.oriah.finance.ledger.AnomalyService;
import com.oriah.finance.ledger.AnomalyService.Severity;
import com.oriah.finance.ledger.LedgerWriter;
import com.oriah.finance.ledger.LedgerWriter.Entry;
import com.oriah.finance.ledger.Locks;

import tools.jackson.core.JacksonException;

/**
 * Applies {@code order.sale_cancelled} and {@code order.return_recorded} (Phase 12): a paid sale corrected. What the correction takes back is booked as PLAIN rows on the
 * Lusaka day of the CORRECTION, each the negative of what the sale booked for those pieces: goods at list price, what the promotion took off, and (a cancellation before
 * dispatch only) the delivery charge. Never as a reversal of the sale's own rows: the month the sale was reported in does not move, and a return in November reads as a
 * November figure. What is now owed to the customer is a memo beside the books until payment says it was paid out.
 *
 * <p>The event carries every figure it books (price, discount, product and promotion per line), so it needs no earlier fact and is booked the same whichever event finance
 * heard first. What depends on other facts (does it agree with the sale? did more come back than was sold? does payment owe the same?) is worked out afterwards from all the
 * facts held, by {@link CorrectionDerivation}, which the sale and the debt run too.
 *
 * <p>What it refuses (an {@link IllegalArgumentException}, nothing written): anything that is not ZMW, an amount finer than an ngwee or below nothing, lines that do not make
 * the totals, what is owed not being goods less discounts plus delivery, a return that takes back delivery or a waiver, an envelope date that is not the correction's.
 */
@Component
public class CorrectionApplier {

    private final JdbcTemplate jdbc;
    private final Locks locks;
    private final LedgerWriter ledger;
    private final AnomalyService anomalies;
    private final BusinessCalendar calendar;
    private final CorrectionDerivation derivation;

    public CorrectionApplier(JdbcTemplate jdbc, Locks locks, LedgerWriter ledger, AnomalyService anomalies, BusinessCalendar calendar, CorrectionDerivation derivation) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.ledger = ledger;
        this.anomalies = anomalies;
        this.calendar = calendar;
        this.derivation = derivation;
    }

    @Transactional
    public void apply(EventContext ctx, byte[] payload) {
        SaleCorrected c = read(payload);
        validate(ctx, c);

        locks.writer();
        locks.confirmation(c.confirmationId());
        locks.order(c.orderId());

        if (jdbc.queryForObject("SELECT count(*) FROM sale_correction WHERE correction_id = ?", Long.class, c.correctionId()) > 0) {
            if (!sameAsHeld(c)) {
                anomalies.raise("conflicting-duplicate", c.correctionId().toString(), Severity.ERROR,
                        Map.of("correctionId", c.correctionId().toString(), "event", ctx.eventId().toString()), ctx.eventId());
            }
            return;
        }
        remember(ctx, c);
        post(ctx, c);
        derivation.derive(c.orderId(), ctx);
    }

    private void remember(EventContext ctx, SaleCorrected c) {
        jdbc.update("""
                INSERT INTO sale_correction (correction_id, kind, order_id, confirmation_id, sale_arrived_at, occurred_at, goods_at_list, discounts, delivery_refunded,
                                             waiver_taken_back, owed_gross, actor, source_recorded_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                c.correctionId(), c.kind(), c.orderId(), c.confirmationId(), Timestamp.from(c.saleArrivedAt()), Timestamp.from(c.occurredAt()), c.goodsAtList(),
                c.discounts(), c.deliveryRefunded(), c.waiverTakenBack(), c.owedGross(), ctx.actor(), Timestamp.from(recordedAt(ctx, c)), ctx.eventId());
        for (SaleCorrected.Line line : c.lines()) {
            jdbc.update("""
                    INSERT INTO sale_correction_line (correction_id, sale_line_id, variant_id, product_id, quantity, unit_price, discount_per_piece, promotion_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    c.correctionId(), line.lineId(), line.variantId(), line.productId(), line.quantity(), line.unitPrice(), line.discountPerPiece(), line.promotionId());
        }
        jdbc.update("INSERT INTO order_ref (order_id) VALUES (?) ON CONFLICT DO NOTHING", c.orderId());
    }

    private void post(EventContext ctx, SaleCorrected c) {
        var day = calendar.dayOf(c.occurredAt());
        String prefix = "corr:" + c.correctionId() + ":";
        for (SaleCorrected.Line line : c.lines()) {
            BigDecimal pieces = BigDecimal.valueOf(line.quantity());
            ledger.post(base(Entry.of(prefix + "goods:" + line.lineId(), "REVENUE_GOODS", line.unitPrice().multiply(pieces).negate(), c.occurredAt(), day, "SALE_CORRECTED",
                    ctx.actor()), ctx, c).saleLine(line.lineId()).variant(line.variantId()).product(line.productId()).promotion(line.promotionId()));
            BigDecimal off = line.discountPerPiece().multiply(pieces);
            if (off.signum() > 0) {
                ledger.post(base(Entry.of(prefix + "disc:" + line.lineId(), "DISCOUNTS", off.negate(), c.occurredAt(), day, "SALE_CORRECTED", ctx.actor()), ctx, c)
                        .saleLine(line.lineId()).variant(line.variantId()).product(line.productId()).promotion(line.promotionId()));
            }
        }
        if (c.deliveryRefunded().signum() > 0) {
            ledger.post(base(Entry.of(prefix + "delivery", "REVENUE_DELIVERY", c.deliveryRefunded().negate(), c.occurredAt(), day, "SALE_CORRECTED", ctx.actor()), ctx, c));
        }
        if (c.owedGross().signum() > 0) {
            ledger.post(base(Entry.of(prefix + "owed", "MEMO_REFUNDS_OWED", c.owedGross(), c.occurredAt(), day, "SALE_CORRECTED", ctx.actor()), ctx, c));
        }
        // The waiver's memo is taken back by the derivation, once the sale's own memo is known to exist (a sale announced before waivers were announced has none).
    }

    private static Entry base(Entry e, EventContext ctx, SaleCorrected c) {
        return e.sourceRecordedAt(recordedAt(ctx, c)).event(ctx.eventId(), ctx.producer(), ctx.outboxId()).order(c.orderId()).confirmation(c.confirmationId())
                .correction(c.correctionId());
    }

    private static Instant recordedAt(EventContext ctx, SaleCorrected c) {
        return c.recordedAt() != null ? c.recordedAt() : ctx.recordedAt();
    }

    /** Whether a correction announced again is the one already held (the same figures), so that it changes nothing and is no conflict. */
    private boolean sameAsHeld(SaleCorrected c) {
        Map<String, Object> held = jdbc.queryForMap("""
                SELECT kind, order_id, confirmation_id, goods_at_list, discounts, delivery_refunded, waiver_taken_back, owed_gross,
                       (SELECT count(*) FROM sale_correction_line l WHERE l.correction_id = s.correction_id) AS lines,
                       (SELECT coalesce(sum(quantity), 0) FROM sale_correction_line l WHERE l.correction_id = s.correction_id) AS pieces
                  FROM sale_correction s WHERE correction_id = ?""", c.correctionId());
        return c.kind().equals(held.get("kind")) && c.orderId().equals(held.get("order_id")) && c.confirmationId().equals(held.get("confirmation_id"))
                && same(held.get("goods_at_list"), c.goodsAtList()) && same(held.get("discounts"), c.discounts()) && same(held.get("delivery_refunded"), c.deliveryRefunded())
                && same(held.get("waiver_taken_back"), c.waiverTakenBack()) && same(held.get("owed_gross"), c.owedGross())
                && ((Number) held.get("lines")).intValue() == c.lines().size()
                && ((Number) held.get("pieces")).intValue() == c.lines().stream().mapToInt(SaleCorrected.Line::quantity).sum();
    }

    private static boolean same(Object held, BigDecimal announced) {
        return ((BigDecimal) held).compareTo(announced) == 0;
    }

    // ------------------------------------------------------------------ what it refuses

    private static SaleCorrected read(byte[] payload) {
        try {
            return EventJson.mapper().readValue(payload, SaleCorrected.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("a sale correction cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private static void validate(EventContext ctx, SaleCorrected c) {
        need(c.correctionId(), "correctionId");
        need(c.orderId(), "orderId");
        need(c.confirmationId(), "confirmationId");
        need(c.saleArrivedAt(), "saleArrivedAt");
        need(c.occurredAt(), "occurredAt");
        if (!"CANCELLATION".equals(c.kind()) && !"RETURN".equals(c.kind())) {
            throw new IllegalArgumentException("a sale correction is a CANCELLATION or a RETURN, was " + c.kind());
        }
        if (!"ZMW".equals(c.currency())) {
            throw new IllegalArgumentException("a sale correction is in ZMW only, was " + c.currency());
        }
        money(c.goodsAtList(), "goodsAtList");
        money(c.discounts(), "discounts");
        money(c.deliveryRefunded(), "deliveryRefunded");
        money(c.waiverTakenBack(), "waiverTakenBack");
        money(c.owedGross(), "owedGross");
        if (c.lines() == null || c.lines().isEmpty()) {
            throw new IllegalArgumentException("a sale correction takes back at least one piece");
        }
        BigDecimal goods = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        Set<UUID> seen = new HashSet<>();
        for (SaleCorrected.Line line : c.lines()) {
            need(line.lineId(), "a line's lineId");
            need(line.variantId(), "a line's variantId");
            need(line.productId(), "a line's productId");
            money(line.unitPrice(), "a line's unitPrice");
            money(line.discountPerPiece(), "a line's discountPerPiece");
            if (line.quantity() < 1) {
                throw new IllegalArgumentException("a line takes back at least one piece, was " + line.quantity());
            }
            if (line.discountPerPiece().compareTo(line.unitPrice()) > 0) {
                throw new IllegalArgumentException("a discount is never more than the price of the piece");
            }
            if (!seen.add(line.lineId())) {
                throw new IllegalArgumentException("a sale line appears once in a correction");
            }
            goods = goods.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            discounts = discounts.add(line.discountPerPiece().multiply(BigDecimal.valueOf(line.quantity())));
        }
        if (goods.compareTo(c.goodsAtList()) != 0) {
            throw new IllegalArgumentException("the lines add up to " + goods.toPlainString() + " of goods at list price, the correction says " + c.goodsAtList().toPlainString());
        }
        if (discounts.compareTo(c.discounts()) != 0) {
            throw new IllegalArgumentException("the lines add up to " + discounts.toPlainString() + " of discounts, the correction says " + c.discounts().toPlainString());
        }
        if (c.goodsAtList().subtract(c.discounts()).add(c.deliveryRefunded()).compareTo(c.owedGross()) != 0) {
            throw new IllegalArgumentException("what is owed is the goods less the discounts plus the delivery refunded, and " + c.owedGross().toPlainString() + " is not");
        }
        if ("RETURN".equals(c.kind()) && (c.deliveryRefunded().signum() != 0 || c.waiverTakenBack().signum() != 0)) {
            throw new IllegalArgumentException("a return after dispatch never refunds delivery and never takes a waiver back");
        }
        if (ctx.occurredAt() == null || !ctx.occurredAt().truncatedTo(ChronoUnit.MICROS).equals(c.occurredAt().truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("the envelope says the correction happened at " + ctx.occurredAt() + " but the body says occurredAt " + c.occurredAt());
        }
    }

    private static void need(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("a sale correction has no " + name);
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
