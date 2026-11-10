package com.oriah.procurement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Add a supplier: "name, country, who you deal with there, how you pay them"
 * (CLIENT_DECISIONS 4.1). Only the name and the country are required.
 *
 * <p>The limits mirror the aggregate's and the table's, so a request that is too
 * long is a {@code fieldErrors} entry instead of a database error. So is the NUL
 * character, which PostgreSQL cannot store in text. These same annotations also
 * check each field of an edit ({@code SupplierService.update}), so there is one
 * rule set. Lengths count the text as sent, before the aggregate trims it.
 */
@Schema(name = "CreateSupplierRequest")
public record CreateSupplierRequest(

        @NotBlank
        @Size(max = 120)
        @Pattern(regexp = NO_NUL, message = NO_NUL_MESSAGE)
        @Schema(description = "What the supplier is called. Unique among live suppliers, ignoring letter case.",
                example = "Example Trading Ltd", maxLength = 120)
        String name,

        @NotBlank
        @ValidCountry
        @Schema(description = "The supplier's own country, as an ISO 3166-1 alpha-2 code (stored in upper case).",
                example = "CN", minLength = 2, maxLength = 2)
        String country,

        @Size(max = 120)
        @Pattern(regexp = NO_NUL, message = NO_NUL_MESSAGE)
        @Schema(description = "Who you deal with there. Optional.", example = "A. Contact", maxLength = 120)
        String contactName,

        @Size(max = 500)
        @Pattern(regexp = NO_NUL, message = NO_NUL_MESSAGE)
        @Schema(description = "How to reach them: phone, WhatsApp, email. Optional.",
                example = "WhatsApp +86 000 0000", maxLength = 500)
        String contactDetails,

        @Size(max = 500)
        @Pattern(regexp = NO_NUL, message = NO_NUL_MESSAGE)
        @Schema(description = "How you pay them, in words. Optional. This is a description, not a payment record.",
                example = "Bank transfer, in full, on shipping", maxLength = 500)
        String paymentNotes) {

    /**
     * Any text without the NUL character. Two backslashes on purpose: javac turns a
     * single backslash-u-0-0-0-0 into the raw character before it reads the string, whereas
     * this hands the regex engine the escape and keeps the source plain.
     */
    static final String NO_NUL = "[^\\u0000]*";
    static final String NO_NUL_MESSAGE = "must not contain the NUL character";
}
