package com.oriah.procurement.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.procurement.dto.CreatePurchaseOrderRequest;
import com.oriah.procurement.dto.PurchaseOrderResponse;
import com.oriah.procurement.dto.UpdatePurchaseOrderRequest;
import com.oriah.procurement.dto.VersionRequest;
import com.oriah.procurement.model.PurchaseOrderStatus;
import com.oriah.procurement.service.PurchaseOrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * What Oriah placed with its suppliers (CLIENT_DECISIONS 4.1, "the order, when you place it").
 * Only the people who hold the procurement capability touch it; the gateway requires the same
 * capability for this prefix and the service requires it again.
 */
@RestController
@RequestMapping(PurchaseOrderController.BASE_PATH)
@PreAuthorize("hasRole('PROCUREMENT_ADMIN')")
@Tag(name = "Purchase orders", description = "What Oriah placed with one supplier")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking the procurement capability",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
public class PurchaseOrderController {

    static final String BASE_PATH = "/api/v1/admin/procurement/purchase-orders";

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;

    private final PurchaseOrderService service;

    public PurchaseOrderController(PurchaseOrderService service) {
        this.service = service;
    }

    @Operation(operationId = "createPurchaseOrder", summary = "Place an order with one supplier",
            description = "One line per item: the variant, how many, and the all-in total paid (as the bank app "
                    + "shows it). Every variant is checked with the catalogue once, here: it must exist, be "
                    + "switched on, and belong to a product that is not deleted or archived (a DRAFT product "
                    + "and packaging are fine).")
    @ApiResponse(responseCode = "201", description = "The order was placed; Location points at it")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule, or a variant cannot be ordered; see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such supplier",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The supplier has been stopped: it gets no new orders",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "The catalogue could not be asked; nothing was saved. Retry-After says when to try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<PurchaseOrderResponse> create(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        PurchaseOrderResponse placed = service.place(request);
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + placed.id())).body(placed);
    }

    @Operation(operationId = "listPurchaseOrders",
            summary = "The orders, newest first, optionally one supplier's or one status")
    @ApiResponse(responseCode = "200", description = "A page of orders")
    @ApiResponse(responseCode = "400",
            description = "size outside 1-100, a negative page or one too far into the list, a status that does not exist, or a sort parameter",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public Page<PurchaseOrderResponse> list(
            @Parameter(description = "Which page, counting from 0", schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            @RequestParam(name = "page", defaultValue = "0") int page,
            @Parameter(description = "Orders per page, 1 to 100", schema = @Schema(type = "integer", minimum = "1",
                    maximum = "" + MAX_PAGE_SIZE, defaultValue = "" + DEFAULT_PAGE_SIZE))
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @Parameter(description = "Only this supplier's orders") @RequestParam(name = "supplierId", required = false) UUID supplierId,
            @Parameter(description = "Only orders in this status") @RequestParam(name = "status", required = false) PurchaseOrderStatus status,
            @RequestParam(name = "sort", required = false) String sort) {
        // Refused, not clamped or ignored: a quietly changed request hides the caller's mistake.
        if (sort != null) {
            throw new IllegalArgumentException("This endpoint is always ordered newest first and does not accept a "
                    + "'sort' parameter. Remove it.");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater, was " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE + ", was " + size);
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page " + page + " is too far into the list for a size of " + size);
        }
        return service.list(page, size, supplierId, status);
    }

    @Operation(operationId = "getPurchaseOrder", summary = "One order")
    @ApiResponse(responseCode = "200", description = "The order")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public PurchaseOrderResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(operationId = "updatePurchaseOrder", summary = "Change an open order",
            description = "Only while the order is OPEN and not in a consignment (take it out of the consignment "
                    + "first). Send the version the change was made from. Fields left out are unchanged; lines, "
                    + "when sent, replace all the lines and are checked with the catalogue again.")
    @ApiResponse(responseCode = "200", description = "The order as it is now")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule, or a variant cannot be ordered; see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The order has changed since that version, is not open, is in a "
            + "consignment, or (for lines) its supplier has been stopped",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "The catalogue could not be asked; nothing was changed",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PatchMapping("/{id}")
    public PurchaseOrderResponse update(@PathVariable UUID id, @Valid @RequestBody UpdatePurchaseOrderRequest request) {
        return service.update(id, request);
    }

    @Operation(operationId = "cancelPurchaseOrder", summary = "Cancel an open order",
            description = "Only while OPEN and not in a consignment. A cancelled order is history and never changes again.")
    @ApiResponse(responseCode = "200", description = "The order, now CANCELLED")
    @ApiResponse(responseCode = "400", description = "The version is missing",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The order has changed since that version, is not open, or is in a consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/cancel")
    public PurchaseOrderResponse cancel(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return service.cancel(id, request.version());
    }
}
