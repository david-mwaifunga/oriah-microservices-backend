package com.oriah.inventory.dto;

import java.time.Instant;
import java.util.UUID;

import com.oriah.inventory.service.StockService;

/** What a write-off or give-away did to the stock count. Carries no cost: reading cost is COST_VIEWER's capability. */
public record WriteOffResponse(
        UUID actionId,
        UUID variantId,
        String reason,
        int qty,
        Instant occurredAt,
        int onHand,
        int reserved,
        int available
) {
    public static WriteOffResponse from(StockService.WriteOffResult r) {
        return new WriteOffResponse(r.actionId(), r.variantId(), r.reason().name(), r.qty(), r.occurredAt(), r.onHand(), r.reserved(),
                r.available());
    }
}
