package com.oriah.procurement.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Placing an order with ONE supplier: which supplier, when, and one line per item. */
@Schema(name = "CreatePurchaseOrderRequest")
public record CreatePurchaseOrderRequest(

        @NotNull(message = "supplierId is required")
        UUID supplierId,

        @Schema(description = "The day the order was placed; today when left out; never in the future",
                types = {"string", "null"})
        LocalDate placedOn,

        @Size(max = 500, message = "note must be at most 500 characters")
        @Schema(types = {"string", "null"})
        String note,

        @NotEmpty(message = "an order needs at least one line")
        @Size(max = 100, message = "an order holds at most 100 lines")
        @Valid
        List<PurchaseOrderLineRequest> lines) {
}
