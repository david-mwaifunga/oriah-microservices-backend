package com.oriah.procurement.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Starting a consignment: either from WHOLE purchase orders ({@code purchaseOrders}), or, with
 * {@code opening = true}, as the day-one stock typed in ({@code lines}). Exactly one of the two.
 */
@Schema(name = "CreateConsignmentRequest")
public record CreateConsignmentRequest(

        @Schema(description = "true for the opening consignment (the stock the shop already held on day one); "
                + "otherwise left out or false", types = {"boolean", "null"})
        Boolean opening,

        @Size(max = 500, message = "note must be at most 500 characters")
        @Schema(types = {"string", "null"})
        String note,

        @Valid
        @Size(max = 20, message = "a consignment holds at most 20 purchase orders")
        @Schema(description = "One to 20 whole orders, each with the version it was read at. Not for the opening "
                + "consignment", types = {"array", "null"})
        List<OrderRef> purchaseOrders,

        @Valid
        @Size(max = 200, message = "a consignment holds at most 200 lines")
        @Schema(description = "The opening stock, one line per variant. Only for the opening consignment",
                types = {"array", "null"})
        List<OpeningLineRequest> lines) {

    /** An order to put in, as it was when the person read it: a stale version is refused. */
    @Schema(name = "ConsignmentOrderRef")
    public record OrderRef(

            @NotNull(message = "id is required")
            UUID id,

            @NotNull(message = "version is required")
            @PositiveOrZero(message = "version must be 0 or more")
            Long version) {
    }
}
