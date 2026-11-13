package com.oriah.order.client;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Shipping, as order asks it: what delivery to ONE town by ONE option costs, for the payer the shopper chose (Phase 7). Order never works out
 * a delivery price itself; the amount in shipping's answer is the amount on the order.
 */
// Order's OWN machine token, not the shopper's (ShippingClientIdentity).
@FeignClient(name = "shipping", configuration = com.oriah.order.config.ShippingClientIdentity.class)
public interface ShippingClient {

    @PostMapping("/api/v1/shipping/quote")
    QuoteReply quote(@RequestBody QuoteAsk body);

    /**
     * @param townId     the town the parcel goes to; absent for self-collect
     * @param payer      ORIAH_COLLECTS or RECIPIENT_PAYS; absent takes the one the price offers
     * @param goodsTotal the goods AFTER discount: what a free-delivery threshold is judged on
     */
    record QuoteAsk(UUID townId, String optionCode, String payer, BigDecimal goodsTotal, String currency) {
    }

    /**
     * The delivery as shipping prices it. {@code amount} is what goes on the order (K0 when the recipient pays the courier, or when the goods
     * reach the free-delivery threshold); {@code payer} is the one it resolved, which the order records.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuoteReply(UUID townId, String townName, String optionCode, String payer, BigDecimal amount, String currency, UUID priceId) {
    }
}
