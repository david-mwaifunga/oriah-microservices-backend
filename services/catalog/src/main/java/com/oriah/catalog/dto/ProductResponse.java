package com.oriah.catalog.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.oriah.catalog.model.Availability;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.service.ProductWithAvailability;

/**
 * Wire-format response for a single product. Carries only what we want to
 * expose publicly — fields the entity holds but the API shouldn't surface
 * (audit timestamps, soft-delete flags, internal flags) stay off this record.
 *
 * <p>{@code availability} is populated on the GET-by-id endpoint (where
 * we make the inventory call) and is {@code null} on list endpoints
 * (where calling inventory N times would be too expensive). Jackson
 * omits null fields via {@code @JsonInclude(NON_NULL)} so list responses
 * don't carry an empty key.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductResponse(
        UUID id,
        String title,
        String description,
        List<String> tags,
        Availability availability
) {

    /**
     * Map a plain {@link Product} to a {@code ProductResponse} without
     * availability. Used by list endpoints.
     */
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.id(),
                product.title(),
                product.description().orElse(null),
                product.tags(),
                null
        );
    }

    /**
     * Map a {@link ProductWithAvailability} (product + inventory-derived
     * availability) to a full response. Used by GET-by-id.
     */
    public static ProductResponse from(ProductWithAvailability enriched) {
        Product product = enriched.product();
        return new ProductResponse(
                product.id(),
                product.title(),
                product.description().orElse(null),
                product.tags(),
                enriched.availability()
        );
    }
}
