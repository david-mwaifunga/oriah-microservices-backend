package com.oriah.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A shopper's claim to have paid: how, and the reference the provider or bank showed. Not money: staff confirm money, or reject the claim.
 *
 * @param method    MTN, AIRTEL, ZAMTEL or BANK_TRANSFER (cash is paid in person, never claimed online)
 * @param reference as it was typed; spaces and case do not matter (it is kept in one spelling), and it is 3 to 64 letters, digits and . _ - /
 */
public record SubmitPaymentRequest(
        @NotBlank @Size(min = 1, max = 32) String method,
        @NotBlank @Size(min = 1, max = 100) String reference
) {
}
