package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code consignment.received} and {@code consignment.amended}, as finance reads them: a consignment line as procurement last announced it (a full snapshot). The supplier's
 * name and the reason a cost was corrected are never read. {@code revision} is present only on an amendment.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsignmentLine(UUID consignmentId, String consignmentReference, UUID consignmentLineId, UUID variantId, String sku, String kind, UUID supplierId, Instant receivedAt,
                              boolean isEstimated, String currency, int quantity, BigDecimal landedTotal, Integer revision) {
}
