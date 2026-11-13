package com.oriah.catalog.dto;

import com.oriah.catalog.model.ProductStatus;
import com.oriah.catalog.pricing.PriceAssessment;

/** A saved price: the new history row, what the rule found, the cost it was compared with, and whether the product can now be published. */
public record PriceChangeResponse(
        PriceEntryResponse price,
        PriceAssessment assessment,
        CostView cost,
        ProductStatus productStatus,
        boolean publishable) {
}
