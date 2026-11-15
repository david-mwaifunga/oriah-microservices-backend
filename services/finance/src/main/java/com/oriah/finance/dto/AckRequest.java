package com.oriah.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Saying an error or warning has been looked at, and what was found. The note and who wrote it are kept for good. */
@Schema(name = "AckRequest")
public record AckRequest(@NotBlank(message = "note is required") @Size(min = 5, max = 200, message = "note is 5 to 200 characters") @Schema(example = "looked at it: the producer was reset") String note) {
}
