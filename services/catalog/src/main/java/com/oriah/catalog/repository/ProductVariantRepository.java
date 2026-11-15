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

    /**
     * Every variant that has a price and is on offer: active, priced above zero, of a SELLABLE product that has not been deleted
     * (drafts included: a priced draft is still a price). The list that compares every price with the cost starts here. Packaging is
     * never priced, and a deleted product's variants are not on offer.
     */
    @Query("SELECT v FROM ProductVariant v JOIN FETCH v.product p WHERE v.active = true AND v.unitPrice > 0 "
            + "AND p.kind = com.oriah.catalog.model.ProductKind.SELLABLE AND p.deleted = false ORDER BY p.title, v.sku")
    List<ProductVariant> findPricedForSale();

    /** Every active variant of a packaging product that is not deleted: the boxes a sale can go out in (Phase 9), by name and SKU. */
    @Query("SELECT v FROM ProductVariant v JOIN FETCH v.product p WHERE v.active = true AND p.kind = com.oriah.catalog.model.ProductKind.PACKAGING AND p.deleted = false "
            + "ORDER BY p.title, v.sku")
    List<ProductVariant> findPackagingInUse();
}
