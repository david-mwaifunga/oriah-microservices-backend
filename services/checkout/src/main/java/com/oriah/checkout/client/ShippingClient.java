package com.oriah.checkout.client;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "shipping", fallback = ShippingClientFallback.class)
public interface ShippingClient {

    @PostMapping("/api/v1/shipping/rates")
    RateResponse rates(@RequestBody RateRequest body);

    record RateRequest(String destinationCountry, BigDecimal subtotal) {}
    record RateResponse(String destinationCountry, String zone, List<ShippingOption> options) {}
    record ShippingOption(String carrier, String service, BigDecimal cost, String currency, Integer etaDaysMin, Integer etaDaysMax) {}
}
