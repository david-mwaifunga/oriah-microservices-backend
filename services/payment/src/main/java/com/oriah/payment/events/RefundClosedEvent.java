package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.refund_closed} (Phase 12): a debt closed without a payout, because the owner confirmed that the money it would have returned never arrived.
 * Aggregate {@code refund_due}, occurred when it was closed. The owner's typed reason is not in it.
 */
public record RefundClosedEvent(UUID dueId, UUID orderId, UUID confirmationId, BigDecimal owedGross, String currency, String outcome, Instant closedAt,
                                Instant recordedAt) {
    public static final String TYPE = "payment.refund_closed";
    public static final String MONEY_NEVER_ARRIVED = "MONEY_NEVER_ARRIVED";
}
