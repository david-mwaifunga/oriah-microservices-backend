package com.oriah.order.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.order.service.DispatchReadService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The packer's work list (Phase 8): paid orders by stage, oldest payment first. FULFILMENT_AGENT only, at an EXACT gateway path, and carrying no money.
 * A controller of its own, like the sales queues, because the rest of {@code /api/v1/admin/orders} is ORDER_ADMIN's and its answer carries totals and logins.
 */
@RestController
@RequestMapping("/api/v1/admin/orders/dispatch")
@Tag(name = "Dispatch (staff)", description = "Pack, send and hand over a paid order, recording the courier fare when Oriah pays it; FULFILMENT_AGENT")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking FULFILMENT_AGENT",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('FULFILMENT_AGENT')")
public class DispatchQueueController {

    private final DispatchReadService reads;

    public DispatchQueueController(DispatchReadService reads) {
        this.reads = reads;
    }

    @Operation(operationId = "dispatchQueue", summary = "The packer's work list: paid orders to pack, packed, or on the way",
            description = "Oldest payment first, at most 100. A row says who the parcel is for, by what option and to which town, how many pieces, and whether it can be "
                    + "packed (an order placed before Phase 8 has no recipient or address kept and is marked not packable). No money, no logins.")
    @ApiResponse(responseCode = "200", description = "The work list")
    @ApiResponse(responseCode = "400", description = "A stage that is not TO_PACK, PACKED or ON_THE_WAY",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/queue")
    public DispatchReadService.Queue queue(
            @RequestParam(name = "stage", required = false) @Schema(description = "TO_PACK (default), PACKED or ON_THE_WAY") String stage) {
        return reads.queue(stage);
    }
}
