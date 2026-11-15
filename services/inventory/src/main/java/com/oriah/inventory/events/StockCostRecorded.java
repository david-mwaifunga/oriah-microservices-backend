package com.oriah.inventory.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code stock.cost_recorded} (Phase 10: finance's feed): what ONE row of the stock ledger moved, at what cost. Aggregate {@code stock_action} (the action the row
 * belongs to), topic {@code oriah.inventory}, occurred on the day of the action (a re-cut's rows keep the day of the sale or write-off they belong to, never the
 * day of the correction), recorded when the row was written.
 *
 * <p>Finance reads a cost from these rows and from nothing else: a sale, a write-off, a give-away, a take-back and a cost correction all reach profit as rows
 * like this one. The signed sum of {@code -qtyDelta x unitCost} over every announced row equals what the ledger holds in force, because a re-cut reverses the old
 * row and writes its replacement, both announced. A receipt, and the lot transfers of an amendment, move no cost and are never announced.
 *
 * <p>{@code productId} is the product the cost belongs to, worked out here once and frozen: a sale's box belongs to the product it was sold with; a take-back and a
 * swapped box to the product of the sale they undo; anything else to the product of the variant. It is null only when inventory cannot say (finance books that to
 * "not tied to a product yet" and flags it). Nothing personal travels with it: no note, no name.
 *
 * @param movementId         the ledger row (finance's idempotency key)
 * @param actionKind         SALE, WRITE_OFF, GIVE_AWAY, TAKE_BACK, CORRECTION or RECEIPT
 * @param reason             the economic reason (one of the ledger's closed list)
 * @param role               ORIGINAL, RECUT_REVERSAL or RECUT_REPLACEMENT
 * @param kind               SELLABLE or PACKAGING
 * @param qtyDelta           negative: units left stock (a cost released); positive: units came back
 * @param estimated          whether the lot's cost was an estimate (day-one stock)
 * @param reversesActionId   only for a take-back: the write-off, give-away or sale it undoes
 * @param reversesReason     only for a take-back: that action's reason, so the take-back nets inside it
 * @param recordedAt         when the row was written
 */
@JsonPropertyOrder({"movementId", "actionId", "actionKind", "reason", "role", "variantId", "lotId", "consignmentLineId", "kind", "productId", "qtyDelta", "unitCost",
        "currency", "estimated", "reversesMovementId", "replacesMovementId", "reversesActionId", "reversesReason", "recordedAt"})
public record StockCostRecorded(
        long movementId,
        UUID actionId,
        String actionKind,
        String reason,
        String role,
        UUID variantId,
        UUID lotId,
        UUID consignmentLineId,
        String kind,
        UUID productId,
        int qtyDelta,
        BigDecimal unitCost,
        String currency,
        boolean estimated,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long reversesMovementId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long replacesMovementId,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID reversesActionId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reversesReason,
        Instant recordedAt) {

    public static final String TYPE = "stock.cost_recorded";
    public static final String AGGREGATE = "stock_action";
}
