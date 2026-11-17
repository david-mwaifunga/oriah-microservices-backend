package com.oriah.payment.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ProblemDetail;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.oriah.payment.confirmation.Staff;
import com.oriah.payment.provider.ProviderPaymentAdmin;
import com.oriah.payment.provider.ProviderPaymentAdmin.AdminView;

/**
 * Payments through ZynlePay, for staff. A member of staff (SALES_AGENT) sees every payment and where it stands, and may ask ZynlePay about one
 * now. Only the owner (PAYMENT_ADMIN) settles a payment ZynlePay did not.
 */
@RestController
@RequestMapping("/api/v1/admin/payments/provider-payments")
@PreAuthorize("hasRole('SALES_AGENT')")
@Tag(name = "Payments through ZynlePay (staff)", description = "Where each payment stands; SALES_AGENT. Settling one: PAYMENT_ADMIN")
public class ProviderPaymentAdminController {

    private static final String PROBLEMS = "application/problem+json";

    private final ProviderPaymentAdmin admin;

    public ProviderPaymentAdminController(ProviderPaymentAdmin admin) {
        this.admin = admin;
    }

    @Operation(operationId = "listProviderPaymentsForStaff", summary = "Payments through ZynlePay and where each stands",
            description = "`status` is OPEN (the default: not yet sent, in flight, or waiting for the owner; oldest first), PENDING, UNRESOLVED, FAILED, "
                    + "VERIFIED or ALL (newest first). At most 200. A payment that is PENDING or UNRESOLVED is on its way or with the owner: the "
                    + "customer is not asked to pay again and nothing is confirmed by hand. `payerPhone` is masked except for the owner.")
    @ApiResponse(responseCode = "200", description = "The payments")
    @ApiResponse(responseCode = "400", description = "A status that is not one",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public List<AdminView> list(@RequestParam(name = "status", defaultValue = "OPEN") String status,
                                @RequestParam(name = "orderId", required = false) UUID orderId, Authentication authentication, @AuthenticationPrincipal Jwt jwt) {
        return admin.list(status, orderId, owner(authentication), "user:" + jwt.getSubject());
    }

    @Operation(operationId = "getProviderPaymentForStaff", summary = "One payment through ZynlePay, with every time ZynlePay was asked about it")
    @ApiResponse(responseCode = "200", description = "The payment and its checks")
    @ApiResponse(responseCode = "404", description = "`provider-payment-not-found`",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public AdminView get(@PathVariable("id") UUID id, Authentication authentication, @AuthenticationPrincipal Jwt jwt) {
        return admin.get(id, owner(authentication), "user:" + jwt.getSubject());
    }

    @Operation(operationId = "checkProviderPayment", summary = "Ask ZynlePay about this payment now",
            description = "Asks ZynlePay exactly as the system does by itself and applies its answer the same way: if ZynlePay says it was paid for the "
                    + "amount asked, the money is recorded. It decides nothing on a person's word. `checkedJustNow` means ZynlePay was already being "
                    + "asked, so it was not asked twice.")
    @ApiResponse(responseCode = "200", description = "The payment as it stands after the answer")
    @ApiResponse(responseCode = "404", description = "`provider-payment-not-found`",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "`provider-payment-final`: recorded, never sent, or the owner's to decide",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/check")
    public AdminView check(@PathVariable("id") UUID id, Authentication authentication, @AuthenticationPrincipal Jwt jwt) {
        return admin.check(id, owner(authentication), "user:" + jwt.getSubject());
    }

    @Operation(operationId = "resolveProviderPayment", summary = "The owner settles a payment ZynlePay did not: paid or not paid",
            description = "PAYMENT_ADMIN. `outcome` is PAID or NOT_PAID; `evidence` (10 to 200 characters) says what was looked at in ZynlePay's own "
                    + "records, and is kept with the decision only. ZynlePay is asked once more first: if it says paid the money is recorded as "
                    + "ZynlePay's (`verifiedByCheck`), if it says failed a PAID is refused, and if it has not said failed a NOT_PAID is refused unless "
                    + "`overridePending` is true. `gross` is given only where ZynlePay reported another amount than was asked, and is one of those two "
                    + "figures. `arrivedAt` dates the money (now if absent; more than 72 hours back needs BACKDATE_SALE). `Idempotency-Key` is required.")
    @ApiResponse(responseCode = "200", description = "The payment as decided")
    @ApiResponse(responseCode = "400", description = "`gross-not-on-record`, or an outcome, evidence, date or key that makes no sense",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Not the owner, or a date further back than the caller may record",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "`provider-payment-not-found`",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "`not-resolvable`, `provider-says-failed`, `provider-says-paid`, `provider-says-pending`, "
            + "`same-money-on-record`, `currency-not-supported`, `check-in-progress` or `idempotency-key-reused`",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "`provider-unavailable`: ZynlePay could not be asked, and nothing was decided",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasRole('PAYMENT_ADMIN')")
    public AdminView resolve(@PathVariable("id") UUID id, @RequestHeader("Idempotency-Key") String idempotencyKey,
                             @RequestBody ProviderPaymentAdmin.Decision decision, Authentication authentication, @AuthenticationPrincipal Jwt jwt) {
        return admin.resolve(id, idempotencyKey, decision, Staff.of(jwt, ConfirmationController.mayBackdate(authentication)));
    }

    private static boolean owner(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_PAYMENT_ADMIN".equals(a.getAuthority()));
    }
}
