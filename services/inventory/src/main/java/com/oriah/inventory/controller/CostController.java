package com.oriah.inventory.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.inventory.service.CostReadService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * What stock cost. {@code COST_VIEWER} only (the owner, a manager, a bookkeeper): cost is gated at the API, by an
 * allow-list, never by hiding a column in a screen and never by blocking the sales assistant alone. The machine
 * identity that calls inventory (INVENTORY_ADMIN) is refused here too. These replies are the ONLY ones with a cost in
 * them: the stock list, the public read, a sale's reply and a write-off's reply have none, and a test proves it.
 */
@RestController
@RequestMapping("/api/v1/admin/costs")
@Tag(name = "Costs (admin)", description = "What stock cost: lots and the ledger; COST_VIEWER only")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking COST_VIEWER",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('COST_VIEWER')")
public class CostController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final CostReadService costs;

    public CostController(CostReadService costs) {
        this.costs = costs;
    }

    @Operation(operationId = "listCostLots",
            summary = "The lots: what each slice of each received consignment line cost per unit, and how many are still held",
            description = "Oldest first, which is the order oldest-first selling draws in. Each lot carries the names it was received under.")
    @ApiResponse(responseCode = "200", description = "A page of lots")
    @ApiResponse(responseCode = "400", description = "Negative page, or size above " + MAX_PAGE_SIZE,
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/lots")
    public ResponseEntity<Page<CostReadService.CostLotRow>> lots(
            @RequestParam(name = "variantId", required = false) UUID variantId,
            @RequestParam(name = "open", defaultValue = "false") boolean open,
            @RequestParam(name = "includeReplaced", defaultValue = "false") boolean includeReplaced,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        checkPage(page, size);
        return ResponseEntity.ok(costs.lots(variantId, open, includeReplaced, page, size));
    }

    @Operation(operationId = "listCostMovements",
            summary = "The ledger: what every action (a receipt, a sale, a write-off, a give-away, a take-back, a correction) did to each lot, at the lot's cost",
            description = "In the order the rows were written. A row a correction reversed is no longer live, but stays on record; "
                    + "the cost released by a row is -qtyDelta x unitCost.")
    @ApiResponse(responseCode = "200", description = "A page of ledger rows")
    @ApiResponse(responseCode = "400", description = "Negative page, size above " + MAX_PAGE_SIZE + ", or a reason that is not on the list",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/movements")
    public ResponseEntity<Page<CostReadService.MovementRow>> movements(
            @RequestParam(name = "variantId", required = false) UUID variantId,
            @RequestParam(name = "lotId", required = false) UUID lotId,
            @RequestParam(name = "actionId", required = false) UUID actionId,
            @RequestParam(name = "reason", required = false) String reason,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        checkPage(page, size);
        return ResponseEntity.ok(costs.movements(variantId, lotId, actionId, reason, page, size));
    }

    /** Rejected rather than clamped, as for every admin list: a caller asking for everything must not get a page and believe it is everything. */
    private static void checkPage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater, was " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE + ", was " + size);
        }
    }
}
