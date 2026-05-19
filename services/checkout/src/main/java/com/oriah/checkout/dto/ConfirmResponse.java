package com.oriah.checkout.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ConfirmResponse(
        UUID orderId,
        String orderStatus,
        UUID paymentIntentId,
        String paymentStatus,
        BigDecimal total,
        String currency,
        String message
) {}
