package com.oriah.checkout.client;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class CartClientFallback implements CartClient {
    @Override
    public CartView getMyCart() {
        log.warn("CartClient.getMyCart fallback fired - cart service unreachable");
        return null;
    }
    @Override
    public void clearMyCart() {
        log.warn("CartClient.clearMyCart fallback fired - cart service unreachable");
    }
}
