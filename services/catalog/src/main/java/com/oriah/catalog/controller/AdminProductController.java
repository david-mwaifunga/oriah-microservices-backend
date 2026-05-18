package com.oriah.catalog.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.security.access.prepost.PreAuthorize;

import com.oriah.catalog.dto.ProductCreateRequest;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.dto.ProductUpdateRequest;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.service.ProductService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin-facing endpoints for the catalog domain.
 *
 * <p>Mounted under a separate path prefix from {@link ProductController} so
 * we can apply different security and rate-limit policies at the gateway
 * and at the controller level (e.g.
 * {@code @PreAuthorize("hasRole('CATALOG_ADMIN')")} when iteration 7 adds
 * Keycloak).
 *
 * <h2>Logging</h2>
 * Inbound mutations are logged at <b>DEBUG</b> here — the matching INFO
 * line lands in {@link ProductService}, which carries the canonical "who
 * changed what" record. Logging twice would duplicate every event.
 */
@RestController
@RequestMapping("/api/v1/admin/products")
@PreAuthorize("hasRole('CATALOG_ADMIN')")
@Slf4j
public class AdminProductController {

    private final ProductService service;

    public AdminProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        log.debug("POST /api/v1/admin/products title=\"{}\"", request.title());
        Product saved = service.create(request.title(), request.description(), request.tags());
        // Location points at the public read endpoint, not the admin path.
        URI location = URI.create("/api/v1/products/" + saved.id());
        return ResponseEntity.created(location).body(ProductResponse.from(saved));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponse> update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ProductUpdateRequest request) {
        log.debug("PATCH /api/v1/admin/products/{}", id);
        return service.update(id, request)
                .map(ProductResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        log.debug("DELETE /api/v1/admin/products/{}", id);
        return service.deleteById(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
