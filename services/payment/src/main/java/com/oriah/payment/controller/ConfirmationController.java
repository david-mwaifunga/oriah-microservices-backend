package com.oriah.payment.controller;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.payment.confirmation.ConfirmationService;
import com.oriah.payment.confirmation.ConfirmationView;
import com.oriah.payment.confirmation.PendingView;
import com.oriah.payment.confirmation.Staff;
import com.oriah.payment.dto.ConfirmPaymentRequest;
import com.oriah.payment.dto.VoidConfirmationRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Staff confirming money that reached Oriah (Phase 7): the only road by which an order becomes paid. SALES_AGENT, here and at the gateway.
 * Whether money older than 72 hours may be recorded depends on a number in the body, so it is the service's check (BACKDATE_SALE, a reason), not
 * a path at the gateway.
 */
@RestController
@RequestMapping("/api/v1/admin/payments/confirmations")
@Tag(name = "Payment confirmations (staff)", description = "Money received, confirmed by a person; SALES_AGENT")
@PreAuthorize("hasRole('SALES_AGENT')")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking SALES_AGENT (or, for money older than 72 hours, BACKDATE_SALE)",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class ConfirmationController {

    private static final String PROBLEMS = "application/problem+json";

    private final ConfirmationService confirmations;
    private final Clock clock;

    public ConfirmationController(ConfirmationService confirmations, Clock clock) {
        this.confirmations = confirmations;
        this.clock = clock;
    }

    @Operation(operationId = "confirmPayment", summary = "Record money that reached Oriah",
            description = "Method, reference (cash needs none), gross, fee and the date the money ARRIVED. Net is gross less fee and is never "
                    + "accepted. A reference confirms money once. Money older than 72 hours needs BACKDATE_SALE and a backdateReason. "
                    + "`Idempotency-Key` is required: the same key and request answers the same confirmation (200).")
    @ApiResponse(responseCode = "201", description = "Recorded; announced as payment.confirmed")
    @ApiResponse(responseCode = "200", description = "A repeat of an earlier request with the same Idempotency-Key: the same confirmation")
    @ApiResponse(responseCode = "400", description = "Not valid: no method or reference, a bad amount or fee, net sent, an arrival in the future, "
            + "money older than 72 hours with no reason, another currency, or no Idempotency-Key",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "reference-already-used (names the confirmation) or idempotency-key-reused",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<ConfirmationView> confirm(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                    @Valid @RequestBody ConfirmPaymentRequest request,
                                                    Authentication authentication, @AuthenticationPrincipal Jwt jwt) {
        ConfirmationService.Confirmed result = confirmations.confirm(request, idempotencyKey, Staff.of(jwt, mayBackdate(authentication)));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.view());
    }

    @Operation(operationId = "listPendingConfirmations", summary = "Confirmations order has not answered yet, oldest first",
            description = "Money recorded that nothing has acted on yet. `stuck` is true once one has waited longer than the settlement window "
                    + "(five minutes): someone should look. Settled, held and voided confirmations are not listed.")
    @GetMapping("/pending")
    public List<PendingView> pending() {
        return confirmations.pending(clock.instant());
    }

    @Operation(operationId = "voidConfirmation", summary = "Take back a confirmation keyed wrongly",
            description = "Needs a reason of at least ten characters. Frees the reference so the corrected confirmation can use it, closes the "
                    + "money held for it and announces payment.voided. A confirmation that paid a sale is never voided.")
    @ApiResponse(responseCode = "400", description = "No reason, or one under ten characters",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "confirmation-not-found",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "already-voided, or confirmation-settled (it paid a sale: correcting it is a later phase's)",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/void")
    public ConfirmationView voidConfirmation(@PathVariable("id") UUID id, @Valid @RequestBody VoidConfirmationRequest request, @AuthenticationPrincipal Jwt jwt) {
        return confirmations.voidConfirmation(id, request.reason(), Staff.of(jwt, false));
    }

    @Operation(operationId = "getConfirmation", summary = "One confirmation, with what became of the money")
    @ApiResponse(responseCode = "404", description = "confirmation-not-found",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public ConfirmationView get(@PathVariable("id") UUID id) {
        return confirmations.get(id);
    }

    /** Whether the caller holds BACKDATE_SALE: read from the authorities of the signed-in person, on the request thread. */
    static boolean mayBackdate(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_BACKDATE_SALE".equals(a.getAuthority()));
    }
}
