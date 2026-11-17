package com.oriah.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The owner's word on a debt in question: did the money that paid the sale ever arrive? Said once.
 *
 * @param reason what the owner looked at (the provider's statement, the till), 10 to 200 characters; kept with the debt, never logged or announced
 */
public record MoneyCheckRequest(
        @NotNull(message = "moneyArrived is required: true if the money came in, false if it never did") Boolean moneyArrived,
        @NotNull(message = "a reason is required") @Size(min = 10, max = 200, message = "the reason must be 10 to 200 characters") String reason) {
}
