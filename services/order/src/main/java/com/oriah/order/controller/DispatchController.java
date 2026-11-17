package com.oriah.order.controller;

import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oriah.money.NgweeAmount;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.service.DispatchReadService;
import com.oriah.order.service.DispatchService;
import com.oriah.order.service.DispatchService.Caller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Packing, sending and handing over a paid order (Phase 8). FULFILMENT_AGENT only -- a packer or driver (the DISPATCH job role), and the manager and the
 * owner, who hold every capability -- here and at the gateway. Whether a time beyond 72 hours back may be keyed depends on a number in the body, so it is the
 * service's check (BACKDATE_SALE, a reason), not a path at the gateway.
 *
 * <p>The packer never sees money: nothing here reads or answers the delivery charge, the total or a discount, and the courier fare comes back only to a
 * caller who may see cost (COST_VIEWER).
 */
@RestController
@RequestMapping("/api/v1/admin/orders/{id}/dispatch")
@Tag(name = "Dispatch (staff)", description = "Pack, send and hand over a paid order, recording the courier fare when Oriah pays it; FULFILMENT_AGENT")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking FULFILMENT_AGENT (or, for a time beyond the 72-hour window, BACKDATE_SALE)",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('FULFILMENT_AGENT')")
public class DispatchController {

    /**
     * @param occurredAt     when it happened; absent means now. Never in the future (five minutes of allowance), never before the step it follows
     * @param backdateReason why it is keyed beyond 72 hours back (5 to 200 characters); needs BACKDATE_SALE
     */
    public record StepRequest(
            @Schema(description = "When it happened (ISO-8601, UTC); absent means now") Instant occurredAt,
            // 5 to 200 CHARACTERS is judged by DispatchDates, which counts as the database does; this bound is only a guard on the size of the body
            // (an emoji is one character and two of the units @Size counts).
            @Size(max = 400, message = "the reason must be at most 200 characters") String backdateReason) {
        public StepRequest {
            backdateReason = backdateReason == null ? null : backdateReason.trim();
        }
    }

    /**
     * @param reference the courier's waybill, booking or bus-receipt number: letters, digits, space and ./#- only, 1 to 40; the shopper is shown it
     * @param farePaid  what the courier charged Oriah, in Kwacha: required when Oriah pays the courier (K0.00 if it charged nothing), refused otherwise
     */
    public record ShipRequest(
            @Pattern(regexp = "^[A-Za-z0-9 ./#-]{1,40}$", message = "may hold letters, digits, space and . / # - only, 1 to 40 characters") String reference,
            @DecimalMin(value = "0.00", message = "the fare cannot be negative") @NgweeAmount BigDecimal farePaid,
            Instant occurredAt,
            @Size(max = 400, message = "the reason must be at most 200 characters") String backdateReason) {
        public ShipRequest {
            reference = reference == null || reference.isBlank() ? null : reference.trim();
            backdateReason = backdateReason == null ? null : backdateReason.trim();
        }
    }

