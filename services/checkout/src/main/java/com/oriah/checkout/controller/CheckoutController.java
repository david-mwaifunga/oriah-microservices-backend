package com.oriah.checkout.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.checkout.dto.ConfirmRequest;
import com.oriah.checkout.dto.ConfirmResponse;
import com.oriah.checkout.dto.PreviewRequest;
import com.oriah.checkout.dto.PreviewResponse;
import com.oriah.checkout.service.CheckoutService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/checkout")
@Tag(name = "Checkout", description = "Checkout preview and confirmation")
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
@PreAuthorize("hasRole('USER')")
public class CheckoutController {

    private final CheckoutService service;

    public CheckoutController(CheckoutService service) {
        this.service = service;
    }

    @Operation(operationId = "preview", summary = "Price the caller's cart without committing anything")
    @ApiResponse(responseCode = "200", description = "The priced preview")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A line cannot be sold (bag-not-purchasable), the bag mixes currencies (mixed-currency), or shipping does not offer the chosen delivery: town-not-offered (the shop has not priced that town yet), payer-not-offered, town-not-found",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "catalog, customer or shipping is temporarily unavailable, so no quote can be given. Carries Retry-After",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(@Valid @RequestBody PreviewRequest body) {
        return ResponseEntity.ok(service.preview(body));
    }

    /**
     * A 200 from this endpoint means the order is PLACED and its pieces are HELD for the shopper, and nothing else: it is not paid. The shopper pays by
     * mobile money or bank transfer and gives order the reference ({@code POST /api/v1/orders/{id}/payment-claim}); staff then confirm the money.
     *
     * <p>Send {@code Idempotency-Key}: it is forwarded to order, which places the same order for a key it has seen rather than a second one. Reuse the
     * same value for every retry of one submission and mint a new one only for a new submission. Every failure carries a status, chosen so that the
     * retryable ones are retryable: 503 is (with the SAME key; the order may already exist if a reply was lost); 409 and 400 deliberately are not, because re-sending an empty cart or a stale quote cannot
     * succeed. A retry after a lost reply that finds the cart already empty is 409 {@code cart-empty}: the cart is cleared the moment the order is
     * placed, so the shopper should look at their orders before concluding that nothing happened.
     */
    @Operation(operationId = "confirm", summary = "Place the order and hold its pieces for the shopper")
    @ApiResponse(responseCode = "200", description = "The order was placed and its pieces are held until holdExpiresAt; it is not paid")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The cart is empty (cart-empty); the bag, a price, a promotion, the delivery or its price changed since preview (pricing-changed) -- preview again, show the shopper the new quote, and confirm with its quoteDigest; a piece or its box is gone (insufficient-stock, naming every short line); the shopper already holds too many orders (hold-limit-reached); or shipping does not offer the delivery (town-not-offered, payer-not-offered). Nothing was ordered",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "A dependency is temporarily unavailable. Carries Retry-After. The order may or may not have been placed (a reply can be lost): retry with the SAME Idempotency-Key and you get that order back, never a second",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error. Do not retry blindly: if it persists, the shopper should look at their orders",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/confirm")
    public ResponseEntity<ConfirmResponse> confirm(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConfirmRequest body) {
        return ResponseEntity.ok(service.confirm(body, idempotencyKey));
    }
}
