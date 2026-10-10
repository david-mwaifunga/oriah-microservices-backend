package com.oriah.checkout.client;

import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class PaymentClientFallback implements PaymentClient {
    @Override
    public IntentResponse createIntent(CreateIntentRequest body) {
        log.warn("PaymentClient.createIntent fallback fired orderId={}", body.orderId());
        return null;
    }
    @Override
    public IntentResponse confirm(UUID id, String idempotencyKey, ConfirmRequest body) {
        log.warn("PaymentClient.confirm fallback fired id={}", id);
        return null;
    }
}
