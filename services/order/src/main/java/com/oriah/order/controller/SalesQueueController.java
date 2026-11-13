package com.oriah.order.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.order.service.SalesQueues;
import com.oriah.order.service.SalesQueues.AwaitingPayment;
import com.oriah.order.service.SalesQueues.SettlementRow;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * What the people who confirm money work from (Phase 7): SALES_AGENT, the capability a sales person, a manager and the owner hold. A SEPARATE
 * controller from {@link AdminOrderController}, whose class-level ORDER_ADMIN would shut sales staff out of it; the gateway has matching rules
 * for these two paths BEFORE its ORDER_ADMIN rule (first match wins).
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Sales queues", description = "Who says they have paid, and money that has not paid its sale; SALES_AGENT")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking SALES_AGENT",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('SALES_AGENT')")
public class SalesQueueController {

    private final SalesQueues queues;

    public SalesQueueController(SalesQueues queues) {
        this.queues = queues;
    }

    @Operation(operationId = "awaitingPayment", summary = "Orders whose shopper says they have paid, soonest to lapse first",
            description = "Each carries the method and reference the shopper gave, for staff to find in the bank or the provider's records and confirm "
                    + "(or reject). With orderId: that one order if it can still take money (waiting for payment, claimed, or lapsed), so money can be "
                    + "confirmed for a customer who never typed a reference. At most 100.")
    @ApiResponse(responseCode = "200", description = "The orders (empty when none)")
    @GetMapping("/awaiting-payment")
    public List<AwaitingPayment> awaitingPayment(@RequestParam(name = "orderId", required = false) UUID orderId) {
        return queues.awaitingPayment(orderId);
    }

    @Operation(operationId = "settlements", summary = "Confirmed money that has not paid its sale",
            description = "In flight (stuck when it has waited more than five minutes), waiting for a box (the box is named), unsettled with the reason it "
                    + "could not pay the sale, or voided. Newest first, at most 100. state: IN_FLIGHT, BLOCKED_BOX, UNSETTLED or VOIDED.")
    @ApiResponse(responseCode = "200", description = "The settlements (empty when none)")
    @ApiResponse(responseCode = "400", description = "An unknown state filter",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/settlements")
    public List<SettlementRow> settlements(@RequestParam(name = "state", required = false) String state) {
        return queues.settlements(state);
    }
}
