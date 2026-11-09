package com.oriah.checkout.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * payment's create-intent endpoint, on a Feign client of its own.
 *
 * <h2>Why not a method on {@link PaymentClient}</h2>
 *
 * Spring Cloud gives each Feign client ONE set of timeouts, looked up by the
 * client's {@code contextId} under {@code spring.cloud.openfeign.client.config}.
 * createIntent needs shorter ones than confirm: it is repeated when its reply
 * is lost (payment hands a repeated create the intent it already made), so
 * each attempt should end soon after payment's own bounded work could have --
 * see the {@code payment-create} block in application.yaml for the numbers.
 * confirm, which charges the card and is never repeated once sent, keeps the
 * longer wait. Same service ({@code name = "payment"}, so the same load
 * balancer and instances), separate settings ({@code contextId}).
 *
 * <p>The contextId is the resilience instance's name too, so one name finds
 * both its timeouts and its breaker, retry and rate limiter.
 */
@FeignClient(name = "payment", contextId = "payment-create", fallback = PaymentCreateClientFallback.class)
public interface PaymentCreateClient {

    @PostMapping("/api/v1/payments/intents")
    PaymentClient.IntentResponse createIntent(@RequestBody PaymentClient.CreateIntentRequest body);
}
