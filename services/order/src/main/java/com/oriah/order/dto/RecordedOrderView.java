package com.oriah.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderItem;
import com.oriah.order.model.OrderStatus;

/**
 * A recorded order as a member of sales staff reads it (Phase 9): what the caller was told and what they owe, built field by field from an allow-list.
 * It is NEVER {@link OrderResponse#forStaff}: a sales person reads no landed cost or margin, no staff identity and nothing of another service's data (Q29).
 * There is no cost, fare, waiver, recorder, login, reservation or price-history id here, and a test reads the JSON for each.
 *
 * <p>The caller's phone, who the parcel is for and where it goes stay readable while the order is alive (a hold that lapsed still needs its caller rung) and
 * are withheld once it is finished: the phone is masked and the recipient and address are not shown after DELIVERED or CANCELLED. A cancelled order has
 * forgotten the recipient and address already.
 *
 * @param kind       {@code LIVE_PHONE} or {@code LATE_PAPER}
 * @param occurredAt when the sale happened; {@code placedAt} is when it was keyed
 * @param holdExpiresAt until when the pieces are held for the caller to pay (ten minutes; money confirmed later still pays it if the pieces are free)
 * @param dispatch   when it was packed, sent and delivered, once it is
 * @param replay     true when this is an order the same request made before (200), false when this call made it (201); absent when read
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordedOrderView(UUID id, String status, String channel, String kind, Instant occurredAt, Instant placedAt, Instant holdExpiresAt, String receiptNumber,
                                Instant paidAt, Caller caller, Recipient recipient, Address address, String currency, List<Line> lines, BigDecimal subtotal,
                                BigDecimal discountAmount, Delivery delivery, BigDecimal total, Dispatch dispatch, Boolean replay) {

    /** Who is buying. */
    public record Caller(String name, String phone) {
    }

    public record Recipient(String name, String phone) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Address(String line1, String line2, String city, String state, String postalCode, String country) {
    }

    /** One line as it was sold: the list price, what a promotion took off each piece and the line's total. */
    public record Line(UUID lineId, UUID variantId, String title, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece, BigDecimal lineTotal, UUID boxVariantId) {
    }

    /** How it goes and what it costs; {@code source} is PUBLISHED (the shop's town price) or AGREED (by phone). Absent for a pickup. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delivery(String source, String optionCode, String optionName, UUID townId, String townName, String payer, BigDecimal amount) {
    }

    /** The dates only: never the reference of a courier's waybill, nor a fare, nor who did it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Dispatch(Instant packedAt, Instant dispatchedAt, Instant deliveredAt) {
    }

    /** The order as a recorder reads it just after recording (or a repeat), nothing finished yet. */
    public static RecordedOrderView of(Order order, boolean replay) {
        return build(order, null, replay);
    }

    /** The order read later, with its dispatch dates, the person withheld once it is finished. */
    public static RecordedOrderView read(Order order, OrderResponse.Dispatch dispatch) {
        return build(order, dispatch, null);
    }

    private static boolean finished(Order order) {
        return order.status() == OrderStatus.DELIVERED || order.status() == OrderStatus.CANCELLED;
    }

    private static RecordedOrderView build(Order order, OrderResponse.Dispatch dispatch, Boolean replay) {
        boolean finished = finished(order);
        List<Line> lines = order.items().stream().map(RecordedOrderView::line).toList();
        Delivery delivery = order.isSelfCollect() ? null : new Delivery(order.deliveryPriceSource(), order.deliveryOption(), order.deliveryOptionName(), order.deliveryTownId(),
                order.deliveryTownName(), order.deliveryPayer(), order.shippingAmount());
        Recipient recipient = finished || order.recipientName() == null ? null : new Recipient(order.recipientName(), order.recipientPhone());
        Address address = finished || order.addressLine1() == null ? null : new Address(order.addressLine1(), order.addressLine2(), order.addressCity(), order.addressState(),
                order.addressPostalCode(), order.addressCountry());
        Dispatch dates = dispatch == null || (dispatch.packedAt() == null && dispatch.dispatchedAt() == null && dispatch.deliveredAt() == null) ? null
                : new Dispatch(dispatch.packedAt(), dispatch.dispatchedAt(), dispatch.deliveredAt());
        return new RecordedOrderView(order.id(), order.status().name(), order.channel().name(), order.saleKind() == null ? null : order.saleKind().name(), order.occurredAt(),
                order.placedAt(), order.holdExpiresAt(), order.receiptNumber(), order.paidAt(),
                new Caller(order.callerName(), finished ? mask(order.callerPhone()) : order.callerPhone()), recipient, address, order.currency(), lines, order.subtotal(),
                order.discountAmount(), delivery, order.total(), dates, replay);
    }

    /** {@code +260971234567} as {@code +2609*****567}: enough to recognise, not enough to ring. Null stays null. */
    public static String mask(String phone) {
        if (phone == null) {
            return null;
        }
        int n = phone.length();
        if (n >= 10) {
            return phone.substring(0, 5) + "*".repeat(n - 8) + phone.substring(n - 3);
        }
        return "*".repeat(Math.max(0, n - 2)) + phone.substring(Math.max(0, n - 2));
    }

    private static Line line(OrderItem item) {
        BigDecimal discount = item.discountPerPiece() == null ? BigDecimal.ZERO.setScale(2) : item.discountPerPiece();
        BigDecimal each = item.unitPrice().subtract(discount);
        return new Line(item.id(), item.variantId(), item.title(), item.quantity(), item.unitPrice(), discount, each.multiply(BigDecimal.valueOf(item.quantity())).setScale(2),
                item.boxVariantId());
    }
}
