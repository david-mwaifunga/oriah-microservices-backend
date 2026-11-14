package com.oriah.payment.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.payment.claim.ClaimService;
import com.oriah.payment.claim.ClaimView;
import com.oriah.payment.claim.RejectionView;
import com.oriah.payment.confirmation.Staff;
import com.oriah.payment.dto.RejectClaimRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * What shoppers claim to have paid with, and rejecting a claim that is false (Phase 7). SALES_AGENT, here and at the gateway: anyone who may
 * confirm may reject. A claim is rejected by its id; the order, method and reference are copied from it.
 */
@RestController
@RequestMapping("/api/v1/admin/payments/claims")
@Tag(name = "Payment claims (staff)", description = "What a shopper said they paid with; reject a false claim; SALES_AGENT")
@PreAuthorize("hasRole('SALES_AGENT')")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking SALES_AGENT",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class ClaimController {

    private static final String PROBLEMS = "application/problem+json";

    private final ClaimService claims;

    public ClaimController(ClaimService claims) {
        this.claims = claims;
    }

    @Operation(operationId = "listPaymentClaims", summary = "Claims, newest first; optionally of one order",
            description = "Each says whether money is already recorded for it (`moneyRecorded`) and whether its reference is used for another order (`usedElsewhere`).")
    @ApiResponse(responseCode = "200", description = "The claims, newest first")
    @GetMapping
    public List<ClaimView> list(@RequestParam(name = "orderId", required = false) UUID orderId) {
        return claims.list(orderId);
    }

    @Operation(operationId = "getPaymentClaim", summary = "One claim")
    @ApiResponse(responseCode = "404", description = "claim-not-found", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "The claim")
    @GetMapping("/{claimId}")
    public ClaimView get(@PathVariable("claimId") UUID claimId) {
        return claims.get(claimId);
    }

    @Operation(operationId = "rejectPaymentClaim", summary = "Reject a claim as false",
            description = "`NOT_FOUND_AT_PROVIDER` needs `evidence` (ten characters or more). `REFERENCE_ALREADY_USED` is checked by the service: the same "
                    + "reference must already be confirmed for another order (held money counts, a voided confirmation does not). A claim whose "
                    + "reference already has money recorded for its own order cannot be rejected. Posts nothing and does not use the reference.")
    @ApiResponse(responseCode = "201", description = "Rejected; announced as payment.rejected")
    @ApiResponse(responseCode = "400", description = "No or unknown proof type, or evidence missing or too short",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "claim-not-found", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "already-rejected, money-recorded-for-claim or reference-not-used-elsewhere",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{claimId}/reject")
    public ResponseEntity<RejectionView> reject(@PathVariable("claimId") UUID claimId, @Valid @RequestBody RejectClaimRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(claims.reject(claimId, request.proofType(), request.evidence(), Staff.of(jwt, false)));
    }
}
