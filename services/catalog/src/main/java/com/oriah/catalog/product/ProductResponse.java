package com.oriah.catalog.product;

import java.util.List;
import java.util.UUID;

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
