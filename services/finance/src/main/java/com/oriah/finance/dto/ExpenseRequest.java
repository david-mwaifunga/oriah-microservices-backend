package com.oriah.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.oriah.money.NgweeAmount;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An expense typed in. The heading is one of the closed list ({@code GET /expense-categories}); the amount is above K0.00 and a whole number of ngwee; the day is a Lusaka day
 * that is not in the future. Free text names nothing and no one: no phone number is accepted. A heading that needs more says so in the list (a courier fare names its order, the
 * monthly account fee its provider, a payment-charge adjustment the confirmation it adjusts and a reason).
 */
@Schema(name = "ExpenseRequest")
public record ExpenseRequest(
        @NotBlank(message = "category is required") @Schema(example = "RENT_UTILITIES") String category,
        @NotNull(message = "amount is required") @DecimalMin(value = "0.01", message = "amount must be more than K0.00") @DecimalMax(value = "9999999999.99", message = "amount is too large")
        @NgweeAmount @Schema(example = "1200.00") BigDecimal amount,
        @NotNull(message = "expenseDate is required") @Schema(description = "The day it was spent (a Lusaka day, not in the future)", example = "2026-10-15") LocalDate expenseDate,
        @NotBlank(message = "description is required") @Size(min = 3, max = 200, message = "description is 3 to 200 characters") @Schema(example = "October rent") String description,
        @Schema(description = "The order a courier fare is for", types = {"string", "null"}) UUID linkedOrderId,
        @Schema(description = "The payment confirmation a payment-charge adjustment is for", types = {"string", "null"}) UUID linkedConfirmationId,
        @Size(max = 20, message = "provider is at most 20 characters") @Schema(description = "The payment provider a monthly account fee is for", types = {"string", "null"}) String provider,
        @Size(min = 5, max = 200, message = "reason is 5 to 200 characters") @Schema(description = "Why, when the heading or a reported month needs one", types = {"string", "null"}) String reason) {
}
