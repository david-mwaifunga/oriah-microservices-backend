package com.oriah.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Single-field payload for the reserve / release / confirm / restock
 * endpoints. Kept as one record (rather than four near-identical types)
 * because the wire shape is the same; the semantic difference lives in
 * which endpoint receives it.
 */
public record QuantityRequest(
        @NotNull(message = "qty must not be null")
        @Min(value = 1, message = "qty must be >= 1")
        Integer qty
) {}
