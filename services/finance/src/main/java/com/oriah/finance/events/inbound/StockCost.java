package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code stock.cost_recorded}, as finance reads it: one row of the stock ledger, what it moved at what cost, and the product it belongs to. No note and no person travel with
 * it, and none is read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StockCost(Long movementId, UUID actionId, String actionKind, String reason, String role, UUID variantId, UUID lotId, UUID consignmentLineId, String kind,
                        UUID productId, int qtyDelta, BigDecimal unitCost, String currency, boolean estimated, Long reversesMovementId, Long replacesMovementId,
                        UUID reversesActionId, String reversesReason, Instant recordedAt) {
}
