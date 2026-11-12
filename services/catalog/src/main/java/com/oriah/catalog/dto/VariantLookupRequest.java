package com.oriah.catalog.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * The variants procurement wants named.
 *
 * @param variantIds capped at 100, like the pricing route and for the same reason:
 *                   one request must not be able to scan the variant table. A
 *                   purchase order carries at most 100 lines.
 */
public record VariantLookupRequest(
        @NotEmpty(message = "variantIds must not be empty")
        @Size(max = 100, message = "at most 100 variantIds per request")
        List<UUID> variantIds
) {
}
