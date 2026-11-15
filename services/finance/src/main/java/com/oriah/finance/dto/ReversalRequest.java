package com.oriah.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Taking an expense back: always with a reason, which is kept with the reversal. */
@Schema(name = "ReversalRequest")
public record ReversalRequest(
        @NotBlank(message = "reason is required") @Size(min = 5, max = 200, message = "reason is 5 to 200 characters") @Schema(example = "keyed twice by mistake") String reason) {
}
