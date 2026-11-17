package com.oriah.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Why a payout is cancelled as recorded wrongly: 10 to 200 characters, written for the person who reads the books; never logged or announced. */
public record VoidPayoutRequest(
        @NotNull(message = "a reason is required") @Size(min = 10, max = 200, message = "the reason must be 10 to 200 characters") String reason) {
}
