package com.oriah.procurement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Add a supplier: "name, country, who you deal with there, how you pay them"
 * (CLIENT_DECISIONS 4.1). Only the name and the country are required.
 *
 * <p>The limits mirror the aggregate's and the table's, so a request that is too
 * long is a {@code fieldErrors} entry instead of a database error.
 */
@Schema(name = "CreateSupplierRequest")
public record CreateSupplierRequest(

        @NotBlank
        @Size(max = 120)
        @Schema(description = "What the supplier is called. Unique among live suppliers, ignoring letter case.",
                example = "Fycargo", maxLength = 120)
        String name,

        @NotBlank
        @ValidCountry
        @Schema(description = "The supplier's own country, as an ISO 3166-1 alpha-2 code (stored in upper case).",
                example = "CN", minLength = 2, maxLength = 2)
        String country,

        @Size(max = 120)
        @Schema(description = "Who you deal with there. Optional.", example = "Mr Li", maxLength = 120)
        String contactName,

        @Size(max = 500)
        @Schema(description = "How to reach them: phone, WhatsApp, email. Optional.",
                example = "WhatsApp +86 000 0000", maxLength = 500)
        String contactDetails,

        @Size(max = 500)
        @Schema(description = "How you pay them, in words. Optional. This is a description, not a payment record.",
                example = "Bank transfer, in full, on shipping", maxLength = 500)
        String paymentNotes) {
}
