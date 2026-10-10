package com.oriah.catalog.controller;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.catalog.dto.PageResponse;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.catalog.service.ProductService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 *
 * <h2>OpenAPI annotations</h2>
 *
 * <p>The class-level {@code @ApiResponses} is the shared tail of every
 * operation here. springdoc merges class-level responses into each one, so the
 * things that are true of <em>all</em> of them are stated once instead of per
 * method.
 *
 * <p>Note what is <strong>not</strong> declared: no {@code 401}. These reads
 * are {@code permitAll}, so documenting an unauthorized response would send a
 * client writing a branch that can never fire. The error responses are
 * per-endpoint for the same reason — see {@code list} versus {@code getById}.
 */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Public catalogue reads")
@ApiResponses({
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
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
     *
     * <p>Documents no {@code 404}: an unknown tag, or a page past the end,
     * answers {@code 200} with an empty {@code content} array. A documented 404
     * here would be as misleading as the missing ones were.
     */
    @GetMapping
    @Operation(operationId = "list",
            summary = "List products, newest first, optionally filtered by tag")
    @ApiResponse(responseCode = "200", description = "A page of products, possibly empty")
    @ApiResponse(responseCode = "400", description = "Unparseable paging or sort parameter",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PageResponse<ProductResponse> list(
            @RequestParam(value = "tag", required = false) String tag,
            @PageableDefault(size = 20) Pageable pageable) {
        log.debug("GET /api/v1/products tag={} page={} size={}",
                tag, pageable.getPageNumber(), pageable.getPageSize());
        return PageResponse.from(service.findAll(tag, pageable), ProductResponse::from);
    }

    /**
     * Single product by id. Returns 200 with the body, or a 404 carrying an
     * RFC 7807 problem body naming what was not found.
     *
     * <p>This used to end in {@code orElseGet(() ->
     * ResponseEntity.notFound().build())}, which answered 404 with an
     * <strong>empty body</strong> — see {@link ResourceNotFoundException} for
     * why that was a worse answer than it looks. The {@code orElseThrow} form
     * is also what lets the return type drop {@code ResponseEntity}: the
     * handler now describes the success case only, and the error case is one
     * exception the advice renders consistently with every other service.
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getById",
            summary = "One product by id, enriched with stock availability")
    @ApiResponse(responseCode = "200", description = "The product, with availability")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No product with that id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ProductResponse getById(@PathVariable("id") UUID id) {
        log.debug("GET /api/v1/products/{}", id);
        // PDP endpoint: enrich with availability via the inventory service.
        // Resilience4j wraps the inventory call so catalog stays up even
        // when inventory is down (availability falls back to UNKNOWN).
        return service.findByIdWithAvailability(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id.toString()));
    }
}
