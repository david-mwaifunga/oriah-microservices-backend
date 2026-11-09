package com.oriah.checkout.client;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The fallback named on {@link PaymentCreateClient}, as every client here names
 * one. Like the others it never runs: Spring Cloud reads the attribute only
 * when its circuit-breaker integration is on, and checkout keeps that off (see
 * application.yaml, spring.cloud.openfeign.circuitbreaker).
 */
@Component
@Slf4j
public class PaymentCreateClientFallback implements PaymentCreateClient {
    @Override
    public PaymentClient.IntentResponse createIntent(PaymentClient.CreateIntentRequest body) {
        log.warn("PaymentCreateClient.createIntent fallback fired orderId={}", body.orderId());
        return null;
    }
}
