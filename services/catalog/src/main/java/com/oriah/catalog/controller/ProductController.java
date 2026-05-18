package com.oriah.catalog.controller;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.catalog.dto.PageResponse;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.service.ProductService;

import lombok.extern.slf4j.Slf4j;

/**
 * Public read endpoints for the catalog.
 *
 * <p>No auth requirements (anyone can browse the shop). Sister controller
 * {@link AdminProductController} hosts the write endpoints under
 * {@code /api/v1/admin/products}.
 *
 * <h2>Logging</h2>
 * Reads stay at DEBUG — these run on every page render, so INFO logging
 * would dwarf the actually-interesting state-change events. Spring's
 * built-in request logging plus access logs at the gateway provide the
 * traffic-level audit trail.
 */
@RestController
@RequestMapping("/api/v1/products")
@Slf4j
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    /**
     * Paginated list of products, optionally filtered by tag. Returns a
     * {@link PageResponse} envelope rather than Spring's verbose Page JSON
     * so the wire shape stays stable across upgrades.
     */
    @GetMapping
    public PageResponse<ProductResponse> list(
            @RequestParam(value = "tag", required = false) String tag,
            @PageableDefault(size = 20) Pageable pageable) {
        log.debug("GET /api/v1/products tag={} page={} size={}",
                tag, pageable.getPageNumber(), pageable.getPageSize());
        return PageResponse.from(service.findAll(tag, pageable), ProductResponse::from);
    }

    /**
     * Single product by id. Returns 200 with the body or 404 if the id is
     * unknown.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getById(@PathVariable("id") UUID id) {
        log.debug("GET /api/v1/products/{}", id);
        // PDP endpoint: enrich with availability via the inventory service.
        // Resilience4j wraps the inventory call so catalog stays up even
        // when inventory is down (availability falls back to UNKNOWN).
        return service.findByIdWithAvailability(id)
                .map(ProductResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
