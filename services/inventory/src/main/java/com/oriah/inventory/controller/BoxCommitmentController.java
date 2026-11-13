package com.oriah.inventory.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.inventory.service.BoxCommitmentService;
import com.oriah.inventory.service.BoxCommitmentService.BoxCounts;
import com.oriah.inventory.service.BoxCommitmentService.Change;
import com.oriah.inventory.service.BoxCommitmentService.Commitment;
import com.oriah.observability.ResourceNotFoundException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Boxes set aside for a product, one record per product on its default box (CLIENT_DECISIONS 4.2). INVENTORY_ADMIN, the plan's gate for
 * box commitments. The reads are counts and names only: a box's cost is read under {@code /api/v1/admin/costs}.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory/box-commitments")
@Tag(name = "Box commitments (admin)", description = "Boxes set aside for a product; INVENTORY_ADMIN")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking INVENTORY_ADMIN",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('INVENTORY_ADMIN')")
public class BoxCommitmentController {

    /** How many boxes to set aside for the product. */
    public record SetCommitmentRequest(
            // A decimal in the request so that 2.5 boxes is refused, not quietly read as 2: a whole number of boxes, at least one.
            @NotNull(message = "quantity is required")
            @DecimalMin(value = "1", message = "quantity must be at least 1 box")
            @Digits(integer = 6, fraction = 0, message = "quantity must be a whole number of boxes")
            java.math.BigDecimal quantity) {
    }

    /** A product's boxes, and the box's counts as they now stand. */
    public record CommitmentResponse(UUID productId, UUID boxVariantId, int quantity, Instant committedAt, String committedBy, BoxCounts box) {
    }

    public record CommitmentList(List<Commitment> commitments) {
    }

    public record CommitmentHistory(List<Change> changes) {
    }

    private final BoxCommitmentService commitments;

    public BoxCommitmentController(BoxCommitmentService commitments) {
        this.commitments = commitments;
    }

    @Operation(operationId = "setBoxCommitment",
            summary = "Set boxes aside for a product, on its default box",
            description = "The box is the product's default box, which inventory learned from the catalogue. Again for the same product it replaces its record: "
                    + "more takes from free, fewer gives back, the same changes nothing. Needs free boxes (a box is never promised twice): otherwise 409 "
                    + "`insufficient-free-packaging` with `available`, the most that can be set aside for this product. A product inventory has not heard of "
                    + "yet is 409 `product-state-not-received`, one with no default box `no-default-box`.")
    @ApiResponse(responseCode = "200", description = "The record, and the box's counts")
    @ApiResponse(responseCode = "400", description = "Not a whole number of boxes of at least 1",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "insufficient-free-packaging, product-state-not-received, no-default-box, not-a-sellable-product or not-packaging",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/{productId}")
    public ResponseEntity<CommitmentResponse> set(@PathVariable("productId") UUID productId, @Valid @RequestBody SetCommitmentRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        BoxCommitmentService.Saved saved = commitments.set(productId, request.quantity().intValueExact(), actor(jwt));
        Commitment c = saved.commitment();
        return ResponseEntity.ok(new CommitmentResponse(c.productId(), c.boxVariantId(), c.quantity(), c.committedAt(), c.committedBy(), saved.box()));
    }

    @Operation(operationId = "releaseBoxCommitment",
            summary = "Let a product's boxes go: they are free again and nothing leaves the building")
    @ApiResponse(responseCode = "204", description = "Released")
    @ApiResponse(responseCode = "404", description = "The product has nothing set aside",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> release(@PathVariable("productId") UUID productId, @AuthenticationPrincipal Jwt jwt) {
        if (commitments.release(productId, null, actor(jwt)).isEmpty()) {
            throw new ResourceNotFoundException("Box commitment", "productId", productId.toString());
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "listBoxCommitments",
            summary = "The boxes set aside, by product or by box",
            description = "Oldest first. With neither filter, every record.")
    @ApiResponse(responseCode = "200", description = "The records")
    @GetMapping
    public CommitmentList list(@RequestParam(name = "productId", required = false) UUID productId,
                               @RequestParam(name = "boxVariantId", required = false) UUID boxVariantId) {
        return new CommitmentList(commitments.commitments(productId, boxVariantId));
    }

    @Operation(operationId = "boxCommitmentHistory",
            summary = "Everything done to a product's boxes, in order: set aside, replaced, let go, sold, cut by a write-off",
            description = "Append-only. Each entry says what, how many, how many were left set aside after it, who or what caused it and when.")
    @ApiResponse(responseCode = "200", description = "The history, possibly empty")
    @GetMapping("/{productId}/history")
    public CommitmentHistory history(@PathVariable("productId") UUID productId) {
        return new CommitmentHistory(commitments.history(productId));
    }

    private static String actor(Jwt jwt) {
        return "user:" + jwt.getSubject();
    }
}
