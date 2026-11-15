package com.oriah.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderItem;

/**
 * A recorded order as a member of sales staff reads it (Phase 9): what the caller was told and what they owe, built field by field from an allow-list.
 * It is NEVER {@link OrderResponse#forStaff}: a sales person reads no landed cost or margin, no staff identity and nothing of another service's data (Q29).
 * There is no cost, fare, waiver, recorder, login, reservation or price-history id here, and a test reads the JSON for each.
 *
 * <p>The caller's name and phone are shown to the person who recorded the order, who typed them. (Reading an order later is a separate allow-list, with the
 * phone and address withheld once the order is finished.)
 *
 * @param kind       {@code LIVE_PHONE} or {@code LATE_PAPER}
 * @param occurredAt when the sale happened; {@code placedAt} is when it was keyed
 * @param holdExpiresAt until when the pieces are held for the caller to pay (ten minutes; money confirmed later still pays it if the pieces are free)
 * @param replay     true when this is an order the same request made before (200), false when this call made it (201)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordedOrderView(UUID id, String status, String channel, String kind, Instant occurredAt, Instant placedAt, Instant holdExpiresAt, String receiptNumber,
                                Instant paidAt, Caller caller, String currency, List<Line> lines, BigDecimal subtotal, BigDecimal discountAmount, Delivery delivery,
                                BigDecimal total, Boolean replay) {

    /** Who is buying. */
    public record Caller(String name, String phone) {
    }

    /** One line as it was sold: the list price, what a promotion took off each piece and the line's total. */
    public record Line(UUID lineId, UUID variantId, String title, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece, BigDecimal lineTotal, UUID boxVariantId) {
    }

    /** How it goes and what it costs; {@code source} is PUBLISHED (the shop's town price) or AGREED (by phone). Absent for a pickup. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delivery(String source, String optionCode, String optionName, UUID townId, String townName, String payer, BigDecimal amount) {
    }

    public static RecordedOrderView of(Order order, boolean replay) {
        List<Line> lines = order.items().stream().map(RecordedOrderView::line).toList();
        Delivery delivery = order.isSelfCollect() ? null : new Delivery(order.deliveryPriceSource(), order.deliveryOption(), order.deliveryOptionName(), order.deliveryTownId(),
                order.deliveryTownName(), order.deliveryPayer(), order.shippingAmount());
        return new RecordedOrderView(order.id(), order.status().name(), order.channel().name(), order.saleKind() == null ? null : order.saleKind().name(), order.occurredAt(),
                order.placedAt(), order.holdExpiresAt(), order.receiptNumber(), order.paidAt(), new Caller(order.callerName(), order.callerPhone()), order.currency(), lines,
                order.subtotal(), order.discountAmount(), delivery, order.total(), replay);
    }

    private static Line line(OrderItem item) {
        BigDecimal discount = item.discountPerPiece() == null ? BigDecimal.ZERO.setScale(2) : item.discountPerPiece();
        BigDecimal each = item.unitPrice().subtract(discount);
        return new Line(item.id(), item.variantId(), item.title(), item.quantity(), item.unitPrice(), discount, each.multiply(BigDecimal.valueOf(item.quantity())).setScale(2),
                item.boxVariantId());
    }
}
