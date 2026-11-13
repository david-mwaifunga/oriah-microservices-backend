package com.oriah.order.controller;

import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.order.service.BoxSwapService;
import com.oriah.order.service.BoxSwaps.Swap;

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
 * Changes the box of a paid order (Phase 7). ORDER_ADMIN only -- held by a manager and the owner, not by a sales person -- and always with a reason.
 * The gateway's ORDER_ADMIN prefix guards this path; the capability is checked here as well, so the edge is not the only thing standing in front of it.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Box swaps", description = "A box changed after the order was paid; ORDER_ADMIN")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking ORDER_ADMIN",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('ORDER_ADMIN')")
public class BoxSwapController {

    /**
     * @param lineId                  the order line whose box changes
     * @param replacementBoxVariantId the packaging variant that goes out instead
     * @param reason                  why, 10 to 200 characters
     * @param amendmentId             the caller's id for this swap, to retry safely after a lost reply; absent mints one (it is in the answer)
     */
    public record BoxSwapRequest(
            @NotNull(message = "lineId is required") UUID lineId,
            @NotNull(message = "replacementBoxVariantId is required") UUID replacementBoxVariantId,
            @NotNull(message = "a reason is required") @Size(min = 10, max = 200, message = "the reason must be 10 to 200 characters") String reason,
            UUID amendmentId) {
    }

    private final BoxSwapService swaps;

    public BoxSwapController(BoxSwapService swaps) {
        this.swaps = swaps;
    }

    @Operation(operationId = "swapBox", summary = "Change the box of a paid order's line",
            description = "Inventory reverses the original box's sale and sells the replacement, both dated to the day of the SALE (a swap never moves it); "
                    + "the line then carries the new box. Only a PAID order that has not been fulfilled. Send the same amendmentId to retry after a lost "
                    + "reply: a repeat answers the same swap (replay: true).")
    @ApiResponse(responseCode = "200", description = "The swap")
    @ApiResponse(responseCode = "400", description = "Validation failure (a reason of 10 to 200 characters is required); see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such order or line",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "box-swap-not-allowed, same-box, no-box-on-line, payment-being-applied, amendment-id-used, or "
            + "inventory's own refusal (not-packaging, insufficient-stock, already-swapped, not-a-box-sale)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Inventory did not answer; nothing was recorded: retry with the same amendmentId",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/box-swaps")
    public Swap swap(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id, @Valid @RequestBody BoxSwapRequest body) {
        return swaps.swap(id, body.lineId(), body.replacementBoxVariantId(), body.reason(), body.amendmentId(), Actor.of(jwt));
    }
}
