package com.oriah.finance.events;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.events.inbound.OrderPaid;
import com.oriah.finance.ledger.AnomalyService;
import com.oriah.finance.ledger.AnomalyService.Severity;
import com.oriah.finance.ledger.LedgerWriter;
import com.oriah.finance.ledger.LedgerWriter.Entry;
import com.oriah.finance.ledger.Locks;

import tools.jackson.core.JacksonException;

/**
 * Applies {@code order.paid} (version 2): a sale. The goods at list price, what a promotion took off, and the delivery the shop collected are each a row of the ledger on the
 * Lusaka day the money ARRIVED, with the product frozen on the row; a delivery the shop gave up is a memo beside it. The sale is remembered as facts (the sale and its lines,
 * the confirmation that paid it) so everything else finance derives (a fee, a void, held money) is worked out from all that it has seen, whichever event came first.
 *
 * <p>What it refuses (an {@link IllegalArgumentException}, nothing written): anything that is not ZMW, an amount finer than an ngwee, lines that do not make the total, the
 * subtotal, the discount and what was paid, an envelope date that is not the day the money arrived, a charge for delivery the recipient pays. What it raises and
 * carries on from: the same confirmation announced with different figures, a second confirmation for an order already paid, a sale recovered from a republished
 * announcement. A sale is booked once however often it is told.
 */
@Component
public class SaleApplier {

    private final JdbcTemplate jdbc;
    private final Locks locks;
    private final LedgerWriter ledger;
    private final AnomalyService anomalies;
    private final BusinessCalendar calendar;

    public SaleApplier(JdbcTemplate jdbc, Locks locks, LedgerWriter ledger, AnomalyService anomalies, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.ledger = ledger;
        this.anomalies = anomalies;
        this.calendar = calendar;
    }

    @Transactional
    public void apply(EventContext ctx, byte[] payload) {
        if (ctx.schemaVersion() < 2) {
            setAsideLegacy(ctx);
            return;
        }
        OrderPaid p = read(payload);
        validate(ctx, p);

        locks.writer();
        locks.confirmation(p.confirmationId());
        locks.order(p.orderId());

        List<UUID> others = jdbc.queryForList("SELECT confirmation_id FROM sale WHERE order_id = ? AND confirmation_id <> ?", UUID.class, p.orderId(), p.confirmationId());
        if (!others.isEmpty()) {
            anomalies.raise("second-confirmation-for-order", p.orderId().toString(), Severity.ERROR,
                    Map.of("orderId", p.orderId().toString(), "paidBy", others.get(0).toString(), "alsoAnnouncedBy", p.confirmationId().toString()), ctx.eventId());
            return;
        }
        if (jdbc.queryForObject("SELECT count(*) FROM sale WHERE confirmation_id = ?", Long.class, p.confirmationId()) > 0) {
            if (!sameAsHeld(p)) {
                anomalies.raise("conflicting-duplicate", p.confirmationId().toString(), Severity.ERROR,
                        Map.of("confirmationId", p.confirmationId().toString(), "event", ctx.eventId().toString()), ctx.eventId());
            }
            return;
        }

        remember(ctx, p);
        post(ctx, p);
        if (p.republished()) {
            anomalies.raise("recovered-from-republish", p.confirmationId().toString(), Severity.WARN,
                    Map.of("orderId", p.orderId().toString(), "confirmationId", p.confirmationId().toString()), ctx.eventId());
        }
    }

    // ------------------------------------------------------------------ what a sale is

