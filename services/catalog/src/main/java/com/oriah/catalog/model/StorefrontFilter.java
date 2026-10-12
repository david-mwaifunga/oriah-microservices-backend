package com.oriah.catalog.model;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The filters a storefront list request can carry.
 *
 * <p>A record rather than six parameters, because the same set is needed by
 * the id query, the count query and the service, and keeping them together
 * stops the three from drifting out of step.
 *
 * <h2>categoryIds is three-valued, deliberately</h2>
 *
 * {@code null} means "no category filter". An EMPTY list means "filter by a
 * category set that is empty", which matches nothing. Those must not collapse
 * into each other: resolving an unknown category slug yields no descendants,
 * and treating that as "no filter" would turn a typo in a URL into "here is
 * the entire catalogue" -- a failure that looks exactly like success.
 */
public record StorefrontFilter(
        String typeCode,
        Collection<UUID> categoryIds,
        String tag,
        BigDecimal minPrice,
        BigDecimal maxPrice) {

    public static StorefrontFilter unfiltered() {
        return new StorefrontFilter(null, null, null, null, null);
    }

    public StorefrontFilter withType(String code) {
        return new StorefrontFilter(code, categoryIds, tag, minPrice, maxPrice);
    }

    public StorefrontFilter withCategories(Collection<UUID> ids) {
        return new StorefrontFilter(typeCode, ids == null ? null : List.copyOf(ids), tag, minPrice, maxPrice);
    }

    public StorefrontFilter withTag(String newTag) {
        return new StorefrontFilter(typeCode, categoryIds, newTag, minPrice, maxPrice);
    }

    public StorefrontFilter withPriceRange(BigDecimal min, BigDecimal max) {
        return new StorefrontFilter(typeCode, categoryIds, tag, min, max);
    }

    /** True when a category filter was supplied at all, empty or not. */
    public boolean hasCategoryFilter() {
        return categoryIds != null;
    }
}
