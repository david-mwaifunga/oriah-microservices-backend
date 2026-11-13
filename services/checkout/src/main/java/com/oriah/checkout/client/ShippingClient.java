package com.oriah.checkout.client;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Shipping, as checkout asks it (Phase 7): what delivery to ONE town by ONE option costs, for the payer the shopper chose. Shipping is the single place
 * that works the amount out (the free-delivery threshold, the courier the recipient pays); checkout never computes it, and neither does order.
 *
 * <p>No {@code fallback}: checkout sets {@code spring.cloud.openfeign.circuitbreaker.enabled: false}, so a failing client throws, and there is no
 * defensible default answer to "what does delivery cost". The caller turns a throw into a transient failure before anything is ordered.
 */
@FeignClient(name = "shipping")
public interface ShippingClient {

    @PostMapping("/api/v1/shipping/quote")
    QuoteReply quote(@RequestBody QuoteAsk body);

    /**
     * @param townId     the town the parcel goes to; absent for self-collect
     * @param payer      ORIAH_COLLECTS or RECIPIENT_PAYS; absent takes the one the price offers
     * @param goodsTotal the goods AFTER discount: what a free-delivery threshold is judged on
     * @param currency   what {@code goodsTotal} is denominated in. Shipping prices in one currency and refuses another rather than inventing an
     *                   exchange rate; checkout checks the answer again on its own side
     */
    record QuoteAsk(UUID townId, String optionCode, String payer, BigDecimal goodsTotal, String currency) {
    }

    /** {@code amount} is what goes on the order; {@code payer} is the one shipping resolved, which the quote must carry. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuoteReply(UUID townId, String townName, String optionCode, String optionName, String payer, BigDecimal amount, String currency,
                      BigDecimal listAmount, boolean freeByThreshold, Integer etaDaysMin, Integer etaDaysMax) {
    }
}
