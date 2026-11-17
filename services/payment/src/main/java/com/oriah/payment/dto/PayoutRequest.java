package com.oriah.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.oriah.money.NgweeAmount;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Money paid back to a customer. Exactly one of {@code refundDueId} and {@code heldMoneyId} says what it pays; the amount is that debt's own and is never sent.
 *
 * @param method         how it was sent: CASH, MTN, AIRTEL, ZAMTEL or BANK_TRANSFER
 * @param reference      the provider's reference for the payout; every method but cash needs one, and one reference pays out once
 * @param customerFee    how much of the provider's charge the customer bore (it comes off what they receive); never more than {@code sendCharge}; K0.00 when Oriah
 *                       bears the charge
 * @param sendCharge     what the provider charged Oriah to send the money; K0.00 for cash
 * @param paidAt         when the money went out; now when absent. Never before it was owed; more than 72 hours back needs BACKDATE_SALE and a reason
 * @param backdateReason why it is recorded late (5 to 200 characters)
 */
public record PayoutRequest(
        UUID refundDueId,
        UUID heldMoneyId,
        @NotBlank(message = "method is required") String method,
        @Size(max = 80, message = "the reference is too long") String reference,
        @NotNull(message = "customerFee is required: type 0.00 when the customer bears none of the charge")
        @DecimalMin(value = "0.00", message = "customerFee cannot be negative") @NgweeAmount BigDecimal customerFee,
        @NotNull(message = "sendCharge is required: type 0.00 when sending cost nothing")
        @DecimalMin(value = "0.00", message = "sendCharge cannot be negative") @NgweeAmount BigDecimal sendCharge,
        Instant paidAt,
        @Size(max = 200, message = "the reason is at most 200 characters") String backdateReason,
        @Schema(hidden = true) @NgweeAmount BigDecimal gross,
        @Schema(hidden = true) @NgweeAmount BigDecimal net) {
}
