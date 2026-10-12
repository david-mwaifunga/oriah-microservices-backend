package com.oriah.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.catalog.model.ProductVariant;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    Optional<ProductVariant> findBySku(String sku);

    /**
     * Variants by id, with their product fetched.
     *
     * <p>The fetch join is required rather than convenient: pricing needs the
     * product's title and its publication status, and resolving those lazily
     * would be one extra query per variant -- turning a ten-line bag into
     * eleven queries, which is the N+1 this batch endpoint exists to avoid.
     */
    @Query("SELECT v FROM ProductVariant v JOIN FETCH v.product WHERE v.id IN :ids")
    List<ProductVariant> findAllByIdInWithProduct(@Param("ids") List<UUID> ids);
}
