package com.oriah.catalog.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Promotion's read of what is in force, asked by catalog to price a piece.
 *
 * <h2>Anonymous on purpose, and still not like the stock badge's client</h2>
 *
 * Catalog prices anonymously for the storefront, so this read carries no token: it is promotion's {@code /internal/promotions/in-force}, a
 * list of definitions that changes nothing, which the gateway never routes. There is therefore nothing for a thread hop to drop.
 *
 * <p>It is still kept out of the circuit breaker (see {@link PromotionClientConfiguration}) and has no fallback, because the failure that
 * must never happen here is silence: a fallback answering "no promotion in force" would charge a customer the list price during a
 * promotion, and quote it to them. A promotion service that cannot answer is a 503 for the caller, never a full price.
 * {@code FeignCircuitBreakerSafetyTest} pins all of it.
 */
@FeignClient(name = "promotion", contextId = "promotion-in-force", configuration = PromotionClientConfiguration.class)
public interface PromotionClient {

    /** The promotions in force at {@code at}; none given means now. */
    @GetMapping("/internal/promotions/in-force")
    InForceReply inForce(@RequestParam(name = "at", required = false) String at);
}
