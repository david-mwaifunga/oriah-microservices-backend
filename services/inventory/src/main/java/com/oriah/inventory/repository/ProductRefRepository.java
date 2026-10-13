package com.oriah.inventory.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.inventory.model.ProductRef;

/**
 * Spring Data repository for {@link ProductRef}. No custom queries needed
 * yet — primary-key lookups, upserts (via save), and deletes are enough
 * to back the {@link com.oriah.inventory.events.ProductEventConsumer}.
 */
public interface ProductRefRepository extends JpaRepository<ProductRef, UUID> {

    /**
     * Every variant reference belonging to one catalog product.
     *
     * <p>The reason product_index carries a product_id at all. A
     * product.deleted event names a PRODUCT, while the rows are keyed by
     * VARIANT, so without this there is no way to find the rows the event
     * refers to.
     */
    java.util.List<ProductRef> findByProductId(UUID productId);
}
