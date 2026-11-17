package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code payment.refund_closed} (Phase 12), as finance reads it: a debt closed without a payout, because the money it would have returned never arrived. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundClosed(UUID dueId, UUID orderId, UUID confirmationId, BigDecimal owedGross, String currency, String outcome, Instant closedAt, Instant recordedAt) {
}
