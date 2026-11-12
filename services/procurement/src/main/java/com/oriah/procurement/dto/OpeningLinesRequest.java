package com.oriah.procurement.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Replacing every line of the opening consignment. Anything typed per line for a cost is cleared. */
@Schema(name = "OpeningLinesRequest")
public record OpeningLinesRequest(

        @NotNull(message = "version is required")
        @PositiveOrZero(message = "version must be 0 or more")
        Long version,

        @NotEmpty(message = "the opening consignment needs at least one line")
        @Size(max = 200, message = "a consignment holds at most 200 lines")
        @Valid
        List<OpeningLineRequest> lines) {
}
