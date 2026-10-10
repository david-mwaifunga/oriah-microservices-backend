package com.oriah.promotion.controller;

import java.math.BigDecimal;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.promotion.dto.ApplyRequest;
import com.oriah.promotion.dto.ApplyResponse;
import com.oriah.promotion.dto.PromotionResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.promotion.service.PromotionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/promotions")
@Tag(name = "Promotions", description = "Discount code lookup and redemption")
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
public class PromotionController {

    private final PromotionService service;

    public PromotionController(PromotionService service) {
        this.service = service;
    }

    /** Look up a code and return its config (validation hint to UI). */
    @Operation(operationId = "get", summary = "Look up a discount code")
    @ApiResponse(responseCode = "200", description = "The code's configuration")
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{code}")
    public PromotionResponse get(@PathVariable("code") String code) {
        return service.findByCode(code)
                .map(PromotionResponse::from)
                .orElseThrow(() -> unknownCode(code));
    }

    /**
     * Validate + compute discount against a subtotal. Returns 404 when
     * the code is unknown/expired/disabled/exhausted — single 404 path
     * avoids leaking which guard failed (UI just shows "code invalid").
     */
    @Operation(operationId = "apply", summary = "Compute the discount for a subtotal")
    @ApiResponse(responseCode = "200", description = "The computed discount")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{code}/apply")
    public ApplyResponse apply(
            @PathVariable("code") String code,
            @Valid @RequestBody ApplyRequest body) {
        return service.applyToSubtotal(code, body.subtotal())
                .orElseThrow(() -> unknownCode(code));
    }

    /**
     * Bump currentRedemptions by 1. Called by checkout after the order
     * is successfully placed. Idempotency is the caller's responsibility
     * — calling this twice double-counts. (Outbox/saga pattern handles
     * idempotency in a real production setup.)
     */
    @Operation(operationId = "redeem", summary = "Increment the code's redemption count")
    @ApiResponse(responseCode = "200", description = "The code, with its new redemption count")
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{code}/redeem")
    public PromotionResponse redeem(@PathVariable("code") String code) {
        return service.redeem(code)
                .map(PromotionResponse::from)
                .orElseThrow(() -> unknownCode(code));
    }

    /**
     * One 404 for every reason a code is unusable -- unknown, expired,
     * disabled or exhausted.
     *
     * <p><strong>The vagueness is the feature.</strong> Telling a caller that a
     * code exists but has hit its redemption cap confirms the code is real, and
     * that is exactly how you enumerate valid discount codes. So all four
     * guards collapse into the same answer, and the shared
     * {@code ResourceNotFoundException} phrasing says the same thing in every
     * case.
     *
     * <p>That made Tier 5 genuinely risky here: adding a <em>helpful</em>
     * detail would have been the regression, not an improvement. What the 404s
     * gained instead is a problem {@code type} and a {@code traceId} -- useful
     * to an operator reading logs, useless to someone probing from outside.
     * {@code PromotionIntegrationTest} now asserts the exhausted-code body is
     * byte-identical to the never-existed body, so a later friendlier message
     * fails the build.
     */
    private static ResourceNotFoundException unknownCode(String code) {
        return new ResourceNotFoundException("Promotion", "code", code);
    }
}
