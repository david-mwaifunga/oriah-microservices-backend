package com.oriah.procurement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * What a consignment line records about the goods, REPLACED as a whole: a field left out (or
 * blank) is cleared.
 *
 * @param version the version of the consignment this change was made from
 */
@Schema(name = "LineDetailsRequest")
public record LineDetailsRequest(

        @NotNull(message = "version is required")
        @PositiveOrZero(message = "version must be 0 or more")
        Long version,

        @PositiveOrZero(message = "chargeableWeightGrams must be 0 or more")
        @Schema(description = "What the courier billed on for the WHOLE line, in whole grams. Needed only when a "
                + "cost is shared by weight", types = {"integer", "null"})
        Long chargeableWeightGrams,

        @Schema(description = "Where the goods came from: a two-letter country code", types = {"string", "null"},
                example = "CN")
        String originCountry,

        @Schema(description = "HS code: digits and dots", types = {"string", "null"}, example = "7113.19.00")
        String hsCode) {
}
