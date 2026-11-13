package com.oriah.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why a confirmation is being taken back: at least ten characters once the spaces round it are gone, written for the person who reads the books. */
public record VoidConfirmationRequest(
        @NotBlank(message = "reason is required") @Size(max = 200, message = "reason is at most 200 characters") String reason) {
}
