package com.oriah.inventory.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.inventory.service.ReturnedPieceService;
import com.oriah.inventory.service.ReturnedPieceService.Decision;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * What becomes of a piece a customer sent back (Phase 12). For the order system, not for people: INVENTORY_ADMIN AND the service account,
 * and the gateway does not route this prefix. The order system decides who may assess a return (and checks what they may do); inventory
 * moves the piece.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory/returned-pieces")
@Tag(name = "Returned pieces (order system)", description = "What becomes of a returned piece once it has been looked at; service to service only")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Not the order system: needs INVENTORY_ADMIN and the service account",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('INVENTORY_ADMIN') and @machineCaller.isMachine(authentication)")
public class ReturnedPieceController {

    public record OutcomeRequest(
            @NotNull(message = "outcome is required")
            @Pattern(regexp = "RELEASED|WRITTEN_OFF|RELISTED", message = "outcome must be RELEASED, WRITTEN_OFF or RELISTED") String outcome,
            @Schema(description = "Only for RELISTED: the marked-down listing the piece moves to") UUID childVariantId,
            @Schema(description = "When it was decided; now when absent. Never before the piece came back, never in the future") Instant occurredAt) {
    }

    private final ReturnedPieceService pieces;

    public ReturnedPieceController(ReturnedPieceService pieces) {
        this.pieces = pieces;
    }

    @Operation(operationId = "decideReturnedPiece", summary = "Say what becomes of a returned piece: back on sale, written off, or moved to a marked-down listing",
            description = "One transaction, once per piece. RELEASED: ordinary stock again, nothing moves. WRITTEN_OFF: out of the lot it went back to, at that "
                    + "lot's cost. RELISTED: into a one-piece lot of its own marked-down listing, at the cost it had; nothing is recognised. "
                    + "The same answer again is a repeat (replay: true).")
    @ApiResponse(responseCode = "200", description = "The decision")
    @ApiResponse(responseCode = "400", description = "Validation failure, or invalid-occurred-at (before the piece came back, or in the future)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "returned-piece-not-found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "piece-outcome-recorded, markdown-variant-not-known-yet (try again shortly), "
            + "markdown-variant-of-another-product, markdown-variant-in-use, already-marked-down",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{pieceId}/outcome")
    public ResponseEntity<Decision> decide(@PathVariable UUID pieceId, @Valid @RequestBody OutcomeRequest request, @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt == null || jwt.getSubject() == null ? "system:unknown" : "user:" + jwt.getSubject();
        return ResponseEntity.ok(pieces.decide(pieceId, request.outcome(), request.childVariantId(), request.occurredAt(), actor));
    }
}