    private void remember(EventContext ctx, OrderPaid p) {
        BigDecimal discount = p.discountAmount();
        jdbc.update("""
                INSERT INTO sale (confirmation_id, order_id, schema_version, arrived_at, sale_occurred_at, source_recorded_at, channel, payment_method, gross, fee, net,
                                  shipping, discount_total, waived_amount, delivery_payer, delivery_option, delivery_town_id, delivery_price_source, agreed_amount,
                                  quoted_amount, receipt_number, recovered_from_republish, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                p.confirmationId(), p.orderId(), ctx.schemaVersion(), Timestamp.from(p.arrivedAt()), instant(p.occurredAt()), instant(sourceRecordedAt(ctx, p)), p.channel(),
                p.paymentMethod(), p.paymentGross(), p.paymentFee(), p.paymentNet(), p.shippingAmount(), discount, p.deliveryWaivedAmount(), p.deliveryPayer(),
                p.deliveryOption(), p.deliveryTownId(), p.deliveryPriceSource(), p.deliveryAgreedAmount(), p.deliveryQuotedAmount(), p.receiptNumber(), p.republished(),
                ctx.eventId());
        for (OrderPaid.Line line : p.lines()) {
            jdbc.update("""
                    INSERT INTO sale_line (sale_line_id, confirmation_id, variant_id, product_id, quantity, list_price, discount_per_piece, promotion_id, title)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    line.lineId(), p.confirmationId(), line.variantId(), line.productId(), line.quantity(), line.unitPrice(), discountOf(line), line.promotionId(),
                    line.title());
        }
        jdbc.update("INSERT INTO confirmation_fact (confirmation_id, kind, order_id, method, gross, fee, arrived_at, source_event_id) VALUES (?, 'SALE', ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING", p.confirmationId(), p.orderId(), p.paymentMethod(), p.paymentGross(), p.paymentFee(), Timestamp.from(p.arrivedAt()), ctx.eventId());
        jdbc.update("INSERT INTO order_ref (order_id, channel) VALUES (?, ?) ON CONFLICT DO NOTHING", p.orderId(), p.channel());
    }

    private void post(EventContext ctx, OrderPaid p) {
        var day = calendar.dayOf(p.arrivedAt());
        Instant recorded = sourceRecordedAt(ctx, p);
        String prefix = "sale:" + p.confirmationId() + ":";
        for (OrderPaid.Line line : p.lines()) {
            BigDecimal goods = line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()));
            ledger.post(base(Entry.of(prefix + "goods:" + line.lineId(), "REVENUE_GOODS", goods, p.arrivedAt(), day, "ORDER_PAID", ctx.actor()), ctx, p, recorded)
                    .saleLine(line.lineId()).variant(line.variantId()).product(line.productId()).promotion(line.promotionId()));
            BigDecimal off = discountOf(line).multiply(BigDecimal.valueOf(line.quantity()));
            if (off.signum() > 0) {
                ledger.post(base(Entry.of(prefix + "disc:" + line.lineId(), "DISCOUNTS", off, p.arrivedAt(), day, "ORDER_PAID", ctx.actor()), ctx, p, recorded)
                        .saleLine(line.lineId()).variant(line.variantId()).product(line.productId()).promotion(line.promotionId()));
            }
        }
        if (p.shippingAmount().signum() > 0) {
            ledger.post(base(Entry.of(prefix + "delivery", "REVENUE_DELIVERY", p.shippingAmount(), p.arrivedAt(), day, "ORDER_PAID", ctx.actor()), ctx, p, recorded));
        }
        if (p.deliveryWaivedAmount() != null && p.deliveryWaivedAmount().signum() > 0) {
            ledger.post(base(Entry.of(prefix + "waived", "MEMO_DELIVERY_WAIVED", p.deliveryWaivedAmount(), p.arrivedAt(), day, "ORDER_PAID", ctx.actor()), ctx, p, recorded));
        }
    }

    private static Entry base(Entry e, EventContext ctx, OrderPaid p, Instant recorded) {
        return e.sourceRecordedAt(recorded).event(ctx.eventId(), ctx.producer(), ctx.outboxId()).order(p.orderId()).confirmation(p.confirmationId());
    }

    /** Whether a sale announced again is the sale already held (the same figures), so that it changes nothing and is no conflict. */
    private boolean sameAsHeld(OrderPaid p) {
        Map<String, Object> held = jdbc.queryForMap("SELECT gross, fee, shipping, discount_total, "
                + "(SELECT coalesce(sum(list_price * quantity), 0) FROM sale_line l WHERE l.confirmation_id = s.confirmation_id) AS goods, "
                + "(SELECT count(*) FROM sale_line l WHERE l.confirmation_id = s.confirmation_id) AS lines FROM sale s WHERE confirmation_id = ?", p.confirmationId());
        BigDecimal goods = p.lines().stream().map(l -> l.unitPrice().multiply(BigDecimal.valueOf(l.quantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        return ((BigDecimal) held.get("gross")).compareTo(p.paymentGross()) == 0 && ((BigDecimal) held.get("fee")).compareTo(p.paymentFee()) == 0
                && ((BigDecimal) held.get("shipping")).compareTo(p.shippingAmount()) == 0 && ((BigDecimal) held.get("discount_total")).compareTo(p.discountAmount()) == 0
                && ((BigDecimal) held.get("goods")).compareTo(goods) == 0 && ((Number) held.get("lines")).intValue() == p.lines().size();
    }

    private void setAsideLegacy(EventContext ctx) {
        jdbc.update("INSERT INTO skipped_fact (event_id, producer, event_type, reason) VALUES (?, ?, ?, 'LEGACY_NO_CONFIRMATION') ON CONFLICT (event_id) DO NOTHING",
                ctx.eventId(), ctx.producer(), ctx.type());
        anomalies.raise("legacy-order-paid-v1", ctx.aggregateId(), Severity.ERROR, Map.of("eventId", ctx.eventId().toString()), ctx.eventId());
    }

    // ------------------------------------------------------------------ what it refuses

    private static OrderPaid read(byte[] payload) {
        try {
            return EventJson.mapper().readValue(payload, OrderPaid.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("order.paid cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private void validate(EventContext ctx, OrderPaid p) {
        need(p.orderId(), "orderId");
        need(p.confirmationId(), "confirmationId");
        need(p.arrivedAt(), "arrivedAt");
        need(p.total(), "total");
        need(p.subtotal(), "subtotal");
        need(p.shippingAmount(), "shippingAmount");
        need(p.discountAmount(), "discountAmount");
        need(p.paymentGross(), "paymentGross");
        need(p.paymentFee(), "paymentFee");
        need(p.paymentNet(), "paymentNet");
        if (!"ZMW".equals(p.currency())) {
            throw new IllegalArgumentException("order.paid is in ZMW only, was " + p.currency());
        }
        if (p.lines() == null || p.lines().isEmpty()) {
            throw new IllegalArgumentException("order.paid has no lines");
        }
        for (BigDecimal amount : List.of(p.total(), p.subtotal(), p.shippingAmount(), p.discountAmount(), p.paymentGross(), p.paymentFee(), p.paymentNet())) {
            ngwee(amount, "an amount");
        }
        BigDecimal goods = BigDecimal.ZERO;
        BigDecimal off = BigDecimal.ZERO;
        for (OrderPaid.Line line : p.lines()) {
            need(line.lineId(), "lines.lineId");
            need(line.variantId(), "lines.variantId");
            need(line.unitPrice(), "lines.unitPrice");
            if (line.quantity() <= 0) {
                throw new IllegalArgumentException("a line sells at least one piece, was " + line.quantity());
            }
            ngwee(line.unitPrice(), "a unit price");
            BigDecimal discount = discountOf(line);
            ngwee(discount, "a discount");
            if (line.unitPrice().signum() < 0 || discount.signum() < 0 || discount.compareTo(line.unitPrice()) > 0) {
                throw new IllegalArgumentException("a line's price is not below nothing and its discount is not more than the price");
            }
            goods = goods.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            off = off.add(discount.multiply(BigDecimal.valueOf(line.quantity())));
        }
        if (p.shippingAmount().signum() < 0) {
            throw new IllegalArgumentException("delivery is not below nothing");
        }
        if (goods.compareTo(p.subtotal()) != 0) {
            throw new IllegalArgumentException("the lines at list price are " + goods.toPlainString() + " but the subtotal is " + p.subtotal().toPlainString());
        }
        if (off.compareTo(p.discountAmount()) != 0) {
            throw new IllegalArgumentException("the lines' discounts are " + off.toPlainString() + " but the discount is " + p.discountAmount().toPlainString());
        }
        BigDecimal total = goods.subtract(off).add(p.shippingAmount());
        if (total.compareTo(p.total()) != 0) {
            throw new IllegalArgumentException("the lines and delivery make " + total.toPlainString() + " but the total is " + p.total().toPlainString());
        }
        if (p.paymentGross().compareTo(p.total()) != 0) {
            throw new IllegalArgumentException("the money that paid it is " + p.paymentGross().toPlainString() + " but the total is " + p.total().toPlainString());
        }
        if (p.paymentGross().subtract(p.paymentFee()).compareTo(p.paymentNet()) != 0) {
            throw new IllegalArgumentException("the net of the payment is not its gross less its fee");
        }
        if ("RECIPIENT_PAYS".equals(p.deliveryPayer()) && p.shippingAmount().signum() != 0) {
            throw new IllegalArgumentException("a delivery the recipient pays the courier for is no charge of ours, but delivery is " + p.shippingAmount().toPlainString());
        }
        if (p.deliveryWaivedAmount() != null) {
            ngwee(p.deliveryWaivedAmount(), "a waived delivery");
            if (p.deliveryWaivedAmount().signum() < 0 || (p.deliveryWaivedAmount().signum() > 0 && p.shippingAmount().signum() != 0)) {
                throw new IllegalArgumentException("a delivery given up is charged nothing");
            }
        }
        if (ctx.occurredAt() == null || !ctx.occurredAt().truncatedTo(ChronoUnit.MICROS).equals(p.arrivedAt().truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("the envelope says the sale happened at " + ctx.occurredAt() + " but the body says the money arrived (arrivedAt) at " + p.arrivedAt());
        }
    }

    private static BigDecimal discountOf(OrderPaid.Line line) {
        return line.discountPerPiece() == null ? BigDecimal.ZERO.setScale(2) : line.discountPerPiece();
    }

    private static Instant sourceRecordedAt(EventContext ctx, OrderPaid p) {
        return p.recordedAt() != null ? p.recordedAt() : ctx.recordedAt();
    }

    private static Timestamp instant(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static void need(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("order.paid has no " + name);
        }
    }

    static void ngwee(BigDecimal amount, String what) {
        try {
            amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException finer) {
            throw new IllegalArgumentException(what + " is a whole number of ngwee, was " + amount.toPlainString());
        }
    }
}
