package com.oriah.inventory.controller;

import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.service.SaleReversalService;
import com.oriah.inventory.service.SaleReversalService.BoxLine;
import com.oriah.inventory.service.SaleReversalService.GoodsLine;
import com.oriah.inventory.service.SaleReversalService.Piece;
import com.oriah.inventory.service.SaleReversalService.Reversal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * A paid sale put back into stock (Phase 12): a paid order cancelled before it left, or pieces of a parcel that came back. For the order
 * system, not for people: INVENTORY_ADMIN AND the service account, and the gateway does not route this prefix. The order system decides who
 * may ask and mints the reversal id that makes a retry safe.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory/sale-reversals")
@Tag(name = "Sale reversals (order system)", description = "A cancelled or returned sale put back into stock; service to service only")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Not the order system: needs INVENTORY_ADMIN and the service account",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('INVENTORY_ADMIN') and @machineCaller.isMachine(authentication)")
public class SaleReversalController {

    public record GoodsLineRequest(
            @NotNull(message = "saleLineId is required") UUID saleLineId,
            @NotNull(message = "variantId is required") UUID variantId,
            @Min(value = 1, message = "quantity must be at least 1") int quantity) {
    }

    public record BoxLineRequest(
            @NotNull(message = "boxLineId is required") UUID boxLineId,
            @NotNull(message = "boxVariantId is required") UUID boxVariantId,
            @NotNull(message = "productId is required") UUID productId,
            @Min(value = 1, message = "quantity must be at least 1") int quantity) {
    }

    public record PieceRequest(
            @NotNull(message = "pieceId is required") UUID pieceId,
            @NotNull(message = "saleLineId is required") UUID saleLineId,
            @NotNull(message = "variantId is required") UUID variantId) {
    }

    public record ReversalRequest(
            @NotNull(message = "reversalId is required") UUID reversalId,
            @NotNull(message = "kind is required") @Pattern(regexp = "CANCELLATION|RETURN", message = "kind must be CANCELLATION or RETURN") String kind,
            @NotNull(message = "orderId is required") UUID orderId,
            @Schema(description = "The hold a cancellation undoes; absent for a return") UUID reservationId,
            @NotNull(message = "occurredAt is required") Instant occurredAt,
            @Schema(description = "A cancellation's lines of goods; empty for a return") List<@Valid @NotNull GoodsLineRequest> goods,
            @Schema(description = "A cancellation's box lines, each the box that actually went with the order; empty for a return") List<@Valid @NotNull BoxLineRequest> boxes,
            @Schema(description = "A return's pieces, one by one; empty for a cancellation") List<@Valid @NotNull PieceRequest> pieces) {
    }

    private final SaleReversalService reversals;

    public SaleReversalController(SaleReversalService reversals) {
        this.reversals = reversals;
    }

    @Operation(operationId = "reverseSale", summary = "Put a cancelled or returned sale back into stock",
            description = "One transaction, keyed by the reversal id. A cancellation returns the lines and their boxes to the lots they came out of, as ordinary stock; "
                    + "a return puts each piece back into its lot and holds it there, not for sale, until it is assessed (the box stays sold). "
                    + "Dated to the day of the correction. The same request again answers it (replay: true) and changes nothing.")
    @ApiResponse(responseCode = "200", description = "The reversal")
    @ApiResponse(responseCode = "400", description = "Validation failure, or invalid-occurred-at (before the sale, or in the future)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "sale-line-not-found, reservation-not-found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "reversal-id-used, not-a-sale, reservation-not-consumed, box-line-not-live, more-than-outstanding, piece-id-used",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<Reversal> reverse(@Valid @RequestBody ReversalRequest request, @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt == null || jwt.getSubject() == null ? "system:unknown" : "user:" + jwt.getSubject();
        return ResponseEntity.ok(reversals.reverse(new SaleReversalService.Request(request.reversalId(), request.kind(), request.orderId(),
                request.reservationId(), request.occurredAt(),
                orEmpty(request.goods()).stream().map(l -> new GoodsLine(l.saleLineId(), l.variantId(), l.quantity())).toList(),
                orEmpty(request.boxes()).stream().map(l -> new BoxLine(l.boxLineId(), l.boxVariantId(), l.productId(), l.quantity())).toList(),
                orEmpty(request.pieces()).stream().map(p -> new Piece(p.pieceId(), p.saleLineId(), p.variantId())).toList()), actor));
    }

    @Operation(operationId = "getSaleReversal", summary = "What was done under a reversal id",
            description = "Waits for an attempt in flight under the same id. A 404 named reversal-not-found therefore proves nothing moved; "
                    + "any other failure proves nothing.")
    @ApiResponse(responseCode = "200", description = "The reversal")
    @ApiResponse(responseCode = "404", description = "reversal-not-found: nothing was done under this id",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{reversalId}")
    public ResponseEntity<Reversal> get(@PathVariable UUID reversalId) {
        return ResponseEntity.ok(reversals.find(reversalId).orElseThrow(() -> new CommitmentProblem(HttpStatus.NOT_FOUND, "reversal-not-found",
                "nothing was done under reversal " + reversalId)));
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
