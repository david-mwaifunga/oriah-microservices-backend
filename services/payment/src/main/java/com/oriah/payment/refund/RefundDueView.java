package com.oriah.payment.refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A refund that is owed, as the owner reads it: what is owed and since when, who bears the sending charge, where it stands, and how the money first came in
 * (so it can be sent back the same way). {@code originalReference} is shown here and is in no event.
 *
 * @param status OWED, IN_QUESTION (the money record was cancelled after it paid the sale: say whether the money arrived), PAID or CLOSED
 * @param check  the owner's word on a debt that was in question; absent when none was needed or given
 * @param payout the payout that paid it; absent while it is not paid
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundDueView(UUID id, String kind, UUID orderId, UUID confirmationId, BigDecimal owedGross, String currency, String feeBearer, Instant owedSince,
                            String status, Check check, Payout payout, String originalMethod, String originalReference, BigDecimal originalFee,
                            Instant originalArrivedAt) {

    public record Check(boolean moneyArrived, Instant checkedAt) {
    }

    public record Payout(UUID id, Instant paidAt, String method, BigDecimal customerFee, BigDecimal net, BigDecimal sendCharge) {
    }
}
