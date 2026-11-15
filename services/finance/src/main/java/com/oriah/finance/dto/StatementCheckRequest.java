package com.oriah.finance.dto;

import java.math.BigDecimal;

import com.oriah.money.NgweeAmount;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What a provider's own statement says it charged in fees for a month. */
@Schema(name = "StatementCheckRequest")
public record StatementCheckRequest(
        @NotBlank(message = "month is required") @Schema(description = "The statement's month, year-month", example = "2026-09") String month,
        @NotBlank(message = "method is required") @Pattern(regexp = "[A-Z_]{2,20}", message = "method is a plain code such as MTN, AIRTEL, ZAMTEL or BANK") @Schema(example = "MTN") String method,
        @NotNull(message = "statementFees is required") @DecimalMin(value = "0.00", message = "statementFees is not below K0.00") @DecimalMax(value = "9999999999.99", message = "statementFees is too large")
        @NgweeAmount @Schema(example = "180.50") BigDecimal statementFees,
        @Size(max = 200, message = "note is at most 200 characters") @Schema(types = {"string", "null"}) String note) {
}
