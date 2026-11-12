package com.oriah.procurement.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Changing an OPEN order that is not yet in a consignment. Every field but {@code version} is
 * optional: left out means unchanged, and a blank {@code note} clears it. {@code lines}, when
 * sent, REPLACES all the lines.
 *
 * @param version the version of the order this change was made from. Required: a change made
 *                from a stale copy is refused (409) instead of overwriting someone else's
 */
@Schema(name = "UpdatePurchaseOrderRequest")
public record UpdatePurchaseOrderRequest(

        @NotNull(message = "version is required")
        @PositiveOrZero(message = "version must be 0 or more")
        Long version,

        @Schema(types = {"string", "null"})
        LocalDate placedOn,

        @Size(max = 500, message = "note must be at most 500 characters")
        @Schema(types = {"string", "null"})
        String note,

        @Size(min = 1, max = 100, message = "an order holds 1 to 100 lines")
        @Valid
        @Schema(types = {"array", "null"}, description = "When sent, replaces every line")
        List<PurchaseOrderLineRequest> lines) {
}
