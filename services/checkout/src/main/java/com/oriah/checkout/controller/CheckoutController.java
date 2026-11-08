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
    @ApiResponse(responseCode = "422", description = "The address is outside Zambia (outside-delivery-area): we deliver within Zambia only",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(@Valid @RequestBody PreviewRequest body) {
        return ResponseEntity.ok(service.preview(body));
    }

    /**
     * A 200 from this endpoint means the order is placed and paid, and nothing
     * else.
     *
     * <p>Send {@code Idempotency-Key}: it is forwarded to payment-service,
     * which replays a stored response for a key it has seen rather than
     * charging again. Reuse the same value for every retry of one submission
     * and mint a new one only for a new submission. Without it, a retry after
     * a 502 is a second charge. Every failure carries a status, chosen so that the retryable ones
     * are retryable: 503 and 502 are, 402, 409 and 422 deliberately are not,
     * because re-sending a declined card or an empty cart cannot succeed.
     */
    @Operation(operationId = "confirm", summary = "Run the checkout saga: reserve, pay, place the order")
    @ApiResponse(responseCode = "200", description = "The order was placed and paid")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "402", description = "The card was declined. Final: the detail carries the processor's reason, and retrying the same card will fail again",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The cart is empty, so there is nothing to check out",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "The promotion code cannot be used (promotion-code-invalid), or the address is outside Zambia (outside-delivery-area)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "The payment could not be confirmed, so whether the card was charged is unknown. The order stays unplaced and the cart intact; confirmation is idempotent, so a retry is safe",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "A dependency is temporarily unavailable and nothing has been charged. Carries Retry-After",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error. One case matters to the client: type payment-captured-without-order means the card WAS charged and the order was not completed. Do not retry — the body carries paymentIntentId and orderId for support to reconcile",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/confirm")
    public ResponseEntity<ConfirmResponse> confirm(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConfirmRequest body) {
        return ResponseEntity.ok(service.confirm(body, idempotencyKey));
    }
}
