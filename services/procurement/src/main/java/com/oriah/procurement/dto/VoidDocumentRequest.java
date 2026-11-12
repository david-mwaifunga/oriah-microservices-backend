package com.oriah.procurement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Voiding a document: it stays on record, marked as not to be relied on, and says why. */
@Schema(name = "VoidDocumentRequest")
public record VoidDocumentRequest(

        @NotBlank(message = "reason is required: say why this document is being voided")
        @Size(max = 200, message = "reason must be at most 200 characters")
        @Schema(example = "This was last month's bill")
        String reason) {
}
