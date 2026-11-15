package com.oriah.order.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.order.dto.RecordOrderRequest;
import com.oriah.order.dto.RecordedOrderView;
import com.oriah.order.service.RecordedOrderService;
import com.oriah.order.service.RecordedOrderService.Who;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Recording a sale taken by phone, chat or paper for a caller with no account (Phase 9). SALES_AGENT, which the owner and the manager hold through their job
 * roles. The gateway's {@code /api/v1/admin/orders/recorded/**} matcher sends these to SALES_AGENT ahead of the ORDER_ADMIN prefix, and the capability is checked
 * here as well. A salesperson reads no landed cost or margin (Q29): every answer is an allow-list built for them, never an order administrator's view.
 */
@RestController
@RequestMapping("/api/v1/admin/orders/recorded")
@Tag(name = "Recorded orders", description = "Orders staff record for a caller with no account; SALES_AGENT")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking SALES_AGENT (or BACKDATE_SALE for a paper sale older than the window)",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('SALES_AGENT')")
public class RecordedOrderController {

    private static final String BACKDATE_SALE = "ROLE_BACKDATE_SALE";

    private final RecordedOrderService service;

    public RecordedOrderController(RecordedOrderService service) {
        this.service = service;
    }

    private static Who who(Jwt jwt, Authentication auth) {
        return new Who(Actor.of(jwt), auth.getAuthorities().stream().anyMatch(a -> BACKDATE_SALE.equals(a.getAuthority())));
    }

    @Operation(operationId = "recordOrder", summary = "Record a sale for a caller with no account, and hold its pieces",
            description = "Staff say who is buying, what, how it goes and (for a paper sale) when; the server dates and prices everything itself (no price, discount or total can be "
                    + "typed) and the person confirms the total they read to the caller in `expectedTotal`. A repeat with the same `Idempotency-Key` and request is the same order "
                    + "(200); the pieces are held for ten minutes, and payment is the staff confirmation of money (`POST /api/v1/admin/payments/confirmations`).")
    @ApiResponse(responseCode = "201", description = "Recorded and held")
    @ApiResponse(responseCode = "200", description = "The same request was recorded before: that order")
    @ApiResponse(responseCode = "400", description = "A body that cannot be recorded; see fieldErrors or the problem type (invalid-contact, invalid-delivery, "
            + "delivery-reason-required, invalid-occurred-at, backdate-reason-required)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "idempotency-key-reused, total-differs (names the real total), zero-total-not-supported, no-price-at-date, "
            + "late-sale-delivery-must-be-agreed, delivery-place-not-offered, town-not-offered, insufficient-stock (names each short piece or box)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Catalog, shipping or inventory did not answer: nothing is saved, try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<RecordedOrderView> record(@AuthenticationPrincipal Jwt jwt, Authentication auth,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                    @Valid @RequestBody RecordOrderRequest request) {
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 128 characters");
        }
        RecordedOrderService.Recorded done = service.record(request, key, who(jwt, auth));
        return ResponseEntity.status(done.created() ? HttpStatus.CREATED : HttpStatus.OK).body(RecordedOrderView.of(done.order(), !done.created()));
    }
}
