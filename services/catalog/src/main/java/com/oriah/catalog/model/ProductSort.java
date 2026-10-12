package com.oriah.catalog.model;

/**
 * The orderings the storefront list offers.
 *
 * <p>A closed enum rather than a free-form sort string, and that is a security
 * property, not a style preference: the list query needs its ORDER BY built
 * dynamically (you cannot express "sort by the cheapest purchasable variant's
 * price" in a static JPQL string), and a client-supplied sort expression
 * interpolated into SQL is an injection. Each constant maps to one fixed,
 * reviewed SQL fragment, so the only values that can ever reach the database
 * are the four below.
 *
 * <p>This also mirrors how admin-facing lists in this codebase already behave:
 * the admin payments endpoint REJECTS an unexpected `sort` parameter rather
 * than ignoring it, because it has exactly one ordering and a partial index
 * built for it.
 */
public enum ProductSort {

    /** Default. Newest first, by created_at, with id as a tiebreak so paging is stable. */
    NEWEST,

    /** Cheapest purchasable variant, ascending. */
    PRICE_ASC,

    /** Cheapest purchasable variant, descending. */
    PRICE_DESC,

    /** Alphabetical by title. */
    TITLE_ASC
}
