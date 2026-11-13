package com.oriah.catalog.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What one variant's held stock cost, as inventory's held-cost read reports it. A variant that holds nothing still has an
 * entry, with no cost.
 */
public record HeldCost(UUID variantId, int onHand, int heldUnits, BigDecimal highestUnitCost, BigDecimal averageUnitCost, String currency,
                       boolean estimated, List<HeldLot> lots) {
}
