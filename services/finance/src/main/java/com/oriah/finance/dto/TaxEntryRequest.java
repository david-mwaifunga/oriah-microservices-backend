package com.oriah.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.oriah.money.NgweeAmount;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Tax paid to ZRA, typed in: which tax (the closed list: turnover tax), the month it is FOR (year-month: it is booked there, not on the day it was paid), the amount and the day
 * it was paid. Duty, surtax and import VAT are refused: they are paid at clearance and reach profit through the cost of goods.
 */
@Schema(name = "TaxEntryRequest")
public record TaxEntryRequest(
        @NotBlank(message = "taxType is required") @Schema(example = "TURNOVER_TAX") String taxType,
        @NotBlank(message = "forMonth is required") @Schema(description = "The month the tax is for, year-month", example = "2026-09") String forMonth,
        @NotNull(message = "amount is required") @DecimalMin(value = "0.01", message = "amount must be more than K0.00") @DecimalMax(value = "9999999999.99", message = "amount is too large")
        @NgweeAmount @Schema(example = "350.00") BigDecimal amount,
        @NotNull(message = "paidOn is required") @Schema(description = "The day it was paid (not in the future)", example = "2026-10-20") LocalDate paidOn,
        @Size(max = 40, message = "reference is at most 40 characters") @Schema(description = "ZRA's own reference for the payment, if it has one", types = {"string", "null"}) String reference,
        @Size(min = 5, max = 200, message = "reason is 5 to 200 characters") @Schema(description = "Why, when the month was already reported", types = {"string", "null"}) String reason) {
}
