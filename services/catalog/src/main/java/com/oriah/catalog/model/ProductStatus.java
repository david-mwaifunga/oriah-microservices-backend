package com.oriah.catalog.model;

/**
 * Publication state of a product, mirroring the
 * {@code products_status_known} CHECK constraint added by V3.
 *
 * <p>The storefront shows {@link #ACTIVE} only. That is what makes the V3
 * backfill safe: every pre-existing product was migrated to {@link #DRAFT}
 * with an unpriced variant, so nothing unpriced is purchasable, and the
 * service refuses the transition to ACTIVE until a product has at least one
 * active variant priced above zero.
 */
public enum ProductStatus {

    /** Not visible to customers. Where every product starts, and where the V3 backfill left the existing catalogue. */
    DRAFT,

    /** Visible and purchasable. Requires at least one active, priced variant. */
    ACTIVE,

    /**
     * Withdrawn from sale but kept. Distinct from a delete because orders
     * reference variants and an operator needs the history to stay readable;
     * distinct from DRAFT because it was never a work in progress.
     */
    ARCHIVED
}
