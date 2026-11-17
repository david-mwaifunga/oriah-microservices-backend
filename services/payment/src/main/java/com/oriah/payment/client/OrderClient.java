package com.oriah.payment.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.oriah.payment.config.FeignTokenForwarder;

/**
 * Payment's one call to order (Phase 13): before ZynlePay is asked for any money, the order is asked to take the attempt.
 *
 * <p>The call carries the SHOPPER'S OWN token ({@link FeignTokenForwarder}), so order answers as that shopper: an order that is not theirs is the
 * same 404 as one that does not exist. Order's answer is what is owed, which is the amount ZynlePay is then asked for; payment never takes an
 * amount from a shopper and never reads an order any other way.
 */
@FeignClient(name = "order", configuration = FeignTokenForwarder.class)
public interface OrderClient {

    @PostMapping("/api/v1/orders/{id}/provider-payment")
    Taken takeProviderPayment(@PathVariable("id") UUID orderId, @RequestBody Take body);

    /**
     * @param supersedes an earlier attempt for the order that is finished in payment's own records; the order lets it go for this one even if the
     *                   event saying it failed has not arrived yet. Absent when there is none.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Take(UUID providerPaymentId, UUID supersedes) {
    }

    /** The order took the attempt: what is owed, and the time its pieces are held to. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Taken(UUID orderId, BigDecimal total, String currency, Instant holdExpiresAt) {
    }
}
