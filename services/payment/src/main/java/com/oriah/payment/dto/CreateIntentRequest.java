package com.oriah.payment.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateIntentRequest(
        @NotNull UUID orderId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @Size(min = 3, max = 3) String currency
) {}
