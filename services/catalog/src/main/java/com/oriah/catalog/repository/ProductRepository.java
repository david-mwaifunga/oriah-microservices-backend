package com.oriah.catalog.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.catalog.model.Product;

public interface ProductRepository extends JpaRepository<Product, UUID>, ProductStorefrontQueries {

    /**
     * Returns a page of products, optionally filtered by a single tag.
     *
     * <p>When {@code tag} is null the filter is bypassed — JPQL short-circuits
     * via the first {@code OR} condition. When non-null, {@code MEMBER OF}
     * joins through the {@code product_tags} table created by
     * {@code V1__create_products.sql}.
     */
    @Query("SELECT p FROM Product p WHERE :tag IS NULL OR :tag MEMBER OF p.tags")
    Page<Product> findOptionallyFilteredByTag(@Param("tag") String tag, Pageable pageable);
}
