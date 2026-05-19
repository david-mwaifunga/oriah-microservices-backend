package com.oriah.shipping.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RateRequest(
        @NotBlank @Size(min = 2, max = 2) String destinationCountry,
        @NotNull @DecimalMin("0.00") BigDecimal subtotal
) {}
