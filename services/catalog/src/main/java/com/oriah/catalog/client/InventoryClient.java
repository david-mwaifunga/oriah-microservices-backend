package com.oriah.catalog.client;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
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
 */
@FeignClient(name = "inventory", fallback = InventoryClientFallback.class)
public interface InventoryClient {

    @GetMapping("/api/v1/inventory/{variantId}")
    InventoryDto getByVariantId(@PathVariable("variantId") UUID variantId);
}
