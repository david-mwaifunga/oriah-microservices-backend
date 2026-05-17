/**
 * Root package for the catalog microservice.
 *
 * <p>The service is organized layer-by-layer:
 * <ul>
 *   <li>{@link com.oriah.catalog.controller} — REST adapters (public reads
 *       under {@code /api/v1/products}, admin writes under
 *       {@code /api/v1/admin/products}).</li>
 *   <li>{@link com.oriah.catalog.service} — transactional business
 *       operations on the {@link com.oriah.catalog.model.Product}
 *       aggregate.</li>
 *   <li>{@link com.oriah.catalog.repository} — Spring Data JPA
 *       repositories. The query catalog stays here, not in services.</li>
 *   <li>{@link com.oriah.catalog.model} — JPA entities + pure domain
 *       types. Encapsulates invariants in constructors and mutators.</li>
 *   <li>{@link com.oriah.catalog.dto} — wire-format records for requests,
 *       responses, and pagination. Bean Validation annotations live
 *       here.</li>
 * </ul>
 *
 * <p>See {@code docs/ARCHITECTURE.md} in the repo root for the rationale
 * behind each layer and the broader microservices roadmap.
 */
package com.oriah.catalog;
