package com.oriah.inventory.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Some of what was written off or given away comes back. {@code requestId} makes a retry safe. */
public record TakeBackRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Chosen by the caller, used once: a retry with the same id is the same request")
        @NotNull(message = "requestId must not be null")
        UUID requestId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", description = "Units; at most as many as are still out")
        @NotNull(message = "qty must not be null")
        @Min(value = 1, message = "qty must be >= 1")
        Integer qty,

        @Schema(types = {"string", "null"}, description = "When it came back; left out, now. Never in the future, never before the write-off")
        Instant occurredAt,

        @Schema(types = {"string", "null"}, maxLength = 200)
        @Size(max = 200, message = "note must be at most 200 characters")
        String note
) {}
