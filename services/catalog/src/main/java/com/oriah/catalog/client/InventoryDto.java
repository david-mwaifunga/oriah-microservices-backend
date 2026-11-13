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
 *
 * <p>The figures are {@code Integer}, not {@code int}, on purpose. When inventory has no stock row for a variant it
 * answers 404 with a problem document, and the Feign client still decodes that body into this record (it is how a 404
 * is read as an answer rather than a failure, see {@link InventoryClient}); a primitive would refuse the missing
 * numbers and turn a normal answer into a decode failure. A 404's figures are all null, and catalog never reads them.
 */
public record InventoryDto(
        UUID variantId,
        Integer onHand,
        Integer reserved,
        Integer available
) {}
