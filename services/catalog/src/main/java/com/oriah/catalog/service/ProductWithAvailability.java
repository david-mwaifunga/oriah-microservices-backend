package com.oriah.catalog.service;

import java.util.Map;
import java.util.UUID;

import com.oriah.catalog.model.Availability;
import com.oriah.catalog.model.Product;

/**
 * Service-layer wrapper combining a {@link Product} with the
 * {@link Availability} sourced from the inventory service: the product's as a
 * whole, and each purchasable variant's own.
 *
 * <p>Lives in the {@code service} package because it's an
 * orchestration result, not a domain entity (Product is the entity,
 * Availability is an enum). The DTO layer maps this to the wire-format
 * {@code ProductResponse}.
 *
 * @param variants the badge of each variant that can be bought, by variant id; a variant that cannot be bought (withdrawn
 *                 or not yet priced) has none
 */
public record ProductWithAvailability(Product product, Availability availability, Map<UUID, Availability> variants) {

    public ProductWithAvailability {
        variants = variants == null ? Map.of() : Map.copyOf(variants);
    }

    /** A product with only its own badge, and none per variant. */
    public ProductWithAvailability(Product product, Availability availability) {
        this(product, availability, Map.of());
    }
}
