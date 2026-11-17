package com.oriah.payment.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ProblemDetail;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import com.oriah.payment.confirmation.Staff;
import com.oriah.payment.dto.PayoutRequest;
import com.oriah.payment.dto.VoidPayoutRequest;
import com.oriah.payment.refund.PayoutService;
import com.oriah.payment.refund.PayoutView;

/**
 * Money paid back to customers (Phase 12), recorded by the owner: PAYMENT_ADMIN, here and at the gateway. A payout pays one refund that is owed, or one record of
 * held money; the amount is that debt's own and is never sent. Whether an old date may be recorded depends on a value in the body, so it is the service's check
 * (BACKDATE_SALE, a reason), not a path at the gateway.
 */
@RestController
@RequestMapping("/api/v1/admin/payments/payouts")
@Tag(name = "Payouts (admin)", description = "Refunds paid and held money returned; PAYMENT_ADMIN")
@PreAuthorize("hasRole('PAYMENT_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking PAYMENT_ADMIN (or, for a date more than 72 hours back, BACKDATE_SALE)",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class PayoutController {

    private final PayoutService payouts;

    public PayoutController(PayoutService payouts) {
        this.payouts = payouts;
    }

    @Operation(operationId = "recordPayout", summary = "Record money paid back to a customer",
            description = "Name the refund owed (refundDueId) or the held money (heldMoneyId), how it was sent, the provider's reference (cash needs none), what the "
                    + "provider charged Oriah (sendCharge) and how much of that the customer bore (customerFee, never more than the charge; 0.00 when Oriah bears "
                    + "it). The amount paid back is the debt's own: `gross` and `net` are never accepted. `Idempotency-Key` is required: the same key and request "
                    + "answers the same payout (200).")
    @ApiResponse(responseCode = "201", description = "Recorded; announced as payment.paid_out")
    @ApiResponse(responseCode = "200", description = "A repeat of an earlier request with the same Idempotency-Key: the same payout")
    @ApiResponse(responseCode = "400", description = "amount-is-not-typed, invalid-send-charge, cash-has-no-charge, invalid-customer-fee, paid-before-it-was-owed; or "
            + "no method or reference, both or neither debt named, an old date with no reason, or no Idempotency-Key", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "refund-due-not-found, held-money-not-found", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "idempotency-key-reused, already-paid-out, money-check-needed, refund-closed, held-money-voided, "
            + "void-and-key-again, oriah-bears-this-charge, payout-reference-already-used", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<PayoutView> pay(@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody PayoutRequest request,
                                          Authentication authentication, @AuthenticationPrincipal Jwt jwt) {
        PayoutService.Paid result = payouts.pay(request, idempotencyKey, Staff.of(jwt, ConfirmationController.mayBackdate(authentication)));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.view());
    }

    @Operation(operationId = "voidPayout", summary = "Cancel a payout recorded wrongly",
            description = "Once, with a reason of 10 to 200 characters. The debt it paid is owed again, its reference is free, and it is announced as "
                    + "payment.payout_voided. Then record the payout afresh.")
    @ApiResponse(responseCode = "200", description = "The payout, cancelled")
    @ApiResponse(responseCode = "400", description = "No reason, or one that is not 10 to 200 characters", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "payout-not-found", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "payout-already-voided", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/void")
    public PayoutView voidPayout(@PathVariable("id") UUID id, @Valid @RequestBody VoidPayoutRequest request, @AuthenticationPrincipal Jwt jwt) {
        return payouts.voidPayout(id, request.reason(), Staff.of(jwt, false));
    }

    @Operation(operationId = "listPayouts", summary = "Money paid back, newest first (at most 200)",
            description = "`purpose` is REFUND or HELD_MONEY (both when absent); `since` keeps the payouts recorded from that moment on.")
    @ApiResponse(responseCode = "200", description = "The payouts, newest first")
    @ApiResponse(responseCode = "400", description = "A purpose that does not exist", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public List<PayoutView> list(@RequestParam(name = "purpose", required = false) String purpose, @RequestParam(name = "since", required = false) Instant since) {
        return payouts.list(purpose, since);
    }

    @Operation(operationId = "getPayout", summary = "One payout, and whether it was cancelled")
    @ApiResponse(responseCode = "200", description = "The payout")
    @ApiResponse(responseCode = "404", description = "payout-not-found", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public PayoutView get(@PathVariable("id") UUID id) {
        return payouts.get(id);
    }
}
