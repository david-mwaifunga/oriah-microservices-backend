package com.oriah.checkout.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConfirmRequest(
        @NotNull UUID shippingAddressId,
        @NotBlank @Size(min = 2, max = 2) String destinationCountry,
        @NotBlank String cardNumber,
        String promotionCode  // optional
) {}
