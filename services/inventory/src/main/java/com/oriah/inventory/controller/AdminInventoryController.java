package com.oriah.inventory.controller;

import java.net.URI;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.Optional;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.inventory.dto.InventoryCreateRequest;
import com.oriah.inventory.dto.InventoryResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.inventory.dto.QuantityRequest;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.service.InventoryService;

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
 * <p>Reserve, release, confirm, and restock all share the same shape:
 * accept a {@link QuantityRequest}, hand it to the service, return 200
 * on success or 404 when the variant is unknown. The service throws
 * {@link IllegalArgumentException} for domain violations (negative qty,
 * over-reserve, etc.); Spring's default exception handling maps that to
 * 500 today — we'd add a {@code @RestControllerAdvice} to translate it
 * to 400/409 in a polish cycle.
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

    public AdminInventoryController(InventoryService service) {
        this.service = service;
    }

    @Operation(operationId = "create", summary = "Create a stock record for a variant")
    @ApiResponse(responseCode = "201", description = "Created; Location points at the public read path")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<InventoryResponse> create(
            @Valid @RequestBody InventoryCreateRequest request) {
        log.debug("POST /api/v1/admin/inventory variantId={}", request.variantId());
        Inventory saved = service.create(request.variantId(), request.onHand());
        URI location = URI.create("/api/v1/inventory/" + saved.variantId());
        return ResponseEntity.created(location).body(InventoryResponse.from(saved));
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

    @Operation(operationId = "confirm", summary = "Confirm reserved units as shipped")
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
            @Valid @RequestBody QuantityRequest request) {
        return apply(variantId, request, service::confirm);
    }

    @Operation(operationId = "restock", summary = "Add units to on-hand stock")
    @ApiResponse(responseCode = "200", description = "Updated stock levels")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{variantId}/restock")
    public ResponseEntity<InventoryResponse> restock(
            @PathVariable("variantId") UUID variantId,
            @Valid @RequestBody QuantityRequest request) {
        return apply(variantId, request, service::restock);
    }

    /**
     * Shared shape for the four state-changing endpoints: pass the
     * variantId + qty to the right service method, return 200 + body or
     * 404 if the variant is unknown.
     */
    private ResponseEntity<InventoryResponse> apply(
            UUID variantId,
            QuantityRequest request,
            BiFunction<UUID, Integer, Optional<Inventory>> serviceCall) {
        // One 404 path shared by reserve / release / confirm / restock, so all
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
