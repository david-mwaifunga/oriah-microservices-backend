package com.oriah.inventory.dto;

import java.time.Instant;
import java.util.UUID;

import com.oriah.inventory.service.StockService;

/** What a take-back did: how many of the write-off or give-away are still out, and the stock counts. No cost. */
public record TakeBackResponse(
        UUID actionId,
        UUID writeOffActionId,
        UUID variantId,
        int qty,
        Instant occurredAt,
        int outstanding,
        int onHand,
        int reserved,
        int available
) {
    public static TakeBackResponse from(StockService.TakeBackResult r) {
        return new TakeBackResponse(r.actionId(), r.writeOffActionId(), r.variantId(), r.qty(), r.occurredAt(), r.outstanding(), r.onHand(),
                r.reserved(), r.available());
    }
}
