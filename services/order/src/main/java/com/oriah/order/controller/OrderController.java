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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.order.dto.AttachPaymentRequest;
import com.oriah.order.dto.OrderResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.dto.PlaceOrderRequest;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
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

    public OrderController(OrderService service) {
        this.service = service;
    }

    @Operation(operationId = "place", summary = "Place an order")
    @ApiResponse(responseCode = "201", description = "Created; Location points at the order")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<OrderResponse> place(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PlaceOrderRequest body) {
        Order o = service.placeOrder(jwt.getSubject(), body);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + o.id()))
                .body(OrderResponse.from(o));
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
        return ResponseEntity.ok(service.findMine(jwt.getSubject(), pageable).map(OrderResponse::from));
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
                .filter(o -> o.keycloakId().equals(jwt.getSubject()))
                .map(OrderResponse::from)
                .orElseThrow(() -> unknownOrder(id));
    }

    /**
     * Attach a settled PaymentIntent to one of the caller's own orders and
     * transition it to PAID.
     *
     * <p>The claim in the body is verified against payment-service before
     * anything is attached: the intent must be SUCCEEDED, must name this
     * order, and must be in this order's currency.
     *
     * <p>It is NOT checked against the order's AMOUNT, and that gap is
     * deliberate rather than an oversight -- {@code order.total} is the
     * client-supplied line sum while checkout charges
     * {@code afterDiscount + shipping}, so an exact comparison would reject
     * real checkouts. A 0.01 intent therefore still settles a 4,500.00 order,
     * and closing that needs the order total to become authoritative. This
     * paragraph exists because the previous wording claimed the amount WAS
     * verified: anyone auditing the money path from this controller would have
     * concluded the hole was closed and stopped reading. See
     * {@link com.oriah.order.service.PaymentIntentVerifier}. This endpoint
     * previously trusted the body, so any shopper could post a random UUID
     * against their own order and receive a PAID order having paid nothing.
     *
     * <p>Note that the verification is a property of the ENDPOINT, not of the
     * checkout orchestrator. Checkout reaches this same path forwarding the
     * shopper's own JWT, so there is no way to tell an orchestrated call from
     * a hand-rolled one; the only defence is checking the claim, which means
     * checkout gets no special treatment and needs none.
     */
    @Operation(operationId = "pay", summary = "Attach a settled payment intent and transition to PAID")
    @ApiResponse(responseCode = "200", description = "The order, now PAID")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409",
            description = "The request conflicts with current state, or the payment intent "
                    + "could not be verified as a settled payment for this order",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503",
            description = "Payment could not be consulted; the order has NOT been marked paid",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/pay")
    public OrderResponse pay(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id,
            @Valid @RequestBody AttachPaymentRequest body) {
        // Verify first, outside any transaction, and only then attach. Each
        // call goes through the service proxy so attach and transition keep
        // their own transactional boundaries.
        return service.verifyPaymentClaim(jwt.getSubject(), id, body.paymentIntentId())
                .flatMap(o -> service.attachPayment(jwt.getSubject(), id, body.paymentIntentId()))
                .flatMap(o -> service.transition(o.id(), OrderStatus.PAID))
                .map(OrderResponse::from)
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
        return service.findById(id)
                .filter(o -> o.keycloakId().equals(jwt.getSubject()))
                .flatMap(o -> service.transition(o.id(), OrderStatus.CANCELLED))
                .map(OrderResponse::from)
                .orElseThrow(() -> unknownOrder(id));
    }

    private static ResourceNotFoundException unknownOrder(UUID id) {
        return new ResourceNotFoundException("Order", "id", id.toString());
    }
}
