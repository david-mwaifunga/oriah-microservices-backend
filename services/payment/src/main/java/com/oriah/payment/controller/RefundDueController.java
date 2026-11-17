package com.oriah.payment.controller;

import java.util.List;
import java.util.UUID;

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
import com.oriah.payment.dto.MoneyCheckRequest;
import com.oriah.payment.refund.RefundDueView;
import com.oriah.payment.refund.RefundDues;

/**
 * Refunds owed to customers (Phase 12), for the owner: PAYMENT_ADMIN, which a manager does not hold, here and at the gateway. A debt is made by the order system
 * when a paid sale is corrected, never typed here; this is where it is read, and where the owner says whether money in question ever arrived.
 */
@RestController
@RequestMapping("/api/v1/admin/payments/refunds-due")
@Tag(name = "Refunds owed (admin)", description = "What customers are owed for cancelled orders and returns; PAYMENT_ADMIN")
@PreAuthorize("hasRole('PAYMENT_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking PAYMENT_ADMIN (or, for a date more than 72 hours back, BACKDATE_SALE)",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class RefundDueController {

    private final RefundDues dues;

    public RefundDueController(RefundDues dues) {
        this.dues = dues;
    }

    @Operation(operationId = "listRefundsDue", summary = "Refunds owed to customers, the oldest first",
            description = "`status` is OWED (the default), IN_QUESTION (the payment record of the sale was cancelled after it paid: say whether the money arrived), "
                    + "PAID, CLOSED or ALL. Each says how the money first came in, so it can be sent back the same way.")
    @ApiResponse(responseCode = "200", description = "The refunds, oldest first")
    @ApiResponse(responseCode = "400", description = "A status that does not exist", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public List<RefundDueView> list(@RequestParam(name = "status", defaultValue = "OWED") String status) {
        return dues.list(status);
    }

    @Operation(operationId = "getRefundDue", summary = "One refund owed, with its payout once it is paid")
    @ApiResponse(responseCode = "200", description = "The refund")
    @ApiResponse(responseCode = "404", description = "refund-due-not-found", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public RefundDueView get(@PathVariable("id") UUID id) {
        return dues.get(id);
    }

    @Operation(operationId = "checkRefundMoney", summary = "Say whether the money of a refund in question ever arrived",
            description = "For a refund whose sale was paid by a payment record that was cancelled afterwards. Said once, with what was looked at (10 to 200 "
                    + "characters). Arrived: the refund is owed and can be paid. Never arrived: it is closed and announced as payment.refund_closed.")
    @ApiResponse(responseCode = "200", description = "The refund, owed or closed")
    @ApiResponse(responseCode = "400", description = "No answer, or a reason that is not 10 to 200 characters", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "refund-due-not-found", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "already-checked, or not-in-question", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/money-check")
    public RefundDueView moneyCheck(@PathVariable("id") UUID id, @Valid @RequestBody MoneyCheckRequest request, @AuthenticationPrincipal Jwt jwt) {
        return dues.moneyCheck(id, request.moneyArrived(), request.reason(), Staff.of(jwt, false));
    }
}
