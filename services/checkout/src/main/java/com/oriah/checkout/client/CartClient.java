package com.oriah.checkout.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "cart", fallback = CartClientFallback.class)
public interface CartClient {

    @GetMapping("/api/v1/carts/me")
    CartView getMyCart();

    @DeleteMapping("/api/v1/carts/me")
    void clearMyCart();

    record CartView(UUID id, BigDecimal subtotal, List<CartItem> items) {}
    record CartItem(UUID id, UUID variantId, String title, Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}
}
