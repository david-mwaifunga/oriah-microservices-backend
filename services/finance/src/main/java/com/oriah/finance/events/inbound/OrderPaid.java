package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code order.paid} (version 2), as finance reads it: an ALLOW-LIST of what a sale's books need. Everything else the event carries (the owner, the payment intent,
 * the box a piece went with, the previous status) is left unread on purpose, so nothing personal or irrelevant can reach a table, a log line or a refusal message.
 * Unknown fields are ignored, so order may add trailing ones without breaking finance.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPaid(
        UUID orderId,
        String currency,
        BigDecimal total,
        BigDecimal subtotal,
        BigDecimal shippingAmount,
        BigDecimal discountAmount,
        String deliveryOption,
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
        boolean republished,
        String channel,
        Instant occurredAt,
        BigDecimal deliveryWaivedAmount,
        String deliveryPriceSource,
        BigDecimal deliveryAgreedAmount,
        BigDecimal deliveryQuotedAmount) {

    /** One line as it was sold: the price of one at list, what a promotion took off each piece, and the product it belongs to. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(UUID variantId, String title, int quantity, BigDecimal unitPrice, UUID lineId, UUID productId, BigDecimal discountPerPiece, UUID promotionId) {
    }
}
