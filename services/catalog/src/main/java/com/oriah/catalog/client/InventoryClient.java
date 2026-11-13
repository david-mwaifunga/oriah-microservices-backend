package com.oriah.catalog.client;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Feign client for the inventory service.
 *
 * <p>{@code @FeignClient(name = "inventory")} tells Spring Cloud OpenFeign
 * to resolve calls to {@code inventory} via Eureka. The Feign auto-config
 * wires Spring Cloud LoadBalancer behind the scenes so we get
 * client-side load balancing across however many inventory instances
 * are registered.
 *
 * <p>The {@code fallback} attribute names a Spring bean implementing
 * this same interface. When the Resilience4j circuit breaker is open or
 * a call fails, the fallback bean's method is invoked instead — catalog
 * stays up and returns {@code Availability.UNKNOWN} rather than failing.
 *
 * <h2>A 404 is an answer</h2>
 *
 * <p>Inventory keeps a stock row for a variant only once goods of it have been received (Phase 5), so "no row" is the
 * normal state of anything not yet received, and means none in stock. {@code dismiss404} hands that back as a
 * {@code ResponseEntity} with status 404 instead of throwing, which keeps it out of the breaker's failure count:
 * counted as failures, a handful of such product views would open the breaker (a window of ten calls, half failing)
 * and every product page would lose its stock badge for 30 seconds. A {@code null} answer is the fallback's: the call
 * really failed.
 */
@FeignClient(name = "inventory", fallback = InventoryClientFallback.class, dismiss404 = true)
public interface InventoryClient {

    @GetMapping("/api/v1/inventory/{variantId}")
    ResponseEntity<InventoryDto> getByVariantId(@PathVariable("variantId") UUID variantId);
}
