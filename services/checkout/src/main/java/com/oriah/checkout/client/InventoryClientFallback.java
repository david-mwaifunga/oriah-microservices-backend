package com.oriah.checkout.client;

import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class InventoryClientFallback implements InventoryClient {
    @Override
    public InventoryView getByVariantId(UUID variantId) {
        log.warn("InventoryClient.getByVariantId fallback fired for variantId={}", variantId);
        return null;
    }
}
