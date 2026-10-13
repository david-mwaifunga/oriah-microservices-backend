package com.oriah.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.oriah.cart.model.Cart;

/**
 * Sets a line's quantity outright.
 *
 * <p>Capped at {@link Cart#MAX_QUANTITY_PER_LINE} for the same reason as
 * {@link AddItemRequest}: without it the per-line cap is one PATCH away from
 * being useless, since this path sets the quantity absolutely rather than
 * incrementing it.
 */
public record UpdateItemRequest(
        @NotNull @Min(1) @Max(Cart.MAX_QUANTITY_PER_LINE) Integer quantity
) {}
