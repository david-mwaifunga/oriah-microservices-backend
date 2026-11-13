package com.oriah.catalog.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Inventory's cost read, asked on behalf of the person pricing a piece.
 *
 * <h2>Why this is not {@link InventoryClient}</h2>
 *
 * The stock badge's client is anonymous, wrapped in Spring's circuit breaker (which runs each call on a pool thread) and degrades
 * to a fallback: all right for decoration on a product page. This call is the opposite on every count. It needs the CALLER'S
 * token (inventory decides whether they may read cost: COST_VIEWER), so it must not run on a pool thread where the security
 * context is empty; and it must never degrade, because a fallback that answers "nothing held" would let a price below cost
 * through unseen.
 *
 * <p>So the token is an explicit parameter, read by the caller on the request thread, the circuit-breaker wrapper is switched off
 * for this client alone ({@link InventoryCostClientConfiguration}), there is no fallback, and a 404 is not "an answer" here.
 * {@code FeignCircuitBreakerSafetyTest} holds all of it.
 *
 * <p>The context id differs from the name so the two clients keep separate configuration; both reach the same service.
 */
@FeignClient(name = "inventory", contextId = "inventory-costs", configuration = InventoryCostClientConfiguration.class)
public interface InventoryCostClient {

    /** At most 100 ids, comma separated; every id is answered, one that was never received with nothing held. */
    @GetMapping("/api/v1/admin/costs/held")
    HeldCostReply held(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                       @RequestParam("variantIds") String variantIds);
}
