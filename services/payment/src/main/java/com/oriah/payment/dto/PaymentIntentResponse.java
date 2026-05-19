package com.oriah.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;

public record PaymentIntentResponse(
        UUID id,
        UUID orderId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String failureReason,
        Instant createdAt,
        Instant confirmedAt
) {
    public static PaymentIntentResponse from(PaymentIntent p) {
        return new PaymentIntentResponse(
                p.id(), p.orderId(), p.amount(), p.currency(),
                p.status(), p.failureReason(), p.createdAt(), p.confirmedAt()
        );
    }
}
