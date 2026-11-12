package com.oriah.procurement.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Putting one more WHOLE purchase order into a DRAFT consignment. Both versions are required:
 * the consignment's, and the order's as it was when the person read it.
 */
@Schema(name = "AttachOrderRequest")
public record AttachOrderRequest(

        @NotNull(message = "version is required")
        @PositiveOrZero(message = "version must be 0 or more")
        Long version,

        @NotNull(message = "purchaseOrderId is required")
        UUID purchaseOrderId,

        @NotNull(message = "purchaseOrderVersion is required")
        @PositiveOrZero(message = "purchaseOrderVersion must be 0 or more")
        Long purchaseOrderVersion) {
}
