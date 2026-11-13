package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A confirmation order has not answered yet: money recorded that nothing has acted on. {@code stuck} is true once it has waited longer than the
 * settlement window ({@code oriah.sales.settlement-stuck-after}, five minutes), which is when someone should look: order may be down, or a
 * message went astray. {@code emitAttempts} is how many times it has been announced (it is announced again while it waits).
 */
public record PendingView(
        UUID id,
        UUID orderId,
        String method,
        String reference,
        BigDecimal gross,
        String currency,
        Instant arrivedAt,
        Instant confirmedAt,
        long ageSeconds,
        int emitAttempts,
        boolean stuck) {
}
