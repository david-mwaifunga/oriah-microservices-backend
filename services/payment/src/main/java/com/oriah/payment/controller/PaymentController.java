package com.oriah.payment.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.payment.dto.ConfirmIntentRequest;
import com.oriah.payment.dto.CreateIntentRequest;
import com.oriah.payment.dto.PaymentIntentResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.service.PaymentService;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment intents and confirmation")
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
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * A payment intent for one of the caller's own orders, for its total.
     *
     * <p>Every refusal is declared here because nothing else would publish it:
     * the service refuses with exceptions that the shared error advice turns
     * into 400, 404, 409 and 503 out of springdoc's sight. The 200 is declared
     * because checkout's retry of a create whose answer was lost receives it,
     * and a client that knows only 201 would treat it as unexpected.
     */
    @Operation(operationId = "createIntent", summary = "Create a payment intent for one of the caller's orders")
    @ApiResponse(responseCode = "200",
            description = "Nothing new was created: the caller already has a PENDING intent for this order, for "
                    + "the same amount and currency -- that intent, unchanged. This is what a repeat of a create "
                    + "whose answer was lost receives.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = PaymentIntentResponse.class)))
    @ApiResponse(responseCode = "201", description = "Created; Location points at the intent")
    @ApiResponse(responseCode = "400",
            description = "Validation failure or unparseable parameter (see fieldErrors), or the amount or "
                    + "currency is not the order's total; nothing was created",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404",
            description = "No order of yours has this id -- it belongs to someone else or does not exist, and the "
                    + "answer is the same either way; nothing was created",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409",
            description = "Not created. The order is no longer waiting for payment (cancelled or already paid), "
                    + "or it already has a payment intent that is pending, being charged or paid -- one order is "
                    + "paid through one intent. A pending intent for a different amount or currency is not handed "
                    + "back. A new one can be made only if that one fails.",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503",
            description = "Order could not be asked whether this order is yours (it is down, slow or failing); "
                    + "nothing was created or charged -- retry after Retry-After",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/intents")
    public ResponseEntity<PaymentIntentResponse> createIntent(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateIntentRequest body) {
        PaymentService.CreatedIntent result = service.createIntent(jwt.getSubject(), body);
        PaymentIntent intent = result.intent();
        if (!result.created()) {
            // The same create again -- typically checkout's retry after the
            // first answer was lost: the intent already made, nothing new.
            return ResponseEntity.ok(PaymentIntentResponse.from(intent));
        }
        return ResponseEntity.created(URI.create("/api/v1/payments/intents/" + intent.id()))
                .body(PaymentIntentResponse.from(intent));
    }

    /**
     * One of the caller's own payment intents.
     *
     * <p>The ownership {@code filter} before the 404 is the sharpest
     * anti-enumeration case in the platform: a payment-intent id confirmed to
     * exist is a far more interesting probe target than an order id. The shared
     * {@code ResourceNotFoundException} phrasing keeps "not yours" and "does
     * not exist" identical, and
     * {@code PaymentIntegrationTest.othersIntentIsIndistinguishableFromMissing}
     * asserts the two bodies match.
     */
    @Operation(operationId = "get", summary = "One of the caller's own payment intents")
    @ApiResponse(responseCode = "200", description = "The intent")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/intents/{id}")
    public PaymentIntentResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id) {
        return service.findById(id)
                .filter(i -> i.keycloakId().equals(jwt.getSubject()))
                .map(PaymentIntentResponse::from)
                .orElseThrow(() -> unknownIntent(id));
    }

    private static ResourceNotFoundException unknownIntent(UUID id) {
        return new ResourceNotFoundException("PaymentIntent", "id", id.toString());
    }

    /**
     * Confirm a PaymentIntent: charge the gateway and record the outcome.
     * Supports the standard {@code Idempotency-Key} header: a repeat with the
     * same key is served the original response verbatim and the gateway is
     * NOT charged again; a repeat while the first is still in progress is
     * refused (409). Same contract as Stripe's for {@code POST} endpoints
     * with side effects.
     *
     * <p>All of it -- the ownership check, the key, the order of the charge
     * and the transactions around it -- lives in
     * {@link PaymentService#confirm}, so that no caller can reorder a check
     * after the irreversible step. The key used to be looked up here, before
     * the service, and stored after it: two requests with one key could both
     * miss it and both charge.
     */
    @Operation(operationId = "confirm", summary = "Charge the gateway and settle the intent")
    @ApiResponse(responseCode = "200", description = "The settled intent; replayed verbatim for a repeated Idempotency-Key")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409",
            description = "Not charged. The intent is not chargeable: it is being charged (its outcome is not "
                    + "known yet) or already settled -- it is never charged twice. Or a confirmation with this "
                    + "Idempotency-Key is still in progress: repeat it once that has finished to receive its "
                    + "result. Or the Idempotency-Key is already bound to a different payment intent.",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503",
            description = "The outcome of the charge is not known yet: the gateway did not answer, or its answer "
                    + "could not be recorded. Not a decline -- the money may have been taken. The intent stays "
                    + "CHARGING and is never charged again; it is settled shortly by asking the gateway, after "
                    + "which a repeat with the same Idempotency-Key returns the result (409 until then).",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/intents/{id}/confirm")
    public ResponseEntity<PaymentIntentResponse> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConfirmIntentRequest body) {
        return service.confirm(id, jwt.getSubject(), body.cardNumber(), idempotencyKey)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> unknownIntent(id));
    }
}
