package com.oriah.procurement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.oriah.money.NgweeAmount;
import com.oriah.procurement.model.CostBasis;
import com.oriah.procurement.model.CostTreatment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One cost shared across a consignment: what the courier charged, or a duty.
 *
 * <p>There is no bank or transfer charge here and there must never be one: the supplier price on
 * a purchase order already is the total that left the bank, so a line of its own would count it
 * twice. It is refused by name.
 */
@Schema(name = "CostLineRequest")
public record CostLineRequest(

        @NotBlank(message = "costType is required")
        @Schema(description = "COURIER_INBOUND, FREIGHT, INSURANCE, PORT_INCIDENTALS, INBOUND_TRANSPORT, AGENT_FEE, "
                + "DUTY, SURTAX or IMPORT_VAT", example = "COURIER_INBOUND")
        String costType,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.00", message = "amount cannot be negative")
        @NgweeAmount
        @Schema(description = "As billed, in kwacha to the ngwee. Zero is allowed: duty reads zero today", example = "6000.00")
        BigDecimal amount,

        @Schema(description = "How it is shared between the lines: UNITS, VALUE, WEIGHT or DIRECT (typed per line). "
                + "Left out: the type's default (the courier's charge by UNITS, an agent's fee by VALUE, duty, "
                + "surtax and import VAT DIRECT). INSURANCE and PORT_INCIDENTALS have no default",
                types = {"string", "null"})
        CostBasis basis,

        @Schema(description = "CAPITALIZE (rides on the goods into the unit costs; the default) or PERIOD (a bill "
                + "for the month, shown here but left out of the unit costs)", types = {"string", "null"})
        CostTreatment treatment,

        @Schema(description = "When it was incurred, if not the day of receipt. Matters for a PERIOD cost",
                types = {"string", "null"})
        LocalDate incurredOn,

        @Size(max = 200, message = "note must be at most 200 characters")
        @Schema(types = {"string", "null"})
        String note,

        @Valid
        @Schema(description = "A split a person typed: one entry for EVERY line, adding up to the amount exactly. "
                + "Needed for DIRECT with an amount above zero; an optional override otherwise. "
                + "Not allowed on a PERIOD cost", types = {"array", "null"})
        List<ShareRequest> shares) {

    /** What belongs to one line. */
    @Schema(name = "CostShareRequest")
    public record ShareRequest(

            @NotNull(message = "lineId is required")
            UUID lineId,

            @NotNull(message = "amount is required")
            @DecimalMin(value = "0.00", message = "amount cannot be negative")
            @NgweeAmount
            BigDecimal amount) {
    }
}
