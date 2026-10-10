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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.payment.dto.ConfirmIntentRequest;
import com.oriah.payment.dto.CreateIntentRequest;
import com.oriah.payment.dto.PaymentIntentResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.payment.model.IdempotencyKey;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.service.PaymentService;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class PaymentController {

    private final PaymentService service;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final ObjectMapper objectMapper;

    public PaymentController(PaymentService service,
                             IdempotencyKeyRepository idempotencyKeys,
                             ObjectMapper objectMapper) {
        this.service = service;
        this.idempotencyKeys = idempotencyKeys;
        this.objectMapper = objectMapper;
    }

    @Operation(operationId = "createIntent", summary = "Create a payment intent for an order")
    @ApiResponse(responseCode = "201", description = "Created; Location points at the intent")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/intents")
    public ResponseEntity<PaymentIntentResponse> createIntent(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateIntentRequest body) {
        PaymentIntent created = service.createIntent(jwt.getSubject(), body);
        return ResponseEntity.created(URI.create("/api/v1/payments/intents/" + created.id()))
                .body(PaymentIntentResponse.from(created));
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
     * Confirm a PaymentIntent (charge the gateway, transition to
     * SUCCEEDED/FAILED). Supports the standard {@code Idempotency-Key}
     * header: if the same key has been seen before, the original
     * response is replayed verbatim and the gateway is NOT re-charged.
     *
     * <p>This is the same contract Stripe uses for {@code POST}
     * endpoints with side effects. The client (the checkout
     * orchestrator) generates a fresh UUID per logical retry and
     * resends the header on every attempt; if our previous attempt
     * already landed, the duplicate is a cheap database read instead
     * of a double-charge.
     */
    @Operation(operationId = "confirm", summary = "Charge the gateway and settle the intent")
    @ApiResponse(responseCode = "200", description = "The settled intent; replayed verbatim for a repeated Idempotency-Key")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/intents/{id}/confirm")
    public ResponseEntity<PaymentIntentResponse> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConfirmIntentRequest body) {

        // Cache hit: replay the stored response verbatim.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var hit = idempotencyKeys.findById(idempotencyKey);
            if (hit.isPresent()) {
                log.info("Idempotency-Key={} hit, replaying intent={} (no gateway charge)",
                        idempotencyKey, hit.get().intentId());
                try {
                    PaymentIntentResponse cached = objectMapper.readValue(
                            hit.get().responseJson(), PaymentIntentResponse.class);
                    return ResponseEntity.ok(cached);
                } catch (JsonProcessingException e) {
                    // Cached row is corrupted -- fall through to a fresh call
                    // rather than 500. Logged loudly because this should not happen.
                    log.error("Idempotency-Key={} response_json could not be parsed, "
                                    + "falling through to a fresh confirm call", idempotencyKey, e);
                }
            }
        }

        return service.confirm(id, body.cardNumber())
                .filter(i -> i.keycloakId().equals(jwt.getSubject()))
                .map(intent -> {
                    PaymentIntentResponse resp = PaymentIntentResponse.from(intent);
                    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                        try {
                            idempotencyKeys.save(new IdempotencyKey(
                                    idempotencyKey, intent.id(),
                                    objectMapper.writeValueAsString(resp)));
                            log.info("Idempotency-Key={} stored for intent={}",
                                    idempotencyKey, intent.id());
                        } catch (JsonProcessingException e) {
                            // Should not happen for our simple record DTO; if it
                            // does, the original call still succeeds -- we just
                            // can't replay.
                            log.warn("Idempotency-Key={} could not be persisted "
                                            + "(replay disabled for this key)", idempotencyKey, e);
                        }
                    }
                    return ResponseEntity.ok(resp);
                })
                .orElseThrow(() -> unknownIntent(id));
    }
}
