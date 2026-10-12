package com.oriah.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.catalog.model.ProductType;

/**
 * Read access to the seeded {@code product_types} rows.
 *
 * <p>There is no write path on purpose. A product type is half DB row and
 * half Java ({@link com.oriah.catalog.model.ProductTypeRegistry} owns its
 * attribute definitions), so adding one is a deploy, not a form submission --
 * a row inserted without matching definitions would be a type whose products
 * validate against no rules at all.
 */
public interface ProductTypeRepository extends JpaRepository<ProductType, String> {
}
