package com.oriah.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The order took the attempt: what is owed, which is the amount ZynlePay is asked for (never an amount the shopper sent), and the time the pieces
 * are held to.
 */
public record ProviderPaymentHoldResponse(UUID orderId, BigDecimal total, String currency, Instant holdExpiresAt) {
}
