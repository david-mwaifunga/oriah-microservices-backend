package com.oriah.catalog.repository;

import java.util.List;
import java.util.UUID;

import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductSort;
import com.oriah.catalog.model.StorefrontFilter;

/**
 * Skeleton only -- behaviour arrives next, driven by
 * ProductStorefrontQueryTest.
 */
public interface ProductStorefrontQueries {

    List<UUID> findStorefrontIds(StorefrontFilter filter, ProductSort sort, int page, int size);

    long countStorefront(StorefrontFilter filter);

    List<Product> findAllWithDetailByIdIn(List<UUID> ids);
}
