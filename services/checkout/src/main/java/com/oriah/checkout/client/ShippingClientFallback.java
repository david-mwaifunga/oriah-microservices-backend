package com.oriah.checkout.client;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class ShippingClientFallback implements ShippingClient {
    @Override
    public RateResponse rates(RateRequest body) {
        log.warn("ShippingClient.rates fallback fired for country={}", body.destinationCountry());
        return null;
    }
}
