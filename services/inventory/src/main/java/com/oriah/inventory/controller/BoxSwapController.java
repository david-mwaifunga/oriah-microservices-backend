package com.oriah.inventory.controller;

import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.inventory.service.BoxSwapService;
import com.oriah.inventory.service.BoxSwapService.Swap;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A box changed after the order was paid (Phase 7). For the order system, not for people: INVENTORY_ADMIN AND the service account, and the
 * gateway does not route this prefix. The order system decides who may ask (ORDER_ADMIN, with a reason) and mints the amendment id that makes a
 * retry safe.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory/box-swaps")
@Tag(name = "Box swaps (order system)", description = "A box changed after payment; service to service only")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Not the order system: needs INVENTORY_ADMIN and the service account",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('INVENTORY_ADMIN') and @machineCaller.isMachine(authentication)")
public class BoxSwapController {

    public record SwapRequest(
            @NotNull(message = "amendmentId is required") UUID amendmentId,
            @NotNull(message = "orderId is required") UUID orderId,
            @NotNull(message = "productId is required") UUID productId,
            @NotNull(message = "originalSaleLineId is required") UUID originalSaleLineId,
            @NotNull(message = "replacementSaleLineId is required") UUID replacementSaleLineId,
            @NotNull(message = "replacementBoxVariantId is required") UUID replacementBoxVariantId,
            @NotNull(message = "a reason is required") @Size(min = 10, max = 200, message = "the reason must be 10 to 200 characters") String reason) {
    }

    private final BoxSwapService swaps;

    public BoxSwapController(BoxSwapService swaps) {
        this.swaps = swaps;
    }

    @Operation(operationId = "swapBox", summary = "Swap the box of a paid order: reverse the original box's sale and sell the replacement",
            description = "One transaction, keyed by the amendment id. Both rows are dated to the day of the sale and recorded now. Boxes go back to free. "
                    + "A repeat of the same swap answers it (replay: true) and changes nothing.")
    @ApiResponse(responseCode = "200", description = "The swap")
    @ApiResponse(responseCode = "400", description = "Validation failure; see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "sale-line-not-found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "amendment-id-used, not-a-box-sale, same-box, already-swapped, not-packaging, insufficient-stock",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<Swap> swap(@Valid @RequestBody SwapRequest request, @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt == null || jwt.getSubject() == null ? "system:unknown" : "user:" + jwt.getSubject();
        return ResponseEntity.ok(swaps.swap(request.amendmentId(), request.orderId(), request.productId(), request.originalSaleLineId(),
                request.replacementSaleLineId(), request.replacementBoxVariantId(), request.reason(), actor));
    }
}
