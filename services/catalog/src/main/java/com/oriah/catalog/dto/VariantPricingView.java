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
 * @param assessment how the price in force stands against the cost as read now; null when the variant is unpriced
 * @param shortfall  by how much it is below the floor, when BELOW
 * @param history    newest first
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
        CostView cost,
        SuggestionView suggestion,
        List<PriceEntryResponse> history) {
}
