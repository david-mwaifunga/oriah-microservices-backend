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
}
