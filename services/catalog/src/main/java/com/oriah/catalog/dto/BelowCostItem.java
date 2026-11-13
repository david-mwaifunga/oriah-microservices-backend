package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.catalog.model.ProductStatus;
import com.oriah.catalog.pricing.BoxBasis;
import com.oriah.catalog.pricing.PriceAssessment;

/**
 * One priced variant and how its price stands against the cost as read now.
 *
 * <p>The comparison is made on the NET price, what a customer pays per piece once the promotion in force at the time asked about has taken its
 * discount off ({@code price} less {@code discountPerPiece}); {@code price} is the list price. {@code shortfall} is how far the NET price is
 * under the floor. With no promotion the discount is 0.00, {@code promotionId} is absent and the net price is the list price.
 */
public record BelowCostItem(
        UUID variantId,
        UUID productId,
        String productTitle,
        String variantTitle,
        String sku,
        ProductStatus productStatus,
        BigDecimal price,
        String currency,
        BigDecimal discountPerPiece,
        UUID promotionId,
        BigDecimal netPrice,
        PriceAssessment assessment,
        BigDecimal floor,
        BigDecimal shortfall,
        BigDecimal goodsHighest,
        BigDecimal boxHighest,
        BoxBasis boxBasis,
        boolean estimated) {
}
