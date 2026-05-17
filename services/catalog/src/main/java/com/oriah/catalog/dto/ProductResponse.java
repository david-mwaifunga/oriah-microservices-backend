package com.oriah.catalog.dto;

import java.util.List;
import java.util.UUID;

import com.oriah.catalog.model.Product;

/**
 * Wire-format response for a single product. Carries only what we want to
 * expose publicly — fields the entity holds but the API shouldn't surface
 * (audit timestamps, soft-delete flags, internal flags) stay off this record.
 */
public record ProductResponse(
        UUID id,
        String title,
        String description,
        List<String> tags
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.id(),
                product.title(),
                product.description().orElse(null),
                product.tags()
        );
    }
}
