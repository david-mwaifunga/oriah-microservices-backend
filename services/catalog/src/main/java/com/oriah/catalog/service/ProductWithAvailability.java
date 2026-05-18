package com.oriah.catalog.service;

import com.oriah.catalog.model.Availability;
import com.oriah.catalog.model.Product;

/**
 * Service-layer wrapper combining a {@link Product} with the
 * {@link Availability} sourced from the inventory service.
 *
 * <p>Lives in the {@code service} package because it's an
 * orchestration result, not a domain entity (Product is the entity,
 * Availability is an enum). The DTO layer maps this to the wire-format
 * {@code ProductResponse}.
 */
public record ProductWithAvailability(Product product, Availability availability) {
}
