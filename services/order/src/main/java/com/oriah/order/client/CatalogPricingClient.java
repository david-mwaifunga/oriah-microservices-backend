package com.oriah.order.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Asks catalog what a variant costs, so the shopper no longer gets to say.
 *
 * <h2>Why order needs this</h2>
 *
 * {@code POST /api/v1/orders} is a ROLE_USER endpoint whose
 * {@code PlaceOrderRequest.items[]} carries {@code unitPrice}, and order stored
 * it verbatim -- its own javadoc said "order-service trusts the supplied
 * prices/titles ... Today this service is just the persister". cart and
 * checkout both stopped trusting a client price earlier in this phase; order is
 * the last writer that did.
 *
 * <h2>The price in the request becomes a CLAIM, not an instruction</h2>
 *
 * order prices every line from catalog. If the caller also stated a price and
 * it disagrees, the placement is REFUSED rather than silently repriced.
 *
 * <p>That is not belt-and-braces, it is the fix for a regression that
 * repricing would otherwise introduce. Today the checkout path has exactly ONE
 * price read: checkout reprices the bag, sends those prices here, and charges
 * that same total. If order read catalog independently and simply overwrote,
 * the path would have TWO reads with nothing reconciling them -- so a price
 * moving in between would leave an order whose recorded total exceeded what
 * the card paid. An invisible under-charge on the cart path, which is worse
 * than the direct-path hole being closed.
 *
 * <p>Refusing on disagreement closes that window in the same change, with no
 * second release: placement happens BEFORE {@code payment.createIntent}
 * ({@code CheckoutService.java:595} then {@code :601}), so a refusal costs
 * nothing but a retry. And the claim can never make the price LOWER -- a
 * caller who claims 0.01 against a 4,500.00 variant is refused, so their
 * number has no influence on what is charged beyond causing a refusal.
 *
 * <p>The TITLE is overwritten rather than asserted. Refusing a sale because an
 * admin renamed a product would be absurd, and catalog's composed
 * {@code variantTitle} is what the order should record -- the shop's name for
 * the thing, at the moment it was sold. Money is asserted; labels are
 * replaced.
 *
 * <h2>Direct to the service, never through the gateway</h2>
 *
 * Resolved by name through Eureka, following cart's precedent. The gateway's
 * storefront rate limiter is keyed on the remote address, which for a
 * service-to-service hop is order's container IP -- identical for every
 * customer, so routing this through the gateway would put every shopper's
 * order placement into one shared bucket.
 *
 * <h2>Anonymous, which is why it is the right first upstream</h2>
 *
 * Catalog's pricing endpoint is deliberately unauthenticated ("Anonymous, like
 * the rest of the storefront catalogue"), so this client needs no token. That
 * matters more than it looks: order's only outbound-auth mechanism is
 * {@link com.oriah.order.config.FeignTokenForwarder}, which reads the
 * SecurityContext and silently sends NO Authorization header when there is
 * none. Every other upstream order might want -- promotion, shipping, customer
 * -- is role-gated, and this platform has no service identity at all, so those
 * calls are only possible on a request thread carrying a shopper's JWT.
 * Catalog is the one that is not, which is why pricing can land on its own.
 *
 * <h2>No fallback, and that is the point</h2>
 *
 * There is deliberately no {@code fallback}, and order sets
 * {@code spring.cloud.openfeign.circuitbreaker.enabled: false}, so Spring
 * Cloud uses {@code DefaultTargeter} and a failed call THROWS. A fallback here
 * would have to invent an answer to "what does this cost", and every available
 * default is wrong: zero sells the item for nothing, and the last known price
 * is exactly the stale snapshot this phase exists to remove. The caller turns a
 * throw into a 503 and persists nothing.
 *
 * <p>Timeouts are explicit (1s connect / 3s read, see
 * {@code application.yaml}) because feign-core's defaults are 10s/60s and this
 * call sits inside a checkout request that is cut off at 15s -- a budget it
 * now shares with customer, order's token and shipping.
 * {@code CatalogClientTimeoutTest} asserts them on this client's own Feign
 * child context, and {@code WritePathBudgetTest} that the four still fit.
 */
@FeignClient(name = "catalog")
public interface CatalogPricingClient {

    /**
     * Prices a batch of variants. At most 100 ids per call -- catalog enforces
     * {@code @Size(max = 100)}, which is why {@code PlaceOrderRequest.items} is
     * capped at the same number: an order that cannot be priced in one request
     * must not be constructible.
     */
    @PostMapping("/api/v1/variants/pricing")
    PricingResponse price(@RequestBody PricingRequest body);

    record PricingRequest(List<UUID> variantIds) {
    }

    /**
     * Mirrors catalog's {@code VariantPricingResponse} as an order-local type.
     *
     * <p>{@code ignoreUnknown} is declared on the record rather than left to
     * mapper configuration, so tolerance of additive fields is a property of
     * this contract: a future
     * {@code fail-on-unknown-properties=true} set for order's REQUEST bodies
     * cannot turn a new field in catalog's response into a failed order.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PricingResponse(List<Entry> results) {
    }

    /**
     * One variant's answer.
     *
     * <p>{@code status} is bound as a String, not an enum. If catalog adds a
     * status this build has never heard of, an enum binding throws and the
     * whole response is lost -- turning a priceable order into a 503. As a
     * String it simply fails to equal PRICED, so the line is treated as not
     * purchasable, which is the safe direction.
     *
     * <p>{@code unitPrice} and {@code currency} are null for anything that is
     * not PRICED: catalog omits them so a stale price cannot be read off an
     * UNPUBLISHED entry by accident.
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

        static final String PRICED = "PRICED";

        /**
         * Whether this entry can be sold, which is a stronger question than
         * its status.
         *
         * <p>A PRICED entry carrying a null, zero or negative price, or no
         * currency, is not sellable whatever it calls itself -- and order
         * additionally requires a TITLE, which cart and checkout do not.
         * {@code order_items.title} is NOT NULL, and an order is the record of
         * what was sold: a nameless line is not a record. Without this, a
         * PRICED entry with a null {@code variantTitle} would reach the insert
         * and become a 500 from the column.
         */
        public boolean isSellable() {
            return PRICED.equals(status)
                    && unitPrice != null
                    && unitPrice.signum() > 0
                    && currency != null && !currency.isBlank()
                    && variantTitle != null && !variantTitle.isBlank();
        }
    }
}
