package com.oriah.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.oriah.money.NgweeAmount;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What staff send to record money that reached Oriah (Phase 7). The method and reference, the gross and the fee, and the DATE THE MONEY ARRIVED
 * (default: now). {@code net} is worked out as gross less fee and is NEVER accepted: a request that carries it is refused, so the books cannot
 * be given a figure that disagrees with the two they come from. A reference is spelled however it was typed and stored in one spelling; cash
 * needs none. Money older than 72 hours needs the authority to back-date and a reason.
 *
 * @param fee      what the provider or bank took, default nothing
 * @param currency default the shop's (ZMW); any other is refused
 */
public record ConfirmPaymentRequest(
        @NotNull(message = "orderId is required") UUID orderId,
        @NotBlank(message = "method is required (CASH, MTN, AIRTEL, ZAMTEL or BANK_TRANSFER)") String method,
        @Size(max = 100, message = "reference is at most 100 characters") String reference,
        @NotNull(message = "gross is required") @DecimalMin(value = "0.01", message = "gross must be above zero")
        @DecimalMax(value = "9999999999.99", message = "gross is too large") @NgweeAmount BigDecimal gross,
        @DecimalMin(value = "0.00", message = "fee cannot be negative") @DecimalMax(value = "9999999999.99", message = "fee is too large")
        @NgweeAmount BigDecimal fee,
        @Size(min = 3, max = 3, message = "currency is three letters") @Pattern(regexp = "^[A-Z]{3}$", message = "currency is three capital letters") String currency,
        Instant arrivedAt,
        @Size(max = 200, message = "backdateReason is at most 200 characters") String backdateReason,
        @Null(message = "net is worked out (gross less fee) and is never sent") @NgweeAmount BigDecimal net) {
}
