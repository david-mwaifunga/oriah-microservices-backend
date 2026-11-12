package com.oriah.procurement.dto;

import com.oriah.procurement.model.ChargesBasis;
import com.oriah.procurement.model.ClearanceForm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The header of a DRAFT consignment, REPLACED as a whole: send every field you want kept. A field
 * left out (or blank) is cleared, except {@code estimated}, which when left out stays as it is.
 *
 * @param version the version of the consignment this change was made from. Required: a change
 *                made from a stale copy is refused (409) instead of overwriting someone else's
 */
@Schema(name = "UpdateConsignmentRequest")
public record UpdateConsignmentRequest(

        @NotNull(message = "version is required")
        @PositiveOrZero(message = "version must be 0 or more")
        Long version,

        @Size(max = 500, message = "note must be at most 500 characters")
        @Schema(types = {"string", "null"})
        String note,

        @Schema(description = "The costs are approximations. Always true for the opening consignment. "
                + "Left out: unchanged", types = {"boolean", "null"})
        Boolean estimated,

        @Schema(description = "What the forwarder's invoice says its charge covers. DUTY_PAID: duty, surtax and import "
                + "VAT are already inside it. No default", types = {"string", "null"})
        ChargesBasis chargesBasis,

        @Size(max = 120, message = "declarant must be at most 120 characters")
        @Schema(types = {"string", "null"})
        String declarant,

        @Schema(description = "The customs form the goods cleared on", types = {"string", "null"})
        ClearanceForm clearanceForm,

        @Size(max = 80, message = "zraAssessmentRef must be at most 80 characters")
        @Schema(types = {"string", "null"})
        String zraAssessmentRef,

        @Schema(description = "Whether a licensed clearing agent was used; unknown until someone says",
                types = {"boolean", "null"})
        Boolean licensedAgentUsed) {
}
