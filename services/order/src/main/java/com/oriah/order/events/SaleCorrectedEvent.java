package com.oriah.order.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code order.sale_cancelled} and {@code order.return_recorded}, schema version 1 (Phase 12, plan 7.1): a paid sale corrected. Aggregate {@code order},
 * topic {@code oriah.order}; the event's {@code occurredAt} is the correction's own instant (when it was cancelled, or when the parcel came back) and its
 * actor is the person who recorded it.
 *
 * <p>The sale is never edited: this event points back at it ({@code orderId}, {@code confirmationId}, {@code receiptNumber}, each {@code lineId}) and
 * says what is undone and what the customer is owed for it. Finance takes the revenue back from it, payment records the debt, notification tells the
 * customer. {@code owedGross = goodsAtList - discounts + deliveryRefunded}; {@code waiverTakenBack} is never money (a waived delivery was never paid),
 * only what leaves the "delivery given up" memo. {@code feeBearer} says who bears the charge for sending the refund, decided when it was recorded.
 *
 * <p>Identifiers, codes, amounts and dates only: never a note, a back-dating reason, a caller, a recipient, an address or a payment reference. The
 * record order is the wire order; {@code contracts/order-sale-cancelled-v1.json} and {@code order-return-recorded-v1.json} pin the bytes.
 *
 * @param pieces     a return only: one entry per piece that came back (the id inventory and order know the piece by); absent on a cancellation
 * @param keycloakId the shopper; null for an order staff recorded for a caller with no account
 * @param channel    only on a phone order: {@code PHONE}
 * @param saleKind   only on a phone order: {@code LIVE_PHONE} or {@code LATE_PAPER}
 */
@JsonPropertyOrder({"correctionId", "orderId", "kind", "reasonCode", "statusBefore", "statusAfter", "confirmationId", "receiptNumber", "saleArrivedAt",
        "occurredAt", "recordedAt", "currency", "lines", "pieces", "goodsAtList", "discounts", "deliveryRefunded", "waiverTakenBack", "owedGross", "feeBearer",
        "deliveryOption", "deliveryPayer", "keycloakId", "channel", "saleKind"})
public record SaleCorrectedEvent(
        UUID correctionId,
        UUID orderId,
        String kind,
        String reasonCode,
        String statusBefore,
        String statusAfter,
        UUID confirmationId,
        String receiptNumber,
        Instant saleArrivedAt,
        Instant occurredAt,
        Instant recordedAt,
        String currency,
        List<Line> lines,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Piece> pieces,
        BigDecimal goodsAtList,
        BigDecimal discounts,
        BigDecimal deliveryRefunded,
        BigDecimal waiverTakenBack,
        BigDecimal owedGross,
        String feeBearer,
        String deliveryOption,
        String deliveryPayer,
        String keycloakId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String channel,
        @JsonInclude(JsonInclude.Include.NON_NULL) String saleKind) {

    public static final String CANCELLED = "order.sale_cancelled";
    public static final String RETURNED = "order.return_recorded";
    public static final int SCHEMA_VERSION = 1;

    /**
     * The part of one sale line that is undone: its pieces, at the sale's own list price and per-piece discount.
     *
     * @param lineId     the id the line was sold under
     * @param lineRefund (unitPrice - discountPerPiece) x quantity
     */
    @JsonPropertyOrder({"lineId", "variantId", "productId", "quantity", "unitPrice", "discountPerPiece", "promotionId", "lineRefund"})
    public record Line(UUID lineId, UUID variantId, UUID productId, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece, UUID promotionId,
                       BigDecimal lineRefund) {
    }

    @JsonPropertyOrder({"pieceId", "lineId"})
    public record Piece(UUID pieceId, UUID lineId) {
    }
}
