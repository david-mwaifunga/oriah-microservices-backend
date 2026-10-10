package com.oriah.order.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.order.dto.OrderResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.dto.TransitionRequest;
import com.oriah.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Orders (admin)", description = "Order reads and state transitions, ORDER_ADMIN only")
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
@PreAuthorize("hasRole('ORDER_ADMIN')")
public class AdminOrderController {

    private final OrderService service;

    public AdminOrderController(OrderService service) {
        this.service = service;
    }

    @Operation(operationId = "list", summary = "All orders, paged")
    @ApiResponse(responseCode = "200", description = "A page of orders")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public ResponseEntity<Page<OrderResponse>> list(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.findAll(pageable).map(OrderResponse::from));
    }

    /**
     * {@code operationId = "get_1"} is not a typo.
     *
     * <p>springdoc derived it: this service has two handlers named {@code get}
     * -- one here, one on {@link com.oriah.order.controller.OrderController} --
     * so it disambiguated by appending a suffix, and the suffix landed on
     * <em>this</em> one even though {@code AdminOrderController} sorts first
     * alphabetically. Which handler receives it is decided by registration
     * order, not source order.
     *
     * <p>That is exactly why it is pinned. Left derived, adding or removing any
     * sibling handler could move the suffix to the other {@code get} and
     * silently swap two operation ids in the published contract. Pinning both
     * halves of the pair -- {@code get} on OrderController, {@code get_1} here
     * -- freezes it.
     *
     * <p>Renaming them to something meaningful is safe in reality, because
     * nothing in oriah-web consumes operationIds (the generated client is
     * gitignored and unimported). It is left for a deliberate, separately
     * recorded change rather than smuggled into a conventions tier.
     */
    @Operation(operationId = "get_1", summary = "One order by id")
    @ApiResponse(responseCode = "200", description = "The order")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable("id") UUID id) {
        return service.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id.toString()));
    }

    /**
     * Admins can drive any allowed FSM transition (fulfill, ship,
     * deliver). The domain enforces what is reachable from the current state.
     *
     * <p>This used to say illegal targets "throw IllegalStateException -> 500
     * today; we'd map to 409 in a polish cycle". That comment outlived its
     * truth: the shared advice has mapped {@code IllegalStateException} to
     * <strong>409 Conflict</strong> since Tier 0.5, so an illegal transition
     * already answers correctly. Corrected rather than deleted, because a
     * comment claiming a 500 would send the next reader looking for a bug that
     * is not there.
     */
    @Operation(operationId = "transition", summary = "Drive an allowed FSM transition")
    @ApiResponse(responseCode = "200", description = "The order, in its new state")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The request conflicts with current state",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/transition")
    public OrderResponse transition(
            @PathVariable("id") UUID id,
            @Valid @RequestBody TransitionRequest body) {
        return service.transition(id, body.targetStatus())
                .map(OrderResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id.toString()));
    }
}
