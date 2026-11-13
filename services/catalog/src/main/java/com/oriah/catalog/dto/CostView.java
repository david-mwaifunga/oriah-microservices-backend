package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.oriah.catalog.pricing.BoxBasis;
import com.oriah.catalog.pricing.HeldCost;

/**
 * What a price is compared with, and what it was made of. {@code floor} is the dearest unit cost still held plus the dearest held
 * cost of the default box; null when nothing is held. Each lot's cost and the weighted average are in {@code goods} and {@code box},
 * shown beside the floor and never compared with. COST_VIEWER only, like everything under the pricing prefix.
 */
public record CostView(
        BigDecimal floor,
        BigDecimal goodsHighest,
        BigDecimal boxHighest,
        BoxBasis boxBasis,
        boolean estimated,
        Instant evaluatedAt,
        HeldCost goods,
        HeldCost box) {
}
