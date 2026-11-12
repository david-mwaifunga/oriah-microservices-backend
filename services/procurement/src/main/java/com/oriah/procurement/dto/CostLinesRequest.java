package com.oriah.procurement.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Replacing every cost line of a DRAFT consignment. An empty list clears them.
 *
 * @param version the version of the consignment this change was made from
 */
@Schema(name = "CostLinesRequest")
public record CostLinesRequest(

        @NotNull(message = "version is required")
        @PositiveOrZero(message = "version must be 0 or more")
        Long version,

        @NotNull(message = "costLines is required (an empty list clears them)")
        @Size(max = 30, message = "a consignment holds at most 30 cost lines")
        @Valid
        List<CostLineRequest> costLines) {
}
