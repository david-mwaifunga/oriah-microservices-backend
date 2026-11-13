package com.oriah.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.order.model.OrderItem;

/**
 * One line of an order. {@code unitPrice} is the LIST price; what a promotion took off each piece is {@code discountPerPiece} (0.00 when none), which
 * {@code promotionId} names, so a sale is never edited to show a discount and what the customer paid per piece is always the difference.
 *
 * @param productId    the product the variant belongs to; absent on a line placed before Phase 7
 * @param boxVariantId the box inventory set aside for each piece; absent until the pieces are held, and on older lines
 */
public record OrderItemResponse(
        UUID id,
        UUID variantId,
        String title,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal discountPerPiece,
        UUID promotionId,
        UUID productId,
        UUID boxVariantId
) {
    public static OrderItemResponse from(OrderItem i) {
        return new OrderItemResponse(i.id(), i.variantId(), i.title(), i.quantity(), i.unitPrice(), i.discountPerPiece(), i.promotionId(), i.productId(),
                i.boxVariantId());
    }
}
