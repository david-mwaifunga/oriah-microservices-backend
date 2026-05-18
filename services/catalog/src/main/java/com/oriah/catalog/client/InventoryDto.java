package com.oriah.catalog.client;

import java.util.UUID;

/**
 * Catalog's view of the inventory service's response.
 *
 * <p>Intentionally a separate record from inventory's own
 * {@code InventoryResponse}: this is a <b>contract</b> between two
 * services, owned by the consumer (catalog). If inventory changes its
 * wire format, catalog can keep this record on the old shape and adapt
 * in a mapper, decoupling deploy timing.
 */
public record InventoryDto(
        UUID variantId,
        int onHand,
        int reserved,
        int available
) {}
