package com.oriah.payment.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.payment.dto.AdminPaymentResponse;
import com.oriah.payment.dto.AdminPaymentSummary;
import com.oriah.payment.model.PaymentStatus;
import com.oriah.payment.repository.PaymentIntentRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Admin-side payment reads. The first and only user of {@code PAYMENT_ADMIN},
 * a role the Keycloak realm has always declared and which
 * {@code scripts/validate_roles.py} has been reporting as declared-but-unused
 * — because the endpoint it was minted for did not exist until now.
 *
 * <h2>Paging rejects; it does not clamp or ignore</h2>
 *
 * <p>The parameters are bound as explicit {@code @RequestParam}s rather than a
 * {@link Pageable}, which is unusual in this codebase and deliberate. Spring's
 * {@code PageableHandlerMethodArgumentResolver} <em>silently caps</em> an
 * oversized {@code size}: ask for 1000, receive 100, and believe you have
 * everything. On a list an operator uses to reconcile money, a page that
 * quietly isn't the page you asked for is worse than an error.
 *
 * <p>{@code sort} is rejected for the same reason. This endpoint has exactly
 * one order — newest first — because that is the order the supporting index is
 * built for. Accepting and ignoring a {@code sort} parameter would teach
 * callers that sorting works.
 *
 * <p>Read-only on purpose. Refunds and other payment writes are a separate
 * concern with their own invariants; this is the list the admin UI needs.
 */
@RestController
@RequestMapping("/api/v1/admin/payments")
@Tag(name = "Payments (admin)", description = "Payment intent reads, PAYMENT_ADMIN only")
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
@PreAuthorize("hasRole('PAYMENT_ADMIN')")
public class AdminPaymentController {

    /** Matches the supporting partial index; see V4__index_payment_intents_admin_list.sql. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final PaymentIntentRepository repository;

    public AdminPaymentController(PaymentIntentRepository repository) {
        this.repository = repository;
    }

    @Operation(operationId = "listPayments",
            summary = "All payment intents, newest first, optionally filtered by status")
    @ApiResponse(responseCode = "200", description = "A page of payment intents")
    @ApiResponse(responseCode = "400",
            description = "Unknown status, size above 100, negative page, or a sort parameter "
                    + "(this endpoint is fixed to newest-first)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public ResponseEntity<Page<AdminPaymentResponse>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "sort", required = false) String sort) {

        // IllegalArgumentException is what the shared advice renders as a 400
        // RFC 7807 body, so every rejection below arrives in the platform's
        // standard error shape without this controller knowing about it.
        if (sort != null) {
            throw new IllegalArgumentException(
                    "This endpoint is always ordered newest first and does not accept a "
                            + "'sort' parameter. Remove it.");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater, was " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between 1 and " + MAX_PAGE_SIZE + ", was " + size);
        }

        Pageable pageable = PageRequest.of(page, size, NEWEST_FIRST);

        Page<AdminPaymentResponse> result = (status == null || status.isBlank())
                ? repository.findByDeletedFalse(pageable).map(AdminPaymentResponse::from)
                : repository.findByDeletedFalseAndStatus(parseStatus(status), pageable)
                        .map(AdminPaymentResponse::from);

        return ResponseEntity.ok(result);
    }

    @Operation(operationId = "paymentSummary",
            summary = "Exact ledger totals: counts by status and captured money per currency")
    @ApiResponse(responseCode = "200", description = "The summary")
    @GetMapping("/summary")
    public ResponseEntity<AdminPaymentSummary> summary() {
        // Every figure is computed by the database. The alternative -- letting
        // the dashboard add up a page of payments -- gives a revenue number
        // that is right until there is more than one page and then quietly
        // wrong, which on a money figure is worse than no number at all.
        return ResponseEntity.ok(new AdminPaymentSummary(
                repository.countByDeletedFalse(),
                repository.countByDeletedFalseAndStatus(PaymentStatus.SUCCEEDED),
                repository.countByDeletedFalseAndStatus(PaymentStatus.FAILED),
                repository.countByDeletedFalseAndStatus(PaymentStatus.PENDING),
                repository.countByDeletedFalseAndStatus(PaymentStatus.CHARGING),
                repository.sumAmountByCurrency(PaymentStatus.SUCCEEDED)));
    }

    /**
     * An unknown status is a 400, not an empty page.
     *
     * <p>Returning nothing would read as "there are no payments like that" when
     * the truth is "I did not understand the question" — and an operator
     * chasing a missing payment would believe the first.
     */
    private static PaymentStatus parseStatus(String raw) {
        try {
            return PaymentStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown payment status '" + raw + "'. Valid values: "
                            + java.util.Arrays.toString(PaymentStatus.values()));
        }
    }
}
