package com.oriah.checkout.client;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class PromotionClientFallback implements PromotionClient {
    @Override
    public ApplyResponse apply(String code, ApplyRequest body) {
        log.warn("PromotionClient.apply fallback fired code={}", code);
        return null;
    }
    @Override
    public void redeem(String code) {
        log.warn("PromotionClient.redeem fallback fired code={}", code);
    }
}
