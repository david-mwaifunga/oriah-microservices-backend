/**
 * REST adapters for the catalog service.
 *
 * <p>Two controllers, deliberately split:
 * <ul>
 *   <li>{@link com.oriah.catalog.controller.ProductController} — public
 *       reads (no auth). Mounted at {@code /api/v1/products}.</li>
 *   <li>{@link com.oriah.catalog.controller.AdminProductController} —
 *       admin writes. Mounted at {@code /api/v1/admin/products}. Will
 *       carry {@code @PreAuthorize} once Keycloak lands.</li>
 * </ul>
 *
 * <p>Controllers translate between the wire-format records in
 * {@link com.oriah.catalog.dto} and the plain values
 * {@link com.oriah.catalog.service.ProductService} expects, keeping the
 * service layer free of HTTP/JSON concerns.
 */
package com.oriah.catalog.controller;
