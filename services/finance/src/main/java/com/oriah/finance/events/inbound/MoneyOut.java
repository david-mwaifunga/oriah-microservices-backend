package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code payment.paid_out} and {@code payment.payout_voided} (Phase 12), as finance reads them: money that went out (a refund of a correction, or held money returned),
 * what the provider charged Oriah to send it, how much of that the customer bore, and, for a payout taken back, when. The reference, the payee and the reason are left
 * unread on purpose.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MoneyOut(UUID payoutId, String purpose, UUID debtId, UUID orderId, UUID confirmationId, String method, BigDecimal gross, BigDecimal customerFee,
                       BigDecimal net, BigDecimal sendCharge, String currency, Instant paidAt, Instant voidedAt, Instant recordedAt) {
}
