package com.oriah.inventory.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Units reserved for a sale, now consumed: they leave the shelf, oldest lots first, and the cost of the lots they
 * came out of is recorded against the sale line.
 *
 * <p>{@code saleLineId} is the sale line this is for. It makes a retry safe (the same sale line with the same
 * quantity is the same sale, answered again, never taken twice) and a reused one refused. {@code occurredAt} is
 * when the sale happened (the day the money arrived); left out it is now. A paid sale is never refused for its date,
 * only for being in the future.
 */
public record ConsumeRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", description = "Units sold")
        @NotNull(message = "qty must not be null")
        @Min(value = 1, message = "qty must be >= 1")
        Integer qty,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The sale line these units are for; used once")
        @NotNull(message = "saleLineId must not be null")
        UUID saleLineId,

        @Schema(types = {"string", "null"}, description = "When the sale happened; left out, now. Never in the future")
        Instant occurredAt
) {}
