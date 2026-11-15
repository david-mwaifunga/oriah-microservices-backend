package com.oriah.order.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Asks catalog what a piece cost, and what promotion applied, AT AN INSTANT (Phase 9): the staff twin of the storefront's price read. A sale recorded by staff
 * is priced at the moment it happened (now for a live sale; the typed true date for a paper sale keyed later), and a piece that is on the shelf but not
 * published on the website can still be sold by phone, so this read ignores today's publication state.
 *
 * <h2>The person's own token, never order's machine token</h2>
 *
 * {@code POST /api/v1/admin/variants/pricing-as-of} is catalog's internal door (the gateway has no route for it) and answers SALES_AGENT. Order forwards the
 * signed-in person's own token ({@link com.oriah.order.config.FeignTokenForwarder}), so catalog sees who is asking and no machine role is widened. It depends on
 * the call running on the request thread, which it does (a sale is recorded on it, outside any transaction).
 *
 * <h2>No fallback, and no today's price</h2>
 *
 * A piece with no price at that instant ({@code NO_PRICE_AT_DATE}) is refused by the caller: never priced at zero, never at today's price. A failed call
 * throws and the caller answers 503 with nothing saved.
 *
 * <p>Nothing in this answer is a cost: a list price, a discount per piece, the promotion and the net price. Sales staff read no landed cost or margin (Q29).
 */
@FeignClient(name = "catalog", contextId = "catalogAsOf", configuration = com.oriah.order.config.FeignTokenForwarder.class)
public interface CatalogAsOfClient {

    @PostMapping("/api/v1/admin/variants/pricing-as-of")
    AsOfResponse priceAsOf(@RequestBody AsOfAsk body);

    /** @param at the moment of the sale: it must have happened (catalog allows 30 seconds of clock skew) */
    record AsOfAsk(Instant at, List<UUID> variantIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AsOfResponse(Instant at, List<AsOfEntry> results) {
    }

    /**
     * One variant's answer. {@code status} is bound as a String (a status this build has never heard of is simply not PRICED). The price fields are null for
     * anything that is not PRICED.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AsOfEntry(UUID variantId, String status, UUID productId, String productTitle, String variantTitle, BigDecimal unitPrice, String currency,
                     UUID priceId, BigDecimal discountPerPiece, UUID promotionId, BigDecimal netUnitPrice) {

        public static final String PRICED = "PRICED";
        public static final String NO_PRICE_AT_DATE = "NO_PRICE_AT_DATE";

        /** The entry as order's line pricing reads it. */
        public CatalogPricingClient.Entry asPricingEntry() {
            return new CatalogPricingClient.Entry(variantId, status, productId, productTitle, variantTitle, unitPrice, currency, discountPerPiece, promotionId,
                    netUnitPrice);
        }
    }
}
