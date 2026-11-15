package com.oriah.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderItem;
import com.oriah.order.service.RecordedPricing;

/**
 * The other answers of the recorded-order door (Phase 9): a row of the list, a page of rows, the receipt a caller is given, and a preview. Each is an
 * allow-list built field by field (a sales person reads no landed cost or margin and no staff identity: Q29), and none shows a caller's phone in full except the
 * receipt, which never shows it at all.
 */
public final class RecordedViews {

    private RecordedViews() {
    }

    /** One order in the list: enough to recognise it and the caller, with the phone masked. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(UUID id, String status, String kind, Instant occurredAt, Instant placedAt, String callerName, String callerPhone, BigDecimal total, String currency,
                      String receiptNumber) {
    }

    /** A page of rows, newest first. */
    public record Page(List<Row> content, int page, int size, long totalElements) {
    }

    /**
     * The receipt staff pass on to the caller: OUR number (never a tax invoice, never a VAT line), the day the money arrived, the caller's NAME only (no phone, no
     * street: it may be forwarded), every line, and a sum that adds up to the total.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(String receiptNumber, Instant paidAt, String callerName, String currency, List<ReceiptLine> lines, BigDecimal subtotal, BigDecimal discountAmount,
                          ReceiptDelivery delivery, BigDecimal total, String notice) {

        public static final String NOTICE = "This is Oriah's receipt for your purchase. It is not a tax invoice.";

        public static Receipt of(Order order) {
            List<ReceiptLine> lines = order.items().stream().map(ReceiptLine::of).toList();
            ReceiptDelivery delivery = order.isSelfCollect() ? null : ReceiptDelivery.of(order);
            return new Receipt(order.receiptNumber(), order.paidAt(), order.callerName(), order.currency(), lines, order.subtotal(), order.discountAmount(), delivery,
                    order.total(), NOTICE);
        }
    }

    public record ReceiptLine(String title, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece, BigDecimal lineTotal) {

        static ReceiptLine of(OrderItem item) {
            BigDecimal discount = item.discountPerPiece() == null ? BigDecimal.ZERO.setScale(2) : item.discountPerPiece();
            return new ReceiptLine(item.title(), item.quantity(), item.unitPrice(), discount,
                    item.unitPrice().subtract(discount).multiply(BigDecimal.valueOf(item.quantity())).setScale(2));
        }
    }

    /** What delivery cost on the order, and who pays the courier; a recipient-paid courier is K0.00 here and the receipt says so. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReceiptDelivery(String optionName, String townName, String payer, BigDecimal amount, String note) {

        static ReceiptDelivery of(Order order) {
            boolean recipientPays = "RECIPIENT_PAYS".equals(order.deliveryPayer());
            return new ReceiptDelivery(order.deliveryOptionName(), order.deliveryTownName(), order.deliveryPayer(), order.shippingAmount(),
                    recipientPays ? "You pay the courier directly: K0.00 on this receipt." : null);
        }
    }

    /** The sale as it would be recorded, nothing written and nothing held: the total to read to the caller. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Preview(String kind, Instant occurredAt, String currency, List<RecordedOrderView.Line> lines, BigDecimal subtotal, BigDecimal discountAmount,
                          RecordedOrderView.Delivery delivery, BigDecimal total) {

        public static Preview of(RecordedPricing.Priced priced, Instant occurredAt, String kind) {
            List<RecordedOrderView.Line> lines = priced.lines().stream().map(l -> {
                BigDecimal each = l.unitPrice().subtract(l.discountPerPiece());
                return new RecordedOrderView.Line(null, l.variantId(), l.title(), l.quantity(), l.unitPrice(), l.discountPerPiece(),
                        each.multiply(BigDecimal.valueOf(l.quantity())).setScale(2), null);
            }).toList();
            var d = priced.delivery();
            RecordedOrderView.Delivery delivery = priced.isPickup() ? null
                    : new RecordedOrderView.Delivery(d.source(), d.optionCode(), d.optionName(), d.townId(), d.townName(), d.payer(), d.amount());
            return new Preview(kind, occurredAt, priced.currency(), lines, priced.subtotal(), priced.discount(), delivery, priced.total());
        }
    }
}