    /** Where the order stands after a step and when each step happened; {@code farePaid} only for a caller who may see cost. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SummaryResponse(UUID orderId, OrderStatus status, String stage, Instant packedAt, Instant dispatchedAt, Instant deliveredAt, String reference,
                                  BigDecimal farePaid, boolean replay) {

        static SummaryResponse of(DispatchService.Summary s) {
            return new SummaryResponse(s.orderId(), s.status(), s.stage(), s.packedAt(), s.dispatchedAt(), s.deliveredAt(), s.reference(), s.farePaid(), s.replay());
        }
    }

    private final DispatchService dispatch;
    private final DispatchReadService reads;

    public DispatchController(DispatchService dispatch, DispatchReadService reads) {
        this.dispatch = dispatch;
        this.reads = reads;
    }

    @Operation(operationId = "packingSlip", summary = "The packing slip: who it is for, where it goes, what is in the parcel, and where the order stands",
            description = "Only for an order that is paid. The delivery block (name, phone, address) is withheld once the order is delivered, except to an order "
                    + "administrator. No money, no logins, no actors; the fare only for a caller who may see cost (COST_VIEWER).")
    @ApiResponse(responseCode = "200", description = "The slip")
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "order-not-paid",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public DispatchReadService.Slip slip(@AuthenticationPrincipal Jwt jwt, Authentication authentication, @PathVariable("id") UUID id) {
        return reads.slip(id, new DispatchReadService.Viewer(Actor.of(jwt), has(authentication, "ROLE_COST_VIEWER"), has(authentication, "ROLE_ORDER_ADMIN")));
    }

    @Operation(operationId = "packOrder", summary = "Pack a paid order (PAID to FULFILLED)",
            description = "Records that the order was packed, at the time given (default now). A delivered order must have its recipient and address kept. "
                    + "A step already recorded answers 200 with replay: true when what you send matches, 409 otherwise.")
    @ApiResponse(responseCode = "200", description = "The order after the step")
    @ApiResponse(responseCode = "400", description = "invalid-occurred-at (in the future, or before the step it follows), backdate-reason-required, or validation",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "order-not-paid, dispatch-step-not-allowed, delivery-details-missing, already-recorded",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/pack")
    public SummaryResponse pack(@AuthenticationPrincipal Jwt jwt, Authentication authentication, @PathVariable("id") UUID id,
                                @Valid @RequestBody(required = false) StepRequest body) {
        StepRequest request = body == null ? new StepRequest(null, null) : body;
        return SummaryResponse.of(dispatch.pack(id, request.occurredAt(), request.backdateReason(), caller(jwt, authentication)));
    }

    @Operation(operationId = "shipOrder", summary = "Send a packed order (FULFILLED to SHIPPED), recording the courier fare when Oriah pays it",
            description = "The parcel goes by the option the shopper chose. When Oriah pays the courier the fare it charged is required (K0.00 if nothing); "
                    + "when the recipient pays the courier, or it is Oriah's own delivery, a fare is refused. Never for a pickup (hand it over). Refused while a "
                    + "box swap on the order is with inventory. The delivery charge is never changed.")
    @ApiResponse(responseCode = "200", description = "The order after the step")
    @ApiResponse(responseCode = "400", description = "invalid-fare, invalid-occurred-at, backdate-reason-required, or validation",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "fare-required, no-fare-for-this-delivery, order-not-paid, dispatch-step-not-allowed, delivery-details-missing, "
            + "box-swap-in-progress, already-dispatched",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/ship")
    public SummaryResponse ship(@AuthenticationPrincipal Jwt jwt, Authentication authentication, @PathVariable("id") UUID id,
                                @Valid @RequestBody(required = false) ShipRequest body) {
        ShipRequest request = body == null ? new ShipRequest(null, null, null, null) : body;
        return SummaryResponse.of(dispatch.ship(id, request.reference(), request.farePaid(), request.occurredAt(), request.backdateReason(),
                caller(jwt, authentication)));
    }

    @Operation(operationId = "deliverOrder", summary = "Record that the order was delivered (SHIPPED to DELIVERED); a pickup is handed over (FULFILLED to DELIVERED)",
            description = "A pickup's hand-over is its dispatch, so it is refused while a box swap on the order is with inventory.")
    @ApiResponse(responseCode = "200", description = "The order after the step")
    @ApiResponse(responseCode = "400", description = "invalid-occurred-at, backdate-reason-required, or validation",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "order-not-paid, dispatch-step-not-allowed, box-swap-in-progress, already-recorded",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/deliver")
    public SummaryResponse deliver(@AuthenticationPrincipal Jwt jwt, Authentication authentication, @PathVariable("id") UUID id,
                                   @Valid @RequestBody(required = false) StepRequest body) {
        StepRequest request = body == null ? new StepRequest(null, null) : body;
        return SummaryResponse.of(dispatch.deliver(id, request.occurredAt(), request.backdateReason(), caller(jwt, authentication)));
    }

    /** Who is acting and what they may do: read from the signed-in person's authorities, on the request thread. */
    private static Caller caller(Jwt jwt, Authentication authentication) {
        return new Caller(Actor.of(jwt), has(authentication, "ROLE_BACKDATE_SALE"), has(authentication, "ROLE_COST_VIEWER"));
    }

    private static boolean has(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
