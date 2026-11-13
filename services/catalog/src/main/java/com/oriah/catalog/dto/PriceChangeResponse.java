package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.catalog.model.ProductStatus;
import com.oriah.catalog.pricing.PriceAssessment;

/**
 * A saved price: the new history row, what the rule found, the cost it was compared with, and whether the product can now be published.
 *
 * <p>The rule judges the NET price (what a customer pays per piece once the promotion in force now has taken its discount off), so
 * {@code assessment} is about {@code netPrice}; {@code price.unitPrice} is the list price that was saved. With no promotion
 * {@code discountPerPiece} is 0.00, {@code promotionId} is absent and the net price is the list price.
 */
public record PriceChangeResponse(
        PriceEntryResponse price,
        PriceAssessment assessment,
        CostView cost,
        ProductStatus productStatus,
        boolean publishable,
        BigDecimal discountPerPiece,
        UUID promotionId,
        BigDecimal netPrice) {
}
