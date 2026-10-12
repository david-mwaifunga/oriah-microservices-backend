package com.oriah.catalog.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.catalog.model.Product;

public interface ProductRepository extends JpaRepository<Product, UUID>, ProductStorefrontQueries {

    /**
     * Used to resolve a slug collision before insert.
     *
     * <p>This is a check-then-act, so it does not make the insert safe on its
     * own -- two concurrent creates of the same title both see "free" and one
     * loses. The UNIQUE constraint remains the actual guarantee, and
     * ProductService retries with a collision-proof slug when it fires. The
     * probe exists only so the common case produces a readable URL rather than
     * an id-suffixed one.
     */
    boolean existsBySlug(String slug);

    // findOptionallyFilteredByTag lived here and is gone with its only caller.
    // The storefront query handles the tag filter alongside category, type and
    // price, and it also excludes DRAFT and ARCHIVED products -- which this
    // method did not. Keeping both would have left a second list path that
    // quietly returns unpublished, unpriced products, for a future caller to
    // choose by accident.
}
