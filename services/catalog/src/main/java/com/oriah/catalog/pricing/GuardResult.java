package com.oriah.catalog.pricing;

import java.math.BigDecimal;

/**
 * What the below-cost rule found for one price. {@code floor} is the dearest held cost of the goods plus the dearest held cost
 * of the default box; it is null when nothing is held (or the currency differs), and {@code shortfall} is only set when the
 * price is BELOW.
 */
public record GuardResult(PriceAssessment assessment, BigDecimal price, String priceCurrency, BigDecimal floor, BigDecimal shortfall,
                          BigDecimal goodsHighest, BigDecimal boxHighest, BoxBasis boxBasis, boolean estimated) {
}
