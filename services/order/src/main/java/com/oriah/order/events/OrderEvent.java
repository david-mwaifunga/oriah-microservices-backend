package com.oriah.order.events;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.oriah.money.Ngwee;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderItem;
import com.oriah.order.model.OrderStatus;

/**
 * The order event contract, schema version 1: what order publishes on
 * {@code oriah.order} for every change of an order's status.
 *
 * <h2>Each event is the whole order</h2>
 *
 * A snapshot, not a change: a consumer never needs an earlier event to make
 * sense of this one, and a projection that receives an older snapshot after a
 * newer one simply keeps the newer (by the envelope's {@code seq}). The event
 * type ({@link #eventType}) says which status the order moved to;
 * {@code newStatus} says the same in the body.
 *
 * <h2>Fields, in this order on the wire</h2>
 *
 * The first six are the original contract, unchanged, so notification keeps
 * reading exactly what it read: {@code orderId}, {@code keycloakId} (the
 * customer), {@code previousStatus} (null on {@code order.created}),
 * {@code newStatus}, {@code total}, {@code currency}. Then what a receipt or a
 * sales ledger needs: {@code subtotal}, {@code shippingAmount},
 * {@code discountAmount} (total = subtotal + shipping - discount),
 * {@code deliveryOption}, {@code paymentIntentId} (null until the order is
 * paid) and every {@code line}.
 *
 * <p>Every amount is in {@code currency}, written as the database stores it:
 * plain, to the ngwee ({@code 150.00}). An order placed before V4 has no
 * recorded shipping, discount or delivery option -- unknown, never zero -- so
 * those are written as {@code null}, as is every other absent value.
 *
 * <p>{@code OrderEventContractTest} pins the bytes;
 * {@code src/test/resources/contracts/order-paid-v1.json} is the example
 * consumers are built against.
 */
@JsonPropertyOrder({"orderId", "keycloakId", "previousStatus", "newStatus", "total", "currency",
        "subtotal", "shippingAmount", "discountAmount", "deliveryOption", "paymentIntentId", "lines", "reservationId", "dispatch"})
public record OrderEvent(
        UUID orderId,
        String keycloakId,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        BigDecimal total,
        String currency,
        BigDecimal subtotal,
        BigDecimal shippingAmount,
        BigDecimal discountAmount,
        String deliveryOption,
        UUID paymentIntentId,
        List<Line> lines,
        /**
         * Only on {@code order.cancelled} and {@code order.expired}, and only for an order that took a hold: the reservation the pieces were held
         * under, so inventory lets go of THAT hold and a stale event can never touch a newer one. Absent (not null) everywhere else, so the bytes
         * of every other event are what they always were.
         */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        UUID reservationId,
        /**
         * Only on {@code order.shipped} (Phase 8): the dispatch as it happened. The ONE home of the courier fare Oriah paid -- finance reads it here and
         * nowhere else. Absent everywhere else, so the bytes of every other event are what they always were.
         */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Dispatch dispatch
) {
    public static final String TOPIC = "oriah.order";
    public static final String AGGREGATE_TYPE = "order";
    public static final int SCHEMA_VERSION = 1;

    /**
     * The dispatch, on {@code order.shipped}: when the parcel went (the keyed date, which dates the month the fare belongs to), the fare Oriah paid the
     * courier and who paid the courier. {@code farePaid} present means a fare was paid (0.00: the courier charged nothing); absent means none was due
     * (a courier the recipient paid, or Oriah's own delivery). No recipient, address, reference or reason: nothing personal and nothing free-text.
     */
    @JsonPropertyOrder({"dispatchedAt", "farePaid", "payer"})
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Dispatch(java.time.Instant dispatchedAt, BigDecimal farePaid, String payer) {
    }

    /**
     * One line as it was sold: the variant, catalog's title at the moment of
     * sale, how many, and the price of one, in the event's currency.
     */
    @JsonPropertyOrder({"variantId", "title", "quantity", "unitPrice"})
    public record Line(UUID variantId, String title, int quantity, BigDecimal unitPrice) {
    }

    /**
     * Lines by variant id (then the rest, for a variant sold on two lines).
     * The database keeps no line order, so without this two snapshots of the
     * same unchanged order could list its lines differently.
     */
    private static final Comparator<Line> LINE_ORDER = Comparator
            .comparing((Line line) -> line.variantId().toString())
            .thenComparing(Line::title)
            .thenComparingInt(Line::quantity)
            .thenComparing(Line::unitPrice);

    /**
     * @param order          the order as it is now, in the transaction that changed it
     * @param previousStatus what it was before this change; null when it was just placed
     */
    public static OrderEvent of(Order order, OrderStatus previousStatus) {
        return new OrderEvent(
                order.id(),
                order.keycloakId(),
                previousStatus,
                order.status(),
                stored(order.total()),
                order.currency(),
                stored(order.subtotal()),
                stored(order.shippingAmount()),
                stored(order.discountAmount()),
                order.deliveryOption(),
                order.paymentIntentId(),
                order.items().stream().map(OrderEvent::line).sorted(LINE_ORDER).toList(),
                order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.EXPIRED ? order.reservationId() : null,
                null);
    }

    /** The snapshot of an order that has just been shipped, carrying the dispatch. */
    public static OrderEvent shipped(Order order, OrderStatus previousStatus, Dispatch dispatch) {
        OrderEvent base = of(order, previousStatus);
        return new OrderEvent(base.orderId, base.keycloakId, base.previousStatus, base.newStatus, base.total, base.currency, base.subtotal, base.shippingAmount,
                base.discountAmount, base.deliveryOption, base.paymentIntentId, base.lines, base.reservationId, new Dispatch(
                        dispatch.dispatchedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS), stored(dispatch.farePaid()), dispatch.payer()));
    }

    /** {@code order.<status in lower case>}: order.created, order.paid, ... order.cancelled. */
    public static String eventType(OrderStatus status) {
        // Locale.ROOT: in a Turkish locale "PAID".toLowerCase() is "paıd".
        return "order." + status.name().toLowerCase(Locale.ROOT);
    }

    private static Line line(OrderItem item) {
        return new Line(item.variantId(), item.title(), item.quantity(), stored(item.unitPrice()));
    }

    /**
     * The amount as its NUMERIC(12,2) column holds it. An order still in the
     * transaction that placed it carries amounts as they were computed
     * (1250 x 2 = 2500, no decimals); the same order read back says 2500.00.
     * The snapshot must not depend on which, so it writes what is stored.
     * Exact: every amount here passed the ngwee rule, so no rounding happens.
     */
    private static BigDecimal stored(BigDecimal amount) {
        return amount == null ? null : amount.setScale(Ngwee.DECIMAL_PLACES, RoundingMode.UNNECESSARY);
    }
}
