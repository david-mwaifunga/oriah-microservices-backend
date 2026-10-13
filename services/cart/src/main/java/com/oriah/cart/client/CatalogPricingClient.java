package com.oriah.cart.client;

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
 * <h2>Why cart suddenly needs an outbound call</h2>
 *
 * {@code AddItemRequest} carries {@code unitPrice} and cart stores it verbatim,
 * so {@code POST /api/v1/carts/me/items} with {@code "unitPrice": 0.01} buys a
 * K4,500 ring for one cent -- demonstrated live against the running stack
 * before this phase began. A floor of {@code @DecimalMin("0.0")} is the only
 * thing between a shopper and any price they care to name.
 *
 * <p>Catalog already publishes the answer: {@code POST /api/v1/variants/pricing}
 * takes up to 100 variant ids and returns, per id, a status and -- only when
 * PRICED -- the price and currency. Cart is the first service to need it.
 *
 * <h2>Direct to the service, never through the gateway</h2>
 *
 * Resolved by name through Eureka ({@code http://catalog/...}), following
 * catalog's own {@code InventoryClient} precedent. Routing this through the
 * gateway instead would put every shopper's add-to-cart into ONE 20/s bucket:
 * the gateway's storefront rate limiter is keyed on the remote address
 * ({@code RateLimitConfig}), which for a service-to-service hop is cart's
 * container IP, identical for every customer in the platform.
 *
 * <h2>No fallback, and that is the point</h2>
 *
 * There is deliberately no {@code fallback} attribute, and cart sets
 * {@code spring.cloud.openfeign.circuitbreaker.enabled: false}, so Spring Cloud
 * uses {@code DefaultTargeter} and a failed call THROWS. A fallback here would
 * have to invent an answer to "what does this cost", and every available
 * default is wrong: a zero sells the item for nothing, and the last known price
 * is exactly the stale snapshot this phase exists to remove. The caller turns a
 * throw into a 503 and leaves the cart untouched.
 *
 * <p>Copying catalog's resilience4j configuration along with its interface is
 * the specific mistake to avoid: catalog sets
 * {@code openfeign.circuitbreaker.enabled: true}, which is what makes ITS
 * fallback live. Checkout's config is the precedent to follow, catalog's
 * interface is the precedent to copy.
 */
@FeignClient(name = "catalog")
public interface CatalogPricingClient {

    /**
     * Prices a batch of variants. At most 100 ids per call -- catalog enforces
     * {@code @Size(max = 100)}, which is why the cart aggregate caps lines per
     * cart at the same number: a bag that cannot be priced in one request must
     * not be constructible.
     */
    @PostMapping("/api/v1/variants/pricing")
    PricingResponse price(@RequestBody PricingRequest body);

    record PricingRequest(List<UUID> variantIds) {

        /**
         * The common case: one variant, on the add-to-cart path.
         *
         * <p>Explicitly public: a record nested in an interface is itself
         * implicitly public, but its MEMBERS are not -- only the interface's
         * own methods get that treatment.
         */
        public static PricingRequest of(UUID variantId) {
            return new PricingRequest(List.of(variantId));
        }
    }

    /**
     * Mirrors catalog's {@code VariantPricingResponse} as a cart-local type.
     *
     * <p>{@code ignoreUnknown} is declared on the record rather than left to
     * Spring Boot's mapper configuration, so tolerance of additive fields is a
     * property of this contract: a future
     * {@code spring.jackson.deserialization.fail-on-unknown-properties=true}
     * set for cart's REQUEST bodies cannot turn a new field in catalog's
     * response into a failed add-to-cart.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PricingResponse(List<Entry> results) {
    }

    /**
     * One variant's answer.
     *
     * <p>{@code status} is bound as a String, not as an enum. If catalog adds a
     * status this service has never heard of, an enum binding throws and the
     * whole response is lost -- turning a priceable bag into a 503. As a String
     * it simply fails to equal PRICED, so the line is treated as not
     * purchasable, which is the safe direction. {@link #isPriced()} is the only
     * place that comparison is made.
     *
     * <p>{@code unitPrice} and {@code currency} are null for anything that is
     * not PRICED: catalog omits them deliberately so a stale price cannot be
     * read off an UNPUBLISHED entry by accident.
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
        static final String UNPUBLISHED = "UNPUBLISHED";
        static final String UNKNOWN = "UNKNOWN";

        /**
         * Purchasable: catalog said PRICED <em>and</em> actually sent a usable
         * price. The second half is not redundant -- a PRICED entry with a null
         * or zero price would otherwise be stored as a free line, and zero is
         * catalog's "nobody has priced this yet" marker for a draft variant.
         */
        public boolean isPriced() {
            return PRICED.equals(status)
                    && unitPrice != null
                    && unitPrice.signum() > 0
                    && currency != null && !currency.isBlank();
        }
    }
}
