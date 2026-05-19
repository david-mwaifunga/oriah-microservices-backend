package com.oriah.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddressRequest(
        @NotBlank @Size(max = 60) String label,
        @NotBlank @Size(max = 255) String line1,
        @Size(max = 255) String line2,
        @NotBlank @Size(max = 120) String city,
        @Size(max = 120) String state,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Size(min = 2, max = 2) String country,
        boolean isDefault
) {}
