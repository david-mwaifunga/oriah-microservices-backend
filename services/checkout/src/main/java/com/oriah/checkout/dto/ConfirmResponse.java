package com.oriah.checkout.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The order is PLACED and its pieces are HELD for the shopper until {@code holdExpiresAt} (ten minutes). It is not paid: the shopper pays (mobile money or a
 * bank transfer) and tells order how, and staff confirm the money. A 200 from checkout means exactly "placed and held", and nothing else.
 */
public record ConfirmResponse(
        UUID orderId,
        String orderStatus,
        BigDecimal total,
        String currency,
        Instant holdExpiresAt,
        String message
) {}
