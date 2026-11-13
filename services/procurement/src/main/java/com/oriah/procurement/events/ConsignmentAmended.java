package com.oriah.procurement.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.oriah.procurement.events.ConsignmentReceived.Slice;

/**
 * {@code consignment.amended}, schema 1: a received consignment's cost was corrected, and this consignment LINE now
 * costs what it says.
 *
 * <p>Topic {@code oriah.procurement}, aggregate ({@code consignment_line}, the line's id): the next event of the line's
 * own history after its {@code consignment.received}. One per line whose unit costs, totals or estimate flag the
 * correction changed; a line it did not change gets none.
 *
 * <p>Everything a receipt says, as it stands after the correction (a full snapshot: a consumer needs nothing else),
 * and then three things that are new: the {@code revision} of the allocation this is (2 and up), the plain-language
 * {@code reason}, and {@code amendedAt}, when it was corrected. {@code occurredAt} is {@code amendedAt}.
 * {@code receivedAt} stays the receipt's: it is the line's place in the oldest-first order and a correction never moves
 * it. {@code isEstimated} is the consignment's flag as of this revision.
 *
 * <p>The field order is the wire order, pinned by a test: change it only with a new schema version.
 */
public record ConsignmentAmended(
        UUID consignmentId,
        String consignmentReference,
        UUID consignmentLineId,
        UUID variantId,
        String sku,
        String productTitle,
        String variantTitle,
        String kind,
        UUID supplierId,
        String supplierName,
        Instant receivedAt,
        boolean isEstimated,
        String currency,
        int quantity,
        BigDecimal goodsTotal,
        BigDecimal sharedCost,
        BigDecimal landedTotal,
        List<Slice> slices,
        int linePosition,
        int revision,
        String reason,
        Instant amendedAt) {
}
