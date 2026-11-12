package com.oriah.catalog.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the catalogue says each asked-for variant IS: name, SKU, kind, and whether
 * it is still usable. Never a price; this is not the pricing route.
 *
 * <p>One entry per requested id, in request order, duplicates included, so the
 * caller can zip it against its own lines. An id that is no variant at all comes
 * back {@code found = false} with every other field absent, rather than being
 * left out: a missing entry would make the caller compare list lengths to work out
 * what happened.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VariantLookupResponse(List<Entry> results) {

    /**
     * @param kind           SELLABLE or PACKAGING
     * @param productStatus  DRAFT, ACTIVE or ARCHIVED (a deleted product is archived)
     * @param active         whether the variant itself is switched on
     * @param productDeleted whether the owning product has been deleted
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(
            UUID variantId,
            boolean found,
            UUID productId,
            String productTitle,
            String variantTitle,
            String sku,
            String kind,
            String productStatus,
            Boolean active,
            Boolean productDeleted
    ) {

        public static Entry notFound(UUID variantId) {
            return new Entry(variantId, false, null, null, null, null, null, null, null, null);
        }
    }
}
