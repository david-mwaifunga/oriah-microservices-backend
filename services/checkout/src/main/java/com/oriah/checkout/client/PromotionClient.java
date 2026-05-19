package com.oriah.checkout.client;

import java.math.BigDecimal;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "promotion", fallback = PromotionClientFallback.class)
public interface PromotionClient {

    @PostMapping("/api/v1/promotions/{code}/apply")
    ApplyResponse apply(@PathVariable("code") String code, @RequestBody ApplyRequest body);

    @PostMapping("/api/v1/promotions/{code}/redeem")
    void redeem(@PathVariable("code") String code);

    record ApplyRequest(BigDecimal subtotal) {}
    record ApplyResponse(String code, BigDecimal subtotal, BigDecimal discount, BigDecimal totalAfterDiscount) {}
}
