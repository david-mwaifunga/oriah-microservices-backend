package com.oriah.checkout.client;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "payment", fallback = PaymentClientFallback.class)
public interface PaymentClient {

    @PostMapping("/api/v1/payments/intents")
    IntentResponse createIntent(@RequestBody CreateIntentRequest body);

    @PostMapping("/api/v1/payments/intents/{id}/confirm")
    IntentResponse confirm(@PathVariable("id") UUID id, @RequestBody ConfirmRequest body);

    record CreateIntentRequest(UUID orderId, BigDecimal amount, String currency) {}
    record ConfirmRequest(String cardNumber) {}
    record IntentResponse(UUID id, UUID orderId, BigDecimal amount, String currency, String status, String failureReason) {}
}
