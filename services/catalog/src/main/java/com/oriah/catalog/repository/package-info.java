/**
 * Spring Data JPA repositories.
 *
 * <p>Repositories own the query catalog. Custom queries live here as
 * {@code @Query}-annotated methods (JPQL) rather than being scattered
 * into services as raw Criteria/JPQL strings. Services treat repositories
 * as the only way to read or write {@link com.oriah.catalog.model.Product}.
 */
package com.oriah.catalog.repository;
