package com.oriah.catalog.client;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Fallback for {@link InventoryClient}. Invoked by the Resilience4j
 * circuit breaker when:
 * <ul>
 *   <li>inventory is unreachable (connection refused, timeout)</li>
 *   <li>inventory returns a 5xx</li>
 *   <li>the circuit is OPEN after enough recent failures</li>
 * </ul>
 *
 * <p>Strategy: return {@code null} so callers (see
 * {@code ProductService.lookupAvailability}) translate that to
 * {@code Availability.UNKNOWN}. Catalog stays up, the PDP just doesn't
 * show a stock badge until inventory recovers. (A 404, "no stock row", is
 * not a failure and never reaches here: see {@link InventoryClient}.)
 */
@Component
@Slf4j
class InventoryClientFallback implements InventoryClient {

    @Override
    public ResponseEntity<InventoryDto> getByVariantId(UUID variantId) {
        log.warn("Inventory call failed (fallback engaged) variantId={}", variantId);
        return null;
    }
}
