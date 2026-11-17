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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import com.oriah.payment.dto.StartProviderPaymentRequest;
import com.oriah.payment.provider.PaymentProvider.CardPayer;
import com.oriah.payment.provider.ProviderPaymentService;
import com.oriah.payment.provider.ProviderPaymentView;
import com.oriah.payment.provider.zynlepay.ZynlePaySetup;

/**
 * A shopper pays one of their own orders through ZynlePay: by mobile money (a prompt on their phone) or by card (on ZynlePay's own page).
 * Starting a payment is not money: the order is paid when ZynlePay, asked by Oriah, says the money was collected.
 */
@RestController
@RequestMapping("/api/v1/payments/provider-payments")
@PreAuthorize("hasRole('USER')")
@Tag(name = "Paying through ZynlePay (shopper)", description = "Mobile money and cards, for the shopper's own orders; USER")
public class ProviderPaymentController {

    private static final String PROBLEMS = "application/problem+json";

    private final ProviderPaymentService payments;
    private final ZynlePaySetup setup;

    public ProviderPaymentController(ProviderPaymentService payments, ZynlePaySetup setup) {
        this.payments = payments;
        this.setup = setup;
    }

    @Operation(operationId = "startProviderPayment", summary = "Start paying an order by mobile money or by card",
            description = "The amount is the order's own total: send none. For mobile money give the wallet's number; the customer then approves a "
                    + "prompt on that phone (`nextStep: APPROVE_ON_PHONE`). For a card, send the customer to `redirectUrl` (`nextStep: OPEN_CARD_PAGE`): "
                    + "the card is typed on ZynlePay's own page, never here. Then read the payment again every `checkAgainInSeconds` until it is "
                    + "VERIFIED (the order will show as paid) or FAILED (a new payment may be started). `Idempotency-Key` is required: the same key "
                    + "and request answers the same payment (200) and nothing is asked twice.")
    @ApiResponse(responseCode = "201", description = "The payment as it stands after ZynlePay was asked: PENDING, or FAILED with its `failureCode`")
    @ApiResponse(responseCode = "200", description = "The same key and request again: the payment it already made")
    @ApiResponse(responseCode = "400", description = "`amount-is-not-typed`, `card-details-not-accepted`, `phone-required`, `phone-not-for-card`, "
            + "`invalid-phone`, `not-a-mobile-money-number`, or no Idempotency-Key",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "`order-not-found`: no such order of the caller's",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "`provider-payment-in-progress`, `money-already-recorded`, `idempotency-key-reused`, "
            + "`channel-not-offered`, or the order's own refusal under its own name (`order-not-awaiting-payment`, `payment-already-submitted`, "
            + "`order-not-held`, `hold-expired`, `hold-too-short`, `payment-being-applied`)",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "`too-many-payment-attempts`, with `Retry-After`",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "`order-unavailable` (nothing was sent: repeat with the same key) or `payments-unavailable`, "
            + "with `Retry-After`", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<ProviderPaymentView> start(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                     @Valid @RequestBody StartProviderPaymentRequest request, @AuthenticationPrincipal Jwt jwt) {
        ProviderPaymentService.Started started = payments.start(jwt.getSubject(), payer(jwt), request, idempotencyKey);
        return ResponseEntity.status(started.created() ? HttpStatus.CREATED : HttpStatus.OK).body(started.view());
    }

    @Operation(operationId = "getProviderPayment", summary = "One of the caller's own payments through ZynlePay")
    @ApiResponse(responseCode = "200", description = "The payment as it stands")
    @ApiResponse(responseCode = "404", description = "`provider-payment-not-found`: no such payment of the caller's",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public ProviderPaymentView get(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        return payments.get(jwt.getSubject(), id);
    }

    @Operation(operationId = "listProviderPayments", summary = "The caller's own payments through ZynlePay for one order, newest first")
    @ApiResponse(responseCode = "200", description = "At most twenty; empty when there are none, or the order is not the caller's")
    @ApiResponse(responseCode = "400", description = "`orderId` is missing or is not an id",
            content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public List<ProviderPaymentView> mine(@RequestParam("orderId") UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        return payments.mine(jwt.getSubject(), orderId);
    }

    /** Who is paying, as ZynlePay's card page is told: the name and email on the shopper's own account, and the shop's town for an address. */
    private CardPayer payer(Jwt jwt) {
        String first = jwt.getClaimAsString("given_name");
        String last = jwt.getClaimAsString("family_name");
        String name = jwt.getClaimAsString("name");
        if ((first == null || first.isBlank()) && name != null && !name.isBlank()) {
            String[] parts = name.strip().split("\\s+", 2);
            first = parts[0];
            last = parts.length > 1 ? parts[1] : parts[0];
        }
        return new CardPayer(first == null || first.isBlank() ? "Customer" : first.strip(), last == null || last.isBlank() ? "Oriah" : last.strip(),
                jwt.getClaimAsString("email"), null, setup.settings().card().payerAddress(), setup.settings().card().payerCity());
    }
}
