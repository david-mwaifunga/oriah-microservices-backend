package com.oriah.inventory.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Stock leaves with no sale. {@code reason} comes from the one closed list (never free text); {@code note} is a
 * remark kept beside it. {@code requestId} makes a retry safe: the same request answers again and takes nothing.
 */
public record WriteOffRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Chosen by the caller, used once: a retry with the same id is the same request")
        @NotNull(message = "requestId must not be null")
        UUID requestId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "variantId must not be null")
        UUID variantId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", description = "Units; only units nobody has reserved can go")
        @NotNull(message = "qty must not be null")
        @Min(value = 1, message = "qty must be >= 1")
        Integer qty,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"WRITE_OFF_LOST", "WRITE_OFF_STOLEN", "WRITE_OFF_BROKEN",
                "GIVEN_AWAY_MODEL", "GIVEN_AWAY_INFLUENCER", "GIVEN_AWAY_GIFT", "GIVEN_AWAY_PRIZE"})
        @NotBlank(message = "reason must not be blank")
        String reason,

        @Schema(types = {"string", "null"}, description = "When it happened; left out, now. Never in the future, and never before the stock arrived")
        Instant occurredAt,

        @Schema(types = {"string", "null"}, maxLength = 200)
        @Size(max = 200, message = "note must be at most 200 characters")
        String note
) {}
