package com.oriah.finance.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import com.oriah.events.EventHeaders;

/**
 * Reads the envelope of a message and hands its body to the applier that owns the event type. An applier is only ever reached for a type the declaration HANDLES (an ignored
 * type is marked without running anything), so a type with no applier here is a defect, not an event: it is refused.
 */
@Component
public class FinanceDispatcher {

    private final SaleApplier sales;
    private final FareApplier fares;
    private final PaymentApplier payments;
    private final PeriodCostApplier periods;
    private final StockCostApplier stock;
    private final FactApplier facts;
    private final CorrectionApplier corrections;
    private final RefundDueApplier refundDues;

    public FinanceDispatcher(SaleApplier sales, FareApplier fares, PaymentApplier payments, PeriodCostApplier periods, StockCostApplier stock, FactApplier facts,
                             CorrectionApplier corrections, RefundDueApplier refundDues) {
        this.sales = sales;
        this.fares = fares;
        this.payments = payments;
        this.periods = periods;
        this.stock = stock;
        this.facts = facts;
        this.corrections = corrections;
        this.refundDues = refundDues;
    }

    public void apply(Message<byte[]> message) {
        EventContext ctx = context(message);
        byte[] body = message.getPayload();
        switch (ctx.type()) {
            case "order.paid" -> sales.apply(ctx, body);
            case "order.shipped" -> fares.apply(ctx, body);
            case "order.created" -> facts.orderCreated(ctx, body);
            case "payment.confirmed", "payment.voided", "payment.held" -> payments.apply(ctx, body);
            case "order.sale_cancelled", "order.return_recorded" -> corrections.apply(ctx, body);
            case "payment.refund_due_recorded", "payment.refund_closed" -> refundDues.apply(ctx, body);
            case "consignment.period_costs" -> periods.apply(ctx, body);
            case "stock.cost_recorded" -> stock.apply(ctx, body);
            case "consignment.received", "consignment.amended" -> facts.consignmentLine(ctx, body);
            case "product.created", "product.updated", "product.deleted" -> facts.product(ctx, body);
            default -> throw new IllegalStateException("no handling for declared event type " + ctx.type());
        }
    }

    static EventContext context(Message<?> message) {
        return new EventContext(UUID.fromString(header(message, EventHeaders.EVENT_ID)), header(message, EventHeaders.EVENT_TYPE), header(message, EventHeaders.AGGREGATE_TYPE),
                header(message, EventHeaders.AGGREGATE_ID), whole(header(message, EventHeaders.SEQ), EventHeaders.SEQ), whole(header(message, EventHeaders.OUTBOX_ID), EventHeaders.OUTBOX_ID),
                instant(header(message, EventHeaders.OCCURRED_AT), EventHeaders.OCCURRED_AT), instant(header(message, EventHeaders.RECORDED_AT), EventHeaders.RECORDED_AT),
                header(message, EventHeaders.ACTOR), header(message, EventHeaders.PRODUCER), (int) whole(header(message, EventHeaders.SCHEMA_VERSION), EventHeaders.SCHEMA_VERSION));
    }

    private static Instant instant(String value, String header) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(header + " is not a timestamp: " + value);
        }
    }

    private static long whole(String value, String header) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(header + " is not a whole number: " + value);
        }
    }

    /** A header as text: a String as the binder hands it over, or raw UTF-8 bytes as Kafka carries it. */
    static String header(Message<?> message, String name) {
        Object value = message.getHeaders().get(name);
        if (value == null) {
            throw new IllegalArgumentException("header " + name + " is missing");
        }
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
    }
}
