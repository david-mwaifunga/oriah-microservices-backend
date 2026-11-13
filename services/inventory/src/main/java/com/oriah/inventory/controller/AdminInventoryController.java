package com.oriah.inventory.controller;

import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.inventory.dto.AdminInventoryRow;
import com.oriah.inventory.dto.InventoryResponse;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.service.InventoryService;
import com.oriah.inventory.service.StockService;

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
 * <p>A hold and a sale are not here: since Phase 7 an order's hold on its pieces and boxes is a reservation with an owner and a clock
 * ({@code /api/v1/admin/inventory/reservations}, the order system's calls), and the per-variant reserve, release and confirm that moved
 * a bare counter are gone.
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

    @Operation(operationId = "reconcileInventory",
            summary = "Check every stock count against its cost lots, and every lot against its ledger",
            description = "One count, not three: on-hand must equal what the current lots hold. Counts only, no cost. "
                    + "A clean system reports no mismatches.")
    @ApiResponse(responseCode = "200", description = "The report; both lists are empty when everything agrees")
    @GetMapping("/reconciliation")
    public ResponseEntity<StockService.ReconciliationReport> reconcile(
            @RequestParam(name = "variantId", required = false) UUID variantId) {
        return ResponseEntity.ok(stock.reconcile(variantId));
    }
}
