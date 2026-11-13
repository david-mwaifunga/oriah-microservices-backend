package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.oriah.catalog.model.ProductStatus;
import com.oriah.catalog.pricing.PriceAssessment;

/**
 * Everything the pricing screen needs about one variant: the price in force, how it stands against the cost now, what the cost is
 * (every held lot, the dearest, the average), the dated history, and a suggestion when a target percentage is known.
 *
 * @param assessment       how the NET price (the list price less the promotion in force now) stands against the cost as read now; null when
 *                         the variant is unpriced
 * @param shortfall        by how much the net price is below the floor, when BELOW
 * @param discountPerPiece the Kwacha the promotion in force now takes off each piece (0.00 when none); null when the variant is unpriced
 * @param promotionId      that promotion; null when none applies
 * @param netPrice         the list price less the discount; null when the variant is unpriced
 * @param history          newest first
 */
public record VariantPricingView(
        UUID variantId,
        UUID productId,
        String productTitle,
        String variantTitle,
        String sku,
        ProductStatus productStatus,
        UUID defaultPackagingVariantId,
        PriceEntryResponse currentPrice,
        PriceAssessment assessment,
        BigDecimal shortfall,
        BigDecimal discountPerPiece,
        UUID promotionId,
        BigDecimal netPrice,
        CostView cost,
        SuggestionView suggestion,
        List<PriceEntryResponse> history) {
}
