package com.oriah.checkout.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Catalog's prices, read by the orchestrator that does the charging.
 *
 * <h2>Why checkout prices the bag when cart already did</h2>
 *
 * Not redundancy. {@code confirm} used to read {@code myCart.subtotal()} and
 * charge it, which made cart the authority on what a card is debited. Cart is
 * the right authority for what it SHOWS a shopper; it must not be the only
 * thing standing between a bug -- or a compromise -- in one service and the
 * money. The service that charges derives the amount first-hand.
 *
 * <p>It also removes a disagreement that cart's own fix created. Cart excludes
 * a line it cannot sell from its subtotal while still listing the item, so an
 * order placed from {@code items()} and charged from {@code subtotal()} would
 * ship that line FREE. Pricing here means a line catalog will not price is
 * refused outright: nothing ordered, nothing charged.
 *
 * <h2>Deliberately a copy of cart's client, not a shared module</h2>
 *
 * Every service in this platform owns its own records for the contracts it
 * consumes ({@code CartClient}, {@code OrderClient}, {@code PaymentClient}
 * here all mirror another service's DTOs). A shared pricing module would
 * couple catalog's release to checkout's and cart's, which is the coupling
 * these clients exist to avoid.
 *
 * <p>No {@code fallback}, matching the other five clients here: checkout sets
 * {@code spring.cloud.openfeign.circuitbreaker.enabled: false}, so Spring
 * Cloud uses {@code DefaultTargeter} and a failure throws. There is no
 * defensible default answer to "what does this cost" -- the caller turns the
 * throw into a transient failure before anything is ordered or charged.
 */
@FeignClient(name = "catalog")
public interface CatalogPricingClient {

    @PostMapping("/api/v1/variants/pricing")
    PricingResponse price(@RequestBody PricingRequest body);

    record PricingRequest(List<UUID> variantIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PricingResponse(List<Entry> results) {}

    /**
     * @param status bound as a String rather than an enum: a status this build
     *               has never heard of must not fail deserialisation and take a
     *               whole checkout down. As a String it simply fails to equal
     *               PRICED, so the bag is refused -- the safe direction.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(
            UUID variantId,
            String status,
            UUID productId,
            String productTitle,
            String variantTitle,
            BigDecimal unitPrice,
            String currency
    ) {

        /**
         * Sellable at a usable price. The price checks are not redundant with
         * the status: a PRICED entry carrying zero or null would otherwise be
         * charged as free, and zero is catalog's "nobody has priced this yet"
         * marker for a draft variant.
         */
        public boolean isPriced() {
            return "PRICED".equals(status)
                    && unitPrice != null
                    && unitPrice.signum() > 0
                    && currency != null && !currency.isBlank();
        }
    }
}
