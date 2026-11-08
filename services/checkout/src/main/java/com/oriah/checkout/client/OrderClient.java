package com.oriah.checkout.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "order", fallback = OrderClientFallback.class)
public interface OrderClient {

    @PostMapping("/api/v1/orders")
    OrderResponse place(@RequestBody PlaceOrderRequest body);

    @PostMapping("/api/v1/orders/{id}/pay")
    OrderResponse pay(@PathVariable("id") UUID id, @RequestBody AttachPaymentRequest body);

    record PlaceOrderRequest(UUID shippingAddressId, String currency, List<LineItem> items,
                             String deliveryOption, String quoteDigest) {}
    record LineItem(UUID variantId, String title, Integer quantity, BigDecimal unitPrice) {}
    record AttachPaymentRequest(UUID paymentIntentId) {}
    record OrderResponse(UUID id, String keycloakId, String status, BigDecimal subtotal, BigDecimal total, String currency, UUID paymentIntentId) {}
}
