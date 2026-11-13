package com.oriah.inventory.controller;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

import com.oriah.inventory.service.ReservationService;
import com.oriah.inventory.service.ReservationService.Line;
import com.oriah.inventory.service.ReservationService.View;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * An order's hold on its pieces and boxes (Phase 7). For the order system, not for people: every call needs INVENTORY_ADMIN AND to be the
 * oriah-admin service account ({@code MachineCaller}); the gateway does not route this prefix. A reply carries counts, times and where each
 * line's units came from, never a cost: cost is read under {@code /api/v1/admin/costs}.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory/reservations")
@Tag(name = "Reservations (order system)", description = "An order's hold on its pieces and boxes; service to service only")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Not the order system: needs INVENTORY_ADMIN and the service account",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('INVENTORY_ADMIN') and @machineCaller.isMachine(authentication)")
public class ReservationController {

    /** One piece the order is buying; its box (one per piece) is the product's default box unless {@code boxVariantId} names another. */
    public record LineRequest(
            @NotNull(message = "saleLineId is required") UUID saleLineId,
            @NotNull(message = "variantId is required") UUID variantId,
            @NotNull(message = "productId is required") UUID productId,
            // A decimal in the request so that 2.5 pieces is refused, not quietly read as 2.
            @NotNull(message = "quantity is required")
            @DecimalMin(value = "1", message = "quantity must be at least 1")
            @Digits(integer = 6, fraction = 0, message = "quantity must be a whole number")
            BigDecimal quantity,
            @NotNull(message = "boxLineId is required") UUID boxLineId,
            UUID boxVariantId) {
    }

    public record ReserveRequest(
            @NotNull(message = "reservationId is required") UUID reservationId,
            @NotNull(message = "orderId is required") UUID orderId,
            @NotEmpty(message = "a reservation needs at least one line") List<@Valid @NotNull LineRequest> lines,
            @Min(value = 1, message = "holdSeconds must be at least 1") @Max(value = 86_400, message = "a hold is at most a day") Long holdSeconds) {
    }

    public record ConsumeRequest(Instant occurredAt) {
    }

    public record ExtendRequest(@NotNull(message = "until is required") Instant until) {
    }

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Operation(operationId = "reserveOrder", summary = "Hold an order's pieces and their boxes, all or nothing",
            description = "A shortage on any line holds nothing and names every short line. Asking again with the same reservation id holds nothing more. "
                    + "An order holds one reservation at a time.")
    @ApiResponse(responseCode = "200", description = "The reservation, HELD, with where each line's units came from")
    @ApiResponse(responseCode = "400", description = "Validation failure; see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "insufficient-stock (with shortages), order-already-holds, reservation-id-used, no-default-box, "
            + "product-state-not-received, sale-line-already-sold",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<View> reserve(@Valid @RequestBody ReserveRequest request, @AuthenticationPrincipal Jwt jwt) {
        List<Line> lines = request.lines().stream()
                .map(l -> new Line(l.saleLineId(), l.variantId(), l.productId(), l.quantity().intValueExact(), l.boxLineId(), l.boxVariantId()))
                .toList();
        Duration hold = request.holdSeconds() == null ? null : Duration.ofSeconds(request.holdSeconds());
        return ResponseEntity.ok(reservations.reserve(request.reservationId(), request.orderId(), lines, hold, actor(jwt)));
    }

    @Operation(operationId = "consumeReservation", summary = "The order has paid: sell every line, dated to the money",
            description = "Pieces and boxes drain their oldest lots in one transaction, each under the order's own sale-line id. Idempotent: consuming again "
                    + "answers the same sale. A hold that has lapsed cannot be sold, even before the sweep has ended it.")
    @ApiResponse(responseCode = "200", description = "The reservation, CONSUMED")
    @ApiResponse(responseCode = "400", description = "occurredAt is in the future",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "reservation-not-found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "reservation-not-held or reservation-expired",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{reservationId}/consume")
    public ResponseEntity<View> consume(@PathVariable("reservationId") UUID reservationId, @RequestBody(required = false) ConsumeRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(reservations.consume(reservationId, request == null ? null : request.occurredAt(), actor(jwt)));
    }

    @Operation(operationId = "releaseReservation", summary = "Let go of the hold",
            description = "Pieces and boxes go back where they came from. Releasing a reservation that is not held (sold, released, expired) changes nothing.")
    @ApiResponse(responseCode = "200", description = "The reservation as it now stands")
    @ApiResponse(responseCode = "404", description = "reservation-not-found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{reservationId}/release")
    public ResponseEntity<View> release(@PathVariable("reservationId") UUID reservationId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(reservations.release(reservationId, actor(jwt)));
    }

    @Operation(operationId = "extendReservation", summary = "Stretch the hold (a payment reference was submitted)",
            description = "Never beyond a day from when it was made, and never shorter than it is.")
    @ApiResponse(responseCode = "200", description = "The reservation with its new time")
    @ApiResponse(responseCode = "404", description = "reservation-not-found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "reservation-not-held or reservation-expired",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{reservationId}/extend")
    public ResponseEntity<View> extend(@PathVariable("reservationId") UUID reservationId, @Valid @RequestBody ExtendRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(reservations.extend(reservationId, request.until(), actor(jwt)));
    }

    @Operation(operationId = "getReservation", summary = "A reservation as it stands")
    @ApiResponse(responseCode = "200", description = "The reservation")
    @ApiResponse(responseCode = "404", description = "reservation-not-found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{reservationId}")
    public ResponseEntity<View> get(@PathVariable("reservationId") UUID reservationId) {
        return ResponseEntity.ok(reservations.get(reservationId));
    }

    private static String actor(Jwt jwt) {
        return jwt == null || jwt.getSubject() == null ? "system:unknown" : "user:" + jwt.getSubject();
    }
}
