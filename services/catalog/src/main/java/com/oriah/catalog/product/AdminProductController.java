package com.oriah.catalog.product;

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

import jakarta.validation.Valid;

/**
 * Admin-facing endpoints for the catalog domain. Mounted under a separate
 * path prefix from {@link ProductController} so we can apply different
 * security and rate-limit policies at the gateway and at the controller
 * level (e.g. {@code @PreAuthorize("hasRole('CATALOG_ADMIN')")} when
 * iteration 7 adds Keycloak).
 */
@RestController
@RequestMapping("/api/v1/admin/products")
public class AdminProductController {

    private final ProductService service;

    public AdminProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        Product saved = service.create(request.title(), request.description(), request.tags());
        URI location = URI.create("/api/v1/products/" + saved.id());
        return ResponseEntity.created(location).body(ProductResponse.from(saved));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponse> update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ProductUpdateRequest request) {
        return service.update(id, request)
                .map(ProductResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        return service.deleteById(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
