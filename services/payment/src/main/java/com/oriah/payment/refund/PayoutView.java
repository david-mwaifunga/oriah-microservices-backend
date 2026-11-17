package com.oriah.payment.refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Money that went out, as recorded: which debt it paid, what was owed ({@code gross}), what the customer bore of the sending charge ({@code customerFee}), what
 * they received ({@code net}), what the provider charged Oriah ({@code sendCharge}), how and when it went, and whether it was cancelled since.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayoutView(UUID id, String purpose, UUID refundDueId, UUID heldMoneyId, UUID orderId, BigDecimal gross, BigDecimal customerFee, BigDecimal net,
                         BigDecimal sendCharge, String feeBearer, String currency, String method, String reference, Instant paidAt, String paidBy, String paidByName,
                         Instant recordedAt, boolean voided, Instant voidedAt, String voidReason) {
}
