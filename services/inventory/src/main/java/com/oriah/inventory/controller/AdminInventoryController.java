package com.oriah.inventory.controller;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.Optional;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.inventory.dto.AdminInventoryRow;
import com.oriah.inventory.dto.ConsumeRequest;
import com.oriah.inventory.dto.InventoryResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.inventory.dto.QuantityRequest;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.service.InventoryService;
import com.oriah.inventory.service.StockService;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.extern.slf4j.Slf4j;

/**
 * Admin-facing inventory endpoints. Mounted under
 * {@code /api/v1/admin/inventory}; will carry {@code @PreAuthorize} once
 * Keycloak is wired in (iteration 7).
 *
 * <p>Reserve and release share the same shape: accept a
 * {@link QuantityRequest}, hand it to the service, return 200 on success or
 * 404 when the variant is unknown; a domain violation is a 400 or 409 through
 * the shared exception advice. Confirm is a SALE: it takes a
 * {@link ConsumeRequest} (the sale line, and when it happened), drains the
 * oldest cost lots and records what the sale cost, in one transaction.
 *
 * <p>There is no endpoint that creates a stock row or adds units to one. That
 * was retired in Phase 5: stock with no cost makes profit wrong, so stock
 * comes only from a received consignment, which brings its cost lots.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory")
@Tag(name = "Inventory (admin)", description = "Stock writes, INVENTORY_ADMIN only")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking the required role",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('INVENTORY_ADMIN')")
@Slf4j
public class AdminInventoryController {

    private final InventoryService service;
    private final StockService stock;

    public AdminInventoryController(InventoryService service, StockService stock) {
        this.service = service;
        this.stock = stock;
    }

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    @Operation(operationId = "listInventory",
            summary = "All stock records, scarcest first, optionally only out-of-stock")
    @ApiResponse(responseCode = "200", description = "A page of stock records")
    @ApiResponse(responseCode = "400",
            description = "Negative page, or size above " + MAX_PAGE_SIZE,
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public ResponseEntity<Page<AdminInventoryRow>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(name = "outOfStock", required = false) Boolean outOfStock) {

        // Rejected rather than clamped, for the same reason as the admin
        // payments list: Spring's pageable resolver silently caps an oversized
        // size, so a caller asking for everything gets a page and believes it
        // is everything. IllegalArgumentException is what the shared advice
        // renders as a 400 problem body.
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater, was " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between 1 and " + MAX_PAGE_SIZE + ", was " + size);
        }

        // Unsorted: the ordering is in the query itself, because it sorts on
        // `onHand - reserved`, which is arithmetic rather than a column and so
        // cannot be expressed as a Sort.
        return ResponseEntity.ok(service.findAdminRows(
                outOfStock, PageRequest.of(page, size)));
    }

    @Operation(operationId = "reserve", summary = "Reserve units against available stock")
    @ApiResponse(responseCode = "200", description = "Updated stock levels")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The request conflicts with current state",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{variantId}/reserve")
    public ResponseEntity<InventoryResponse> reserve(
            @PathVariable("variantId") UUID variantId,
            @Valid @RequestBody QuantityRequest request) {
        return apply(variantId, request, service::reserve);
    }

    @Operation(operationId = "release", summary = "Release units back from reserved")
    @ApiResponse(responseCode = "200", description = "Updated stock levels")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The request conflicts with current state",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{variantId}/release")
    public ResponseEntity<InventoryResponse> release(
            @PathVariable("variantId") UUID variantId,
            @Valid @RequestBody QuantityRequest request) {
        return apply(variantId, request, service::release);
    }

    @Operation(operationId = "confirm",
            summary = "Consume reserved units for a sale: the oldest cost lots are drained and the cost recorded",
            description = "The sale line makes a retry safe: the same sale line with the same quantity answers again and takes "
                    + "nothing; a sale line already used for a different request is a 409. A paid sale is never refused for its "
                    + "date, only for being in the future. This is the order system's call, not a person's button: the gateway "
                    + "does not route it.")
    @ApiResponse(responseCode = "200", description = "Updated stock levels")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The request conflicts with current state",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{variantId}/confirm")
    public ResponseEntity<InventoryResponse> confirm(
            @PathVariable("variantId") UUID variantId,
            @Valid @RequestBody ConsumeRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt == null || jwt.getSubject() == null ? "system:unknown" : "user:" + jwt.getSubject();
        return stock.consume(variantId, request.qty(), request.saleLineId(), request.occurredAt(), actor)
                .map(InventoryResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", "variantId", variantId.toString()));
    }

    /**
     * Shared shape for the three state-changing endpoints: pass the
     * variantId + qty to the right service method, return 200 + body or
     * 404 if the variant is unknown.
     */
    private ResponseEntity<InventoryResponse> apply(
            UUID variantId,
            QuantityRequest request,
            BiFunction<UUID, Integer, Optional<Inventory>> serviceCall) {
        // One 404 path shared by reserve / release, so all
        // four gained a problem body from this single change.
        //
        // Note what is NOT folded in here: an over-reserve is a 409, raised as
        // InsufficientStockException from the Inventory aggregate, not an empty
        // Optional. The two conditions were previously both "4xx with no body"
        // and are now properly distinct -- 404 the variant does not exist, 409
        // it exists but the numbers do not allow this.
        return serviceCall.apply(variantId, request.qty())
                .map(InventoryResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Inventory", "variantId", variantId.toString()));
    }
}
