package com.oriah.catalog.repository;

import java.util.List;
import java.util.UUID;

import com.oriah.catalog.model.ProductKind;
import com.oriah.catalog.model.ProductStatus;

/**
 * The operator's view of the catalogue: every product that has not been
 * deleted, whatever its status or kind.
 *
 * <p>Kept apart from {@link ProductStorefrontQueries} on purpose. The storefront
 * query bakes in "ACTIVE, sellable, priced" and the two have opposite jobs: one
 * must never show a draft, the other exists to find one. Parameterising a single
 * query on who is asking would make one code path whose visibility depends on the
 * caller, which is the kind of conditional that leaks.
 */
public interface ProductAdminQueries {

    /**
     * The ids of one page, newest first (id as the tiebreak, so a page boundary
     * never repeats or skips a row).
     *
     * @param kind   null for both kinds
     * @param status null for every status
     * @param text   null or blank for no text filter; otherwise matched as plain
     *               text, case-insensitively, against the title and against every
     *               variant's SKU
     */
    List<UUID> findAdminIds(ProductKind kind, ProductStatus status, String text, int page, int size);

    long countAdmin(ProductKind kind, ProductStatus status, String text);
}
