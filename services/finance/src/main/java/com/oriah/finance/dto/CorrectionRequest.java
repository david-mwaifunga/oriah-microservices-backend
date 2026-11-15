package com.oriah.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.oriah.money.NgweeAmount;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Correcting an expense: a reversal plus a new expense in one step. Only what is sent changes; what is left out stays as it was. Changing only the heading is re-classifying:
 * the amount and the day stay, so no month's total moves.
 */
@Schema(name = "CorrectionRequest")
public record CorrectionRequest(
        @NotBlank(message = "reason is required") @Size(min = 5, max = 200, message = "reason is 5 to 200 characters") String reason,
        @Schema(types = {"string", "null"}) String category,
        @DecimalMin(value = "0.01", message = "amount must be more than K0.00") @DecimalMax(value = "9999999999.99", message = "amount is too large") @NgweeAmount
        @Schema(types = {"number", "null"}) BigDecimal amount,
        @Schema(types = {"string", "null"}) LocalDate expenseDate,
        @Size(min = 3, max = 200, message = "description is 3 to 200 characters") @Schema(types = {"string", "null"}) String description,
        @Schema(types = {"string", "null"}) UUID linkedOrderId,
        @Schema(types = {"string", "null"}) UUID linkedConfirmationId,
        @Size(max = 20, message = "provider is at most 20 characters") @Schema(types = {"string", "null"}) String provider) {
}
