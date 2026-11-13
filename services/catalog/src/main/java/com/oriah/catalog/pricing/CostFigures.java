package com.oriah.catalog.pricing;

import java.math.BigDecimal;

/**
 * What a price is compared with, apart from any price: the dearest held cost of the goods, the dearest held cost of the default box
 * and their sum (the floor). {@code floor} is null when nothing of the goods is held, so there is no cost to speak of.
 *
 * @param estimated true if a lot that SETS the floor (the dearest of the goods, or of the box when it is counted) is an estimate
 */
public record CostFigures(BigDecimal floor, BigDecimal goodsHighest, BigDecimal boxHighest, BoxBasis boxBasis, boolean estimated) {
}
