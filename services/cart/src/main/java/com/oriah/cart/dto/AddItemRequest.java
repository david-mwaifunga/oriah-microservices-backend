package com.oriah.cart.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddItemRequest(
        @NotNull UUID variantId,
        @NotNull @Min(1) Integer quantity,
        @NotNull @DecimalMin("0.0") BigDecimal unitPrice,
        @NotBlank String title
) {}
