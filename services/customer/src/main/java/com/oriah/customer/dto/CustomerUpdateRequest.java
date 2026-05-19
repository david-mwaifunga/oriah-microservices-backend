package com.oriah.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record CustomerUpdateRequest(
        @Email @Size(max = 255) String email,
        @Size(min = 1, max = 120) String firstName,
        @Size(min = 1, max = 120) String lastName
) {}
