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

import com.oriah.catalog.constants.CatalogConstants;
import java.math.BigDecimal;

import com.oriah.catalog.dto.PageResponse;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.catalog.model.ProductSort;
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
@RequestMapping(CatalogConstants.PUBLIC_BASE)
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
            summary = "List published products, filtered and sorted",
            description = "Returns ACTIVE products only. DRAFT and ARCHIVED are never "
                    + "listed, which is also why the catalogue looks empty immediately "
                    + "after the variants migration: it left every pre-existing product "
                    + "DRAFT because none of them had a price anyone had chosen. "
                    + "`orderBy` is named that way rather than `sort` so it does not "
                    + "collide with Spring's own paging `sort` parameter, and it is a "
                    + "closed set of values because the ordering has to be built into "
                    + "SQL -- a caller-supplied sort expression would be an injection. "
                    + "`category` matches the category AND its descendants, so filtering "
                    + "by a parent returns everything beneath it. An unknown category "
                    + "slug is a 400, unlike an unknown `tag`, which answers 200 with an "
                    + "empty page: a tag is a free-form label whose absence means nothing, "
                    + "while a category is a closed set. "
                    + "Price filtering uses the cheapest PURCHASABLE variant -- active and "
                    + "priced above zero -- so a deactivated or unpriced variant cannot "
                    + "pull a product into a price band a customer cannot actually buy at.")
    @ApiResponse(responseCode = "200", description = "A page of products, possibly empty")
    @ApiResponse(responseCode = "400", description = "Unparseable paging or sort parameter",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PageResponse<ProductResponse> list(
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "minPrice", required = false) BigDecimal minPrice,
            @RequestParam(value = "maxPrice", required = false) BigDecimal maxPrice,
            @RequestParam(value = "orderBy", required = false) ProductSort orderBy,
            @PageableDefault(size = CatalogConstants.DEFAULT_PAGE_SIZE) Pageable pageable) {
        log.debug("GET /api/v1/products tag={} category={} type={} orderBy={} page={} size={}",
                tag, category, type, orderBy, pageable.getPageNumber(), pageable.getPageSize());
        return PageResponse.from(
                service.findStorefront(category, type, tag, minPrice, maxPrice, orderBy, pageable),
                r -> r);
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
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id.toString()));
    }
}
