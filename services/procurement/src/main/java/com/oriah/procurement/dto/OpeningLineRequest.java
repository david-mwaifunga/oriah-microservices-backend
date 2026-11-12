package com.oriah.procurement.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.money.NgweeAmount;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * One line of the opening stock, typed in: which variant, how many, and what ALL of them cost,
 * approximated from the client's old book. A K0 cost is refused.
 */
@Schema(name = "OpeningLineRequest")
public record OpeningLineRequest(

        @NotNull(message = "variantId is required")
        @Schema(description = "A variant picked from the catalogue's admin list (drafts and packaging included)")
        UUID variantId,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 1_000_000, message = "quantity must be at most 1,000,000")
        @Schema(description = "How many pieces", requiredMode = Schema.RequiredMode.REQUIRED, example = "7")
        int quantity,

        @NotNull(message = "totalCost is required")
        @DecimalMin(value = "0.01", message = "totalCost must be above zero: a K0 cost is refused")
        @NgweeAmount
        @Schema(description = "What all of them cost, in kwacha to the ngwee (an approximation from the old book)",
                example = "10000.00")
        BigDecimal totalCost,

        @PositiveOrZero(message = "chargeableWeightGrams must be 0 or more")
        @Schema(description = "What the courier billed on for the whole line, in whole grams",
                types = {"integer", "null"})
        Long chargeableWeightGrams,

        @Schema(description = "Where the goods came from: a two-letter country code", types = {"string", "null"},
                example = "CN")
        String originCountry,

        @Schema(description = "HS code: digits and dots", types = {"string", "null"}, example = "7113.19.00")
        String hsCode) {
}
