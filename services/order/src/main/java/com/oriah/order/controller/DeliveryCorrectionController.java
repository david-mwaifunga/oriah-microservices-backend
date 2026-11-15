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

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.dto.OrderResponse;
import com.oriah.order.service.DeliveryCorrectionService;
import com.oriah.order.service.DispatchViews;
import com.oriah.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Correcting who a paid parcel is for and where it goes, before it is packed (Phase 8). ORDER_ADMIN only -- the owner and the manager, never a packer: a
 * person who could redirect a paid parcel could send it to an accomplice. The gateway's ORDER_ADMIN prefix guards this path; the capability is checked here too.
 */
@RestController
@RequestMapping("/api/v1/admin/orders/{id}/delivery-details")
@Tag(name = "Delivery details (admin)", description = "Correct the recipient and address of a paid order before it is packed; ORDER_ADMIN")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking ORDER_ADMIN",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('ORDER_ADMIN')")
public class DeliveryCorrectionController {

    public record AddressBody(
            @NotBlank @Size(max = 255) String line1,
            @Size(max = 255) String line2,
            @NotBlank @Size(max = 120) String city,
            @Size(max = 120) String state,
            @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter country code") String country) {
    }

    /**
     * @param recipientName  who the parcel is for
     * @param recipientPhone their phone number as typed (097 123 4567, +260 ...); kept as +260971234567
     * @param address        where it goes
     * @param reason         why it is corrected, 5 to 200 characters, kept with the previous details
     */
    public record CorrectDeliveryRequest(
            @NotBlank @Size(max = 120) String recipientName,
            @NotBlank @Size(max = 40) String recipientPhone,
            @NotNull @Valid AddressBody address,
            @NotNull(message = "a reason is required") @Size(min = 5, max = 200, message = "the reason must be 5 to 200 characters") String reason) {

        public CorrectDeliveryRequest {
            reason = reason == null ? null : reason.trim();
        }
    }

    private final DeliveryCorrectionService corrections;
    private final OrderService orders;
    private final DispatchViews views;

    public DeliveryCorrectionController(DeliveryCorrectionService corrections, OrderService orders, DispatchViews views) {
        this.corrections = corrections;
        this.orders = orders;
        this.views = views;
    }

    @Operation(operationId = "correctDeliveryDetails", summary = "Correct who a paid order is for and where it goes, before it is packed",
            description = "Replaces the whole delivery block (nothing left out is kept); the previous details, who changed them and why are kept in an append-only "
                    + "history, and nothing is published. Only a PAID order that is not packed yet: once packed the label says where it goes. The option and the "
                    + "town the delivery was priced for are not changed here.")
    @ApiResponse(responseCode = "200", description = "The order, with its corrected delivery")
    @ApiResponse(responseCode = "400", description = "validation: a reason of 5 to 200 characters, a readable name and phone, a whole address; see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "order-not-paid, no-delivery-details (a pickup), delivery-details-locked (packed already)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public OrderResponse correct(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id, @Valid @RequestBody CorrectDeliveryRequest body) {
        AddressBody a = body.address();
        corrections.correct(id, new DeliveryCorrectionService.Details(body.recipientName(), body.recipientPhone(), a.line1(), a.line2(), a.city(), a.state(),
                a.postalCode(), a.country()), body.reason(), Actor.of(jwt));
        return orders.findById(id).map(views::staff).orElseThrow(() -> new ResourceNotFoundException("Order", "id", id.toString()));
    }
}
