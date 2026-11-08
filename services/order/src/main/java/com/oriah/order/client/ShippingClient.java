package com.oriah.order.client;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Shipping, as order asks it: what the option the shopper chose costs
 * (MVP_PLAN Phase 0, item 4.2). Order prices delivery itself, from that option,
 * rather than trusting a figure checkout or the shopper sends.
 */
// Order's OWN machine token, not the shopper's (ShippingClientIdentity).
@FeignClient(name = "shipping", configuration = com.oriah.order.config.ShippingClientIdentity.class)
public interface ShippingClient {

    @PostMapping("/api/v1/shipping/rates")
    RateResponse rates(@RequestBody RateRequest body);

    /** {@code deliveryOption} asks for that one option; an empty answer means it is not offered. */
    record RateRequest(String destinationCountry, BigDecimal subtotal, String currency, String deliveryOption) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RateResponse(String destinationCountry, List<ShippingOption> options) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShippingOption(String code, BigDecimal cost, String currency) {}
}