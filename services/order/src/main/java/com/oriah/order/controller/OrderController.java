package com.oriah.order.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.RestController;

import com.oriah.order.dto.OrderResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.dto.PlaceOrderRequest;
import com.oriah.order.dto.SubmitPaymentRequest;
import com.oriah.order.model.Order;
import com.oriah.order.service.OrderClaimService;
import com.oriah.order.service.DispatchViews;
import com.oriah.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "The caller's own orders")
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
public class OrderController {

    private final OrderService service;
    private final OrderClaimService claimService;
    private final DispatchViews views;

    public OrderController(OrderService service, OrderClaimService claimService, DispatchViews views) {
        this.views = views;
        this.service = service;
        this.claimService = claimService;
    }

    @Operation(operationId = "claimPayment", summary = "Say how you paid: a mobile-money or bank-transfer reference",
            description = "Not money: staff confirm the payment (or tell you they cannot find it). Giving a reference stretches the hold on your pieces "
                    + "to a day so they have time to look. Only an order waiting for payment can take one, and only once: the same reference again "
                    + "is the same claim, another is refused.")
    @ApiResponse(responseCode = "200", description = "The order, now PAYMENT_SUBMITTED")
    @ApiResponse(responseCode = "400", description = "The method is not one that can be claimed online (cash is paid in person), or the reference is "
            + "not a reference; see fieldErrors", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "payment-already-submitted (another reference was given), order-not-awaiting-payment, "
            + "order-not-held, hold-expired (the pieces are no longer held: place the order again), payment-being-applied",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/payment-claim")
    public OrderResponse claimPayment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id,
            @Valid @RequestBody SubmitPaymentRequest body) {
        return views.shopper(claimService.submit(jwt.getSubject(), id, body.method(), body.reference(), Actor.of(jwt)));
    }

    @Operation(operationId = "place", summary = "Place an order")
    @ApiResponse(responseCode = "201", description = "Created; Location points at the order")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter (see "
            + "fieldErrors), or the shipping address is not one of the caller's own; nothing was saved",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The basket or its price changed since the shopper saw "
            + "the quote (pricing-changed), or a line cannot be sold; nothing was saved",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "The total is larger than an order can hold",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "customer, catalog, shipping or the token server is "
            + "unreachable; nothing was saved -- retry after Retry-After",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<OrderResponse> place(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest body) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 128 characters");
        }
        OrderService.Placement placement = service.placeOrder(jwt.getSubject(), body, key, Actor.of(jwt));
        Order o = placement.order();
        // 201 when this call made the order; 200 when the same placement (the same key, or the same bag still waiting for payment) had already been made.
        return (placement.created()
                ? ResponseEntity.created(URI.create("/api/v1/orders/" + o.id()))
                : ResponseEntity.ok().location(URI.create("/api/v1/orders/" + o.id())))
                .body(views.shopper(o));
    }

    @Operation(operationId = "mine", summary = "The caller's orders, paged")
    @ApiResponse(responseCode = "200", description = "A page of orders")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/me")
    public ResponseEntity<Page<OrderResponse>> mine(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(views.shopper(service.findMine(jwt.getSubject(), pageable)));
    }

    /**
     * One of the caller's own orders.
     *
     * <p>The {@code filter} on ownership before the 404 is deliberate:
     * "not yours" and "does not exist" must be indistinguishable, or an id is
     * an existence oracle. The shared {@code ResourceNotFoundException}
     * phrasing preserves that -- it names the id the caller already sent and
     * nothing else -- and
     * {@code OrderIntegrationTest.othersOrderIsIndistinguishableFromMissing}
     * asserts the two bodies match, so a friendlier message fails the build.
     */
    @Operation(operationId = "get", summary = "One of the caller's own orders")
    @ApiResponse(responseCode = "200", description = "The order")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public OrderResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id) {
        return service.findById(id)
                .filter(o -> o.isOwnedBy(jwt.getSubject()))
                .map(views::shopper)
                .orElseThrow(() -> unknownOrder(id));
    }

    @Operation(operationId = "cancel", summary = "Cancel one of the caller's own orders")
    @ApiResponse(responseCode = "200", description = "The order, now CANCELLED")
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The request conflicts with current state",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id) {
        return service.cancel(jwt.getSubject(), id, Actor.of(jwt))
                .map(views::shopper)
                .orElseThrow(() -> unknownOrder(id));
    }

    private static ResourceNotFoundException unknownOrder(UUID id) {
        return new ResourceNotFoundException("Order", "id", id.toString());
    }
}
