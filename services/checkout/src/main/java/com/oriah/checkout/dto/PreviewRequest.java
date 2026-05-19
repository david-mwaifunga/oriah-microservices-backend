package com.oriah.checkout.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PreviewRequest(
        @NotBlank @Size(min = 2, max = 2) String destinationCountry,
        String promotionCode  // optional
) {}
