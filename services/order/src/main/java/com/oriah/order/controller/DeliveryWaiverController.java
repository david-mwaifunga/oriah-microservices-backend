package com.oriah.order.controller;

import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.order.dto.OrderResponse;
import com.oriah.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Free delivery on one order, for a chosen customer (Phase 7; CLIENT_DECISIONS Q2). {@code DELIVERY_WAIVER} only -- held by the owner, not by a manager or a
 * sales person -- and always with a reason. The gateway's {@code /api/v1/admin/orders/&#42;/delivery-waiver/**} matcher guards this path; the capability is
 * checked here as well, so the edge is not the only thing standing in front of it.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Delivery waiver", description = "Free delivery on one order, before it is paid; DELIVERY_WAIVER")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking DELIVERY_WAIVER",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('DELIVERY_WAIVER')")
public class DeliveryWaiverController {

    /** @param reason why, 5 to 200 characters after trimming: padding cannot make a short one long enough, nor spoil a good one */
    public record WaiveDeliveryRequest(
            @NotNull(message = "a reason is required") @Size(min = 5, max = 200, message = "the reason must be 5 to 200 characters") String reason) {

        public WaiveDeliveryRequest {
            reason = reason == null ? null : reason.trim();
        }
    }

    private final OrderService orders;

    public DeliveryWaiverController(OrderService orders) {
        this.orders = orders;
    }

    @Operation(operationId = "waiveDelivery", summary = "Make delivery free on an order that is still waiting for a payment reference",
            description = "Delivery becomes K0.00 and the order's total falls by exactly what delivery would have cost; who, why, when and how much are kept on the order "
                    + "(delivery revenue given up, never goods revenue). Only an order that is CREATED with its pieces held and no money being applied: once a payment "
                    + "is claimed, being applied or made, the total cannot move. Staff must then confirm the new, lower total. One waiver per order.")
    @ApiResponse(responseCode = "200", description = "The order, with its new total and the waiver recorded")
    @ApiResponse(responseCode = "400", description = "A reason of 5 to 200 characters is required; see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "delivery-waiver-not-allowed (not waiting for a payment reference, or still being placed), payment-being-applied, "
            + "delivery-already-waived, no-delivery-to-waive (a pickup, a courier the recipient pays, or free already)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/delivery-waiver")
    public OrderResponse waive(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id, @Valid @RequestBody WaiveDeliveryRequest body) {
        return OrderResponse.forStaff(orders.waiveDelivery(id, body.reason(), Actor.of(jwt)));
    }
}
