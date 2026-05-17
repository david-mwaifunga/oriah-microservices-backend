package com.oriah.catalog.product;

import java.net.URI;

import org.springframework.http.ResponseEntity;
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
}
