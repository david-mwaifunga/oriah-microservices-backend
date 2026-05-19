package com.oriah.checkout.client;

import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class OrderClientFallback implements OrderClient {
    @Override
    public OrderResponse place(PlaceOrderRequest body) {
        log.warn("OrderClient.place fallback fired addressId={}", body.shippingAddressId());
        return null;
    }
    @Override
    public OrderResponse pay(UUID id, AttachPaymentRequest body) {
        log.warn("OrderClient.pay fallback fired id={}", id);
        return null;
    }
}
