package com.oriah.checkout.client;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * payment's confirm endpoint -- the call that charges the card.
 *
 * <p>createIntent is on {@link PaymentCreateClient}, a client of its own, so
 * that it can have shorter timeouts. The request and reply shapes the two
 * endpoints share are declared here.
 */
@FeignClient(name = "payment", fallback = PaymentClientFallback.class)
public interface PaymentClient {

    /**
     * Charge the gateway and settle the intent.
     *
     * <p>{@code Idempotency-Key} is the caller's single protection against a
     * double charge: payment-service replays the stored response for a key it
     * has already seen and does not re-charge. The value must be the one the
     * <strong>browser</strong> sent, carried end to end -- a key minted here
     * would be fresh on every attempt and protect nothing.
     */
    @PostMapping("/api/v1/payments/intents/{id}/confirm")
    IntentResponse confirm(@PathVariable("id") UUID id,
                           @RequestHeader(name = "Idempotency-Key", required = false)
                           String idempotencyKey,
                           @RequestBody ConfirmRequest body);

    record CreateIntentRequest(UUID orderId, BigDecimal amount, String currency) {}
    record ConfirmRequest(String cardNumber) {}
    record IntentResponse(UUID id, UUID orderId, BigDecimal amount, String currency, String status, String failureReason) {}
}
