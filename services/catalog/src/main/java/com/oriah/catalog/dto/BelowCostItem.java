package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.catalog.model.ProductStatus;
import com.oriah.catalog.pricing.BoxBasis;
import com.oriah.catalog.pricing.PriceAssessment;

/** One priced variant and how its price in force stands against the cost as read now. */
public record BelowCostItem(
        UUID variantId,
        UUID productId,
        String productTitle,
        String variantTitle,
        String sku,
        ProductStatus productStatus,
        BigDecimal price,
        String currency,
        PriceAssessment assessment,
        BigDecimal floor,
        BigDecimal shortfall,
        BigDecimal goodsHighest,
        BigDecimal boxHighest,
        BoxBasis boxBasis,
        boolean estimated) {
}
