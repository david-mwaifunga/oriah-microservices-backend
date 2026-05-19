package com.oriah.checkout.client;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "inventory", fallback = InventoryClientFallback.class)
public interface InventoryClient {

    @GetMapping("/api/v1/inventory/{variantId}")
    InventoryView getByVariantId(@PathVariable("variantId") UUID variantId);

    record InventoryView(UUID variantId, Integer onHand, Integer reserved, Integer available) {}
}
