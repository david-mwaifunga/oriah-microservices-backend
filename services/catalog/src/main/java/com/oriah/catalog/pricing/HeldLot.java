package com.oriah.catalog.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One lot that still holds stock, as inventory reports it. */
public record HeldLot(UUID lotId, BigDecimal unitCost, int qtyRemaining, boolean estimated, int revision, Instant receivedAt,
                      String consignmentReference) {
}
