package com.oriah.catalog.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ProblemDetail;
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
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.service.ProductService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Products (admin)", description = "Catalogue writes, CATALOG_ADMIN only")
@ApiResponses({
        // The tail every operation on this controller shares. springdoc merges
        // class-level responses into each one, so 401/403/500 are stated once
        // rather than three times.
        //
        // 403 is declared here and NOT on ProductController, because that is
        // the real difference between the two: these endpoints are behind
        // @PreAuthorize("hasRole('CATALOG_ADMIN')"), so a valid token with the
        // wrong role is a documented outcome. Spring Security's
        // AccessDeniedException reaches the shared advice, which renders it as
        // a problem body like everything else.
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking CATALOG_ADMIN",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
@Slf4j
public class AdminProductController {

    private final ProductService service;

    public AdminProductController(ProductService service) {
        this.service = service;
    }

    /**
     * Creates a product.
     *
     * <p>Documented as <strong>201</strong>, which is what it returns. The spec
     * said {@code 200} for the whole life of this endpoint: springdoc cannot
     * see inside {@code ResponseEntity.created(...)} and falls back to 200, and
     * nothing checked. Eleven endpoints across the platform were wrong this
     * way.
     *
     * <p>The {@code Location} header is documented too — it is the only thing
     * telling a caller where the new resource lives, and it points at the
     * PUBLIC read path rather than this admin one.
     */
    @PostMapping
    @Operation(operationId = "create", summary = "Create a product")
    @ApiResponse(responseCode = "201", description = "Created; Location points at the public read path",
            headers = @io.swagger.v3.oas.annotations.headers.Header(
                    name = "Location", description = "/api/v1/products/{id}",
                    schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Validation failure; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        log.debug("POST /api/v1/admin/products title=\"{}\"", request.title());
        Product saved = service.create(request.title(), request.description(), request.tags());
        // Location points at the public read endpoint, not the admin path.
        URI location = URI.create("/api/v1/products/" + saved.id());
        return ResponseEntity.created(location).body(ProductResponse.from(saved));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "update", summary = "Partially update a product")
    @ApiResponse(responseCode = "200", description = "The updated product")
    @ApiResponse(responseCode = "400", description = "Validation failure; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No product with that id",
            content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
    public ProductResponse update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ProductUpdateRequest request) {
        log.debug("PATCH /api/v1/admin/products/{}", id);
        return service.update(id, request)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id.toString()));
    }

    /**
     * 204 on success. The {@code ResponseEntity} stays here, unlike on the
     * other two handlers: 204 is not the default status for a {@code void}
     * handler (Spring would answer 200 with an empty body), and
     * {@code @ResponseStatus(NO_CONTENT)} would move the status away from the
     * line that produces it.
     */
    @DeleteMapping("/{id}")
    @Operation(operationId = "delete", summary = "Delete a product")
    @ApiResponse(responseCode = "204", description = "Deleted; no body")
    @ApiResponse(responseCode = "404", description = "No product with that id",
            content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        log.debug("DELETE /api/v1/admin/products/{}", id);
        if (!service.deleteById(id)) {
            throw new ResourceNotFoundException("Product", "id", id.toString());
        }
        return ResponseEntity.noContent().build();
    }
}
