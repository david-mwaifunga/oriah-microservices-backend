package com.oriah.search.dto;

import java.util.List;
import java.util.UUID;

import com.oriah.search.model.SearchIndexEntry;

/**
 * A page of search hits.
 *
 * @param query the query as received, echoed so a client rendering results
 *              asynchronously can tell which request they belong to
 * @param count the number of hits returned, which is NOT a total — this
 *              endpoint has no pagination
 */
public record SearchResultResponse(String query, int count, List<Hit> hits) {

    /**
     * One matching product.
     *
     * <h2>Why there are two id fields with the same value</h2>
     *
     * This index has always held one row per PRODUCT. Its column is named
     * {@code variant_id} and the field here was named {@code variantId}, which
     * was accurate only while a product had exactly one variant whose id was
     * the product's own — the convention catalog has now outgrown. The VALUE
     * was never wrong; the NAME stopped being defensible.
     *
     * <p>So this is a rename with a deprecation window, not a semantic change.
     * {@code productId} is the field to use. {@code variantId} carries the
     * identical value and will be removed once the frontend no longer reads
     * it.
     *
     * <p>A test asserts the two are always equal for the whole window, which
     * matters because the alternative failure is silent: if this index ever
     * started holding one row per variant, {@code variantId} would begin
     * carrying something genuinely different and any client still reading it
     * would be linking to the wrong page with no error anywhere.
     *
     * @param productId the catalog product id. Link to
     *                  {@code /api/v1/products/{productId}} with this.
     * @param variantId deprecated; identical to {@link #productId}. It is NOT
     *                  a purchasable variant id and never was — resolving a
     *                  variant to add to a cart requires the product detail
     *                  endpoint, which lists them.
     * @param tags      space-joined rather than an array, because the index
     *                  stores them flattened for the tsvector
     */
    public record Hit(
            UUID productId,
            UUID variantId,
            String title,
            String description,
            String tags) {

        public static Hit from(SearchIndexEntry e) {
            // The same value twice, deliberately. See the record javadoc.
            return new Hit(e.variantId(), e.variantId(), e.title(), e.description(), e.tags());
        }
    }
}
