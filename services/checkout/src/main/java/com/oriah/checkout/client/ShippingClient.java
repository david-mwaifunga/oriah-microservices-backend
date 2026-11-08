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

    /**
     * @param currency what {@code subtotal} is denominated in. shipping-service
     *                 publishes its rate card in one currency and refuses to
     *                 quote in another rather than inventing an exchange rate,
     *                 so it has to be told. Checkout checks the answer again on
     *                 its own side -- a service that cannot be bothered to read
     *                 this field is exactly the one that would return the wrong
     *                 currency.
     */
    record RateRequest(String destinationCountry, BigDecimal subtotal, String currency) {}
    /** No zone: shipping delivers within Zambia only, so a zone would be a type with one value. */
    record RateResponse(String destinationCountry, List<ShippingOption> options) {}
    /** {@code code} identifies the option the shopper chooses, e.g. SELF_COLLECT. */
    record ShippingOption(String code, String carrier, String service, BigDecimal cost, String currency, Integer etaDaysMin, Integer etaDaysMax) {}
}
