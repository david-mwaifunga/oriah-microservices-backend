package com.oriah.order.events;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.oriah.money.Ngwee;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderItem;
import com.oriah.order.model.OrderStatus;

/**
 * {@code order.paid}, schema version 2 (Phase 7): the whole order as it was SOLD, and the money that paid it. Aggregate {@code order}, topic
 * {@code oriah.order}, occurred on the day the money ARRIVED (the true date of the sale), recorded when order applied it.
 *
 * <p>The first twelve fields are version 1's, in version 1's order, so a consumer that reads version 1 (notification) reads this the same way; then what
 * the second version adds: the confirmation that paid it ({@code confirmationId}: payment reads it to know that money SETTLED, and any consumer may use it
 * as the sale's idempotency key), our receipt number, the money's own figures, where and how the parcel goes, and on every line the id it was sold under,
 * its product, what a promotion took off each piece, and the box that went with it.
 *
 * <p>{@code republished} is true when this is the SAME answer announced again because the confirmation it answers was announced again (payment does that
 * while an order has not answered it, so a lost answer is recovered): the sale happened once, and a consumer that acts on a sale (an email, a ledger
 * entry) acts on the first announcement only. The confirmation id says which sale it is.
 */
@JsonPropertyOrder({"orderId", "keycloakId", "previousStatus", "newStatus", "total", "currency", "subtotal", "shippingAmount", "discountAmount",
        "deliveryOption", "paymentIntentId", "lines",
        "confirmationId", "receiptNumber", "arrivedAt", "recordedAt", "paymentMethod", "paymentGross", "paymentFee", "paymentNet",
        "deliveryTownId", "deliveryPayer", "republished"})
public record OrderPaidEvent(
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
        UUID confirmationId,
        String receiptNumber,
        Instant arrivedAt,
        Instant recordedAt,
        String paymentMethod,
        BigDecimal paymentGross,
        BigDecimal paymentFee,
        BigDecimal paymentNet,
        UUID deliveryTownId,
        String deliveryPayer,
        boolean republished
) {
    public static final String TYPE = "order.paid";
    public static final int SCHEMA_VERSION = 2;

    /**
     * One line as it was sold. The first four fields are version 1's.
     *
     * @param lineId           the id the line was sold under (inventory's sale-line id)
     * @param discountPerPiece what a promotion took off each piece (0.00 when none), so a sale is never edited to show a discount
     * @param boxVariantId     the box that went with each piece; absent when inventory never named one
     * @param boxLineId        the id the box was sold under
     */
    @JsonPropertyOrder({"variantId", "title", "quantity", "unitPrice", "lineId", "productId", "discountPerPiece", "promotionId", "boxVariantId", "boxLineId"})
    public record Line(UUID variantId, String title, int quantity, BigDecimal unitPrice, UUID lineId, UUID productId, BigDecimal discountPerPiece,
                       UUID promotionId, UUID boxVariantId, UUID boxLineId) {
    }

    /** The money that paid the sale, as the settlement recorded it. */
    public record Payment(UUID confirmationId, String method, BigDecimal gross, BigDecimal fee, BigDecimal net, Instant arrivedAt, Instant recordedAt) {
    }

    private static final Comparator<Line> LINE_ORDER = Comparator
            .comparing((Line line) -> line.variantId().toString())
            .thenComparing(Line::title)
            .thenComparingInt(Line::quantity)
            .thenComparing(Line::unitPrice)
            .thenComparing(line -> line.lineId().toString());

    /**
     * @param order          the order as it is now, PAID, in the transaction that paid it
     * @param previousStatus what it was before; null when this is the same answer announced again
     * @param republished    whether this is the same answer announced again
     */
    public static OrderPaidEvent of(Order order, OrderStatus previousStatus, Payment payment, boolean republished) {
        return new OrderPaidEvent(order.id(), order.keycloakId(), previousStatus, order.status(), stored(order.total()), order.currency(),
                stored(order.subtotal()), stored(order.shippingAmount()), stored(order.discountAmount()), order.deliveryOption(), order.paymentIntentId(),
                order.items().stream().map(OrderPaidEvent::line).sorted(LINE_ORDER).toList(),
                payment.confirmationId(), order.receiptNumber(), payment.arrivedAt(), payment.recordedAt(), payment.method(), stored(payment.gross()),
                stored(payment.fee()), stored(payment.net()), order.deliveryTownId(), order.deliveryPayer(), republished);
    }

    private static Line line(OrderItem item) {
        return new Line(item.variantId(), item.title(), item.quantity(), stored(item.unitPrice()), item.id(), item.productId(),
                stored(item.discountPerPiece()), item.promotionId(), item.boxVariantId(), item.boxLineId());
    }

    private static BigDecimal stored(BigDecimal amount) {
        return amount == null ? null : amount.setScale(Ngwee.DECIMAL_PLACES, RoundingMode.UNNECESSARY);
    }
}
