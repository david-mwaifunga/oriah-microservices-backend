package com.oriah.procurement.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code consignment.received}, schema 1: one consignment LINE has landed, with what each of its
 * units cost.
 *
 * <p>Topic {@code oriah.procurement}, aggregate ({@code consignment_line}, the line's id), so a
 * line has its own history and a later correction of it is the next event of the same aggregate.
 * A full snapshot, so a consumer needs nothing else: not the consignment, not the catalogue, not
 * the supplier. {@code occurredAt} is {@code receivedAt}, when the goods landed.
 *
 * <p>{@code slices} are the unit costs, DEARER FIRST: at most two, a ngwee apart. Inventory makes
 * one lot per slice, laying them over the units in this order, which is the order the allocator
 * handed out the odd ngwee. Every slice's {@code quantity} adds up to {@code quantity}, and
 * {@code sum(quantity x unitCost)} is exactly {@code landedTotal}.
 *
 * <p>{@code supplierId} and {@code supplierName} are null for the opening stock (supplier
 * unknown), whose events say {@code isEstimated = true}: its costs come from the old book.
 * {@code goodsTotal} is the all-in price paid for the line; {@code sharedCost} the capitalized
 * costs put on it; {@code landedTotal} their sum. Every amount is in {@code currency}.
 *
 * <p>{@code linePosition} is where the line sits in its consignment, counted from 1 in the order the
 * lines were typed. Every line of one receipt shares {@code receivedAt}, so it is what breaks a tie
 * between two lots of the same receipt: oldest-first selling inside a receipt follows the consignment's
 * line order, whatever order Kafka delivers the events in. It was added at the END of the field list
 * (Phase 5); events written before it carry no such field, and a consumer reads that as unknown.
 *
 * <p>The field order is the wire order, pinned by a test: change it only with a new schema
 * version. Consumers read an absent field they do not know as unknown, never as zero.
 */
public record ConsignmentReceived(
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
        int linePosition) {

    /** {@code quantity} units, each costing {@code unitCost}. */
    public record Slice(int quantity, BigDecimal unitCost) {
    }
}
