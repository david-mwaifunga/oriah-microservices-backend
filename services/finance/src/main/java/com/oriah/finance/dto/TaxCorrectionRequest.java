package com.oriah.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.oriah.money.NgweeAmount;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Correcting a tax entry: a reversal plus a new entry in one step. Only what is sent changes. */
@Schema(name = "TaxCorrectionRequest")
public record TaxCorrectionRequest(
        @NotBlank(message = "reason is required") @Size(min = 5, max = 200, message = "reason is 5 to 200 characters") String reason,
        @Schema(types = {"string", "null"}) String forMonth,
        @DecimalMin(value = "0.01", message = "amount must be more than K0.00") @DecimalMax(value = "9999999999.99", message = "amount is too large") @NgweeAmount
        @Schema(types = {"number", "null"}) BigDecimal amount,
        @Schema(types = {"string", "null"}) LocalDate paidOn,
        @Size(max = 40, message = "reference is at most 40 characters") @Schema(types = {"string", "null"}) String reference) {
}
