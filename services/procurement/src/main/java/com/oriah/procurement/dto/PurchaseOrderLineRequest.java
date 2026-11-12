package com.oriah.procurement.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.money.NgweeAmount;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One item on a purchase order as it is asked for: which variant, how many, and the
 * ALL-IN total that left the bank for them.
 *
 * <p>There is no unit price. The bank app shows one figure for the line (CLIENT_DECISIONS
 * 3.1, "the total amount shown in the bank application"), and a unit price would be a
 * division nobody made, one that does not come out even. How the total becomes unit costs
 * is the allocator's job when the goods land.
 */
@Schema(name = "PurchaseOrderLineRequest")
public record PurchaseOrderLineRequest(

        @NotNull(message = "variantId is required")
        @Schema(description = "A variant picked from the catalogue's admin list (drafts and packaging included)")
        UUID variantId,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 1_000_000, message = "quantity must be at most 1,000,000")
        // A primitive is never "required" to the generator, though a missing one is refused (it reads as 0).
        @Schema(description = "How many, 1 to 1,000,000", requiredMode = Schema.RequiredMode.REQUIRED, example = "7")
        int quantity,

        @NotNull(message = "totalPaid is required")
        @DecimalMin(value = "0.01", message = "totalPaid must be above zero")
        @NgweeAmount
        @Schema(description = "Total paid, as the bank app shows it, in kwacha to the ngwee", example = "10500.00")
        BigDecimal totalPaid) {
}
