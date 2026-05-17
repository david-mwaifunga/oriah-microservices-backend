/**
 * Wire-format records for the catalog API.
 *
 * <p>Three categories:
 * <ul>
 *   <li><b>Request records</b> —
 *       {@link com.oriah.catalog.dto.ProductCreateRequest},
 *       {@link com.oriah.catalog.dto.ProductUpdateRequest}.
 *       Carry {@code jakarta.validation} annotations so {@code @Valid}
 *       in controllers rejects bad input before it reaches the service.</li>
 *   <li><b>Response records</b> —
 *       {@link com.oriah.catalog.dto.ProductResponse}.
 *       Carries the public-facing shape of a product, stripped of
 *       internal fields the API shouldn't expose.</li>
 *   <li><b>Envelope</b> —
 *       {@link com.oriah.catalog.dto.PageResponse}.
 *       Stable pagination wrapper that decouples our wire shape
 *       from Spring Data's verbose Page JSON.</li>
 * </ul>
 *
 * <p>DTOs are records (immutable, equals/hashCode/toString auto-generated)
 * and live at the boundary — they MUST NOT leak into the service or
 * persistence layers.
 */
package com.oriah.catalog.dto;
