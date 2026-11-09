package com.oriah.order.integration;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.model.Order;
import com.oriah.order.repository.OrderRepository;

import static com.oriah.order.QuoteFixtures.ANY_DIGEST;
import static com.oriah.order.QuoteFixtures.line;
import static com.oriah.order.QuoteFixtures.selfCollect;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What {@code POST /api/v1/orders} accepts, and what it must refuse.
 *
 * <h2>The database was the only line of defence, and it answers in 500s</h2>
 *
 * {@code V1__create_orders.sql} carries {@code CHECK (quantity > 0)} and
 * {@code title VARCHAR(255)}, and {@code PlaceOrderRequest} carried no bounds
 * at all beyond {@code @NotNull}. So every malformed line reached Postgres,
 * violated a constraint or overflowed a column, and came back as
 *
 * <pre>500 An unexpected error occurred. Use the traceId to correlate...</pre>
 *
 * Verified against the running stack: {@code quantity: -5}, {@code quantity: 0},
 * {@code quantity: 2000000000} and a 5,000-character title were all 500s.
 *
 * <p>That is wrong twice over. The caller is told nothing it can act on, and
 * an ordinary bad request is recorded as the server fault an operator pages
 * on. Bounds belong in the DTO, where they produce a 400 with fieldErrors; the
 * CHECK constraint then goes back to being a backstop that never fires, and a
 * 500 from it becomes the honest answer that validation has a hole.
 *
 * <h2>The caps match cart's, deliberately</h2>
 *
 * {@code Cart.MAX_LINES} is 100 and {@code Cart.MAX_QUANTITY_PER_LINE} is 99,
 * and cart's own comment explains why the line cap is 100: catalog's pricing
 * endpoint enforces {@code @Size(max = 100)} on a batch, so a bag that cannot
 * be priced in one request must not be constructible. An order is the record
 * of a bag, so an order that could not have been a bag should not be
 * constructible either -- and once order prices from catalog itself, the same
 * batch limit applies to it directly.
 *
 * <h2>And the last hardcoded USD in the platform</h2>
 *
 * {@code Order}'s constructor read
 *
 * <pre>this.currency = currency == null || currency.isBlank() ? "USD" : currency;</pre>
 *
 * on a platform whose prices are in Kwacha. cart and catalog both hold
 * {@code DEFAULT_CURRENCY = "ZMW"}; shipping's is configuration; payment's
 * identical default was removed in the same pass. This was the last one in
 * live code.
 *
 * <p>It is replaced with a refusal rather than with ZMW. An order is the
 * record of a sale, so its currency should be the one the sale was priced in
 * -- and a default, even the right one, produces an order whose currency
 * nobody chose. Verified live before the fix: a request omitting currency
 * returned 201 with {@code "currency": "USD"}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class PlaceOrderValidationTest {

    private static final SimpleGrantedAuthority ROLE_USER =
            new SimpleGrantedAuthority("ROLE_USER");

    private static final String ADDRESS = "11111111-2222-3333-4444-555555555555";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderRepository repository;

    /**
     * Catalog, answering 100.00 ZMW -- the figure this class's bodies already
     * claim, so every bound assertion here keeps its exact meaning.
     *
     * <p>Needed because order prices from catalog now. Most tests in this class
     * never reach it: a malformed quantity, title or currency is refused by
     * Bean Validation before any pricing happens, which is the whole point of
     * having the bounds in the DTO. Only the two happy paths get this far.
     */
    @MockitoBean
    private CatalogPricingClient catalog;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /** Shipping as it is until Phase 7: self-collect at no charge, in the order's currency. */
    @MockitoBean
    private com.oriah.order.client.ShippingClient shipping;

    /**
     * customer, listing {@link #ADDRESS} as alice's: the two happy paths send
     * it, and order checks it is the caller's before pricing. The bodies this
     * class refuses never get that far -- Bean Validation answers first.
     */
    @MockitoBean
    private com.oriah.order.client.CustomerClient customer;

    @BeforeEach
    void reset() {
        org.mockito.Mockito.doReturn(java.util.List.of(
                        new com.oriah.order.client.CustomerClient.Address(UUID.fromString(ADDRESS))))
                .when(customer).myAddresses();
        org.mockito.Mockito.doAnswer(inv -> {
            com.oriah.order.client.ShippingClient.RateRequest asked = inv.getArgument(0);
            return new com.oriah.order.client.ShippingClient.RateResponse("ZM", java.util.List.of(
                    new com.oriah.order.client.ShippingClient.ShippingOption(
                            "SELF_COLLECT", new java.math.BigDecimal("0.00"), asked.currency())));
        }).when(shipping).rates(org.mockito.ArgumentMatchers.any());
        repository.deleteAll();
        doAnswer(inv -> {
            CatalogPricingClient.PricingRequest asked = inv.getArgument(0);
            return new CatalogPricingClient.PricingResponse(asked.variantIds().stream()
                    .map(id -> new CatalogPricingClient.Entry(
                            id, "PRICED", UUID.randomUUID(), "Aurelia 18k",
                            "Aurelia 18k", new java.math.BigDecimal("100.00"), "ZMW"))
                    .toList());
        }).when(catalog).price(any());
    }

    private static RequestPostProcessor alice() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-alice"));
    }

    private static final String ADDRESS_FIELD = "\"shippingAddressId\":\"" + ADDRESS + "\",";
    private static final String SELF_COLLECT_FIELD = "\"deliveryOption\":\"SELF_COLLECT\",";
    private static final String ANY_DIGEST_FIELD = "\"quoteDigest\":\"" + ANY_DIGEST + "\",";

    /**
     * One line that order will actually place: catalog's 100.00, and the
     * digest of exactly that line, as preview would have shown it.
     */
    private static String placeable(String addressField, int quantity) {
        UUID variant = UUID.randomUUID();
        return "{" + addressField + SELF_COLLECT_FIELD + "\"currency\":\"ZMW\","
                + "\"quoteDigest\":\"" + selfCollect("ZMW", line(variant, quantity, "100.00")) + "\","
                + "\"items\":[{\"variantId\":\"" + variant + "\",\"title\":\"Aurelia 18k\","
                + "\"quantity\":" + quantity + ",\"unitPrice\":100.00}]}";
    }

    /** One otherwise-valid line, with the top-level fields given verbatim. */
    private static String bodyWithFields(String fields) {
        return "{" + fields + "\"currency\":\"ZMW\",\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\","
                + "\"title\":\"Aurelia 18k\",\"quantity\":1,\"unitPrice\":100.00}]}";
    }

    /** One line, with whatever quantity the caller asked for. */
    private static String bodyWithQuantity(Object quantity) {
        return "{\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + ANY_DIGEST + "\",\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\","
                + "\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\","
                + "\"title\":\"Aurelia 18k\",\"quantity\":" + quantity + ","
                + "\"unitPrice\":100.00}]}";
    }

    private void refused(String body, String field) throws Exception {
        long before = repository.count();
        mvc.perform(post("/api/v1/orders")
                        .with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString(field))));
        // A 400 that still wrote a row would be the worst of both answers.
        assertThat(repository.count())
                .as("a refused order must not be persisted")
                .isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -5})
    @DisplayName("a non-positive quantity is a 400, not a 500 from CHECK (quantity > 0)")
    void nonPositiveQuantityIsRefused(int quantity) throws Exception {
        refused(bodyWithQuantity(quantity), "quantity");
    }

    @Test
    @DisplayName("a quantity beyond the per-line cap is a 400, not a numeric overflow")
    void absurdQuantityIsRefused() throws Exception {
        // 2,000,000,000 x 100.00 overflows NUMERIC(12,2) when the line total is
        // computed, which was a 500. The cap makes it unreachable.
        refused(bodyWithQuantity(2_000_000_000), "quantity");
    }

    @Test
    @DisplayName("the per-line cap is inclusive at 99, matching cart")
    void theQuantityCapIsInclusive() throws Exception {
        // The boundary itself, so the cap is pinned to cart's constant rather
        // than to a number someone liked.
        mvc.perform(post("/api/v1/orders")
                        .with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeable(ADDRESS_FIELD, 99)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("more lines than catalog can price in one batch is a 400")
    void tooManyLinesIsRefused() throws Exception {
        // 101 lines. catalog's pricing endpoint takes at most 100 ids, which is
        // why cart caps a bag at 100 -- an order that could not have been a bag
        // should not be constructible either.
        String lines = IntStream.range(0, 101)
                .mapToObj(i -> "{\"variantId\":\"" + UUID.randomUUID() + "\","
                        + "\"title\":\"L" + i + "\",\"quantity\":1,\"unitPrice\":1.00}")
                .collect(Collectors.joining(","));
        refused("{\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + ANY_DIGEST + "\",\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\","
                + "\"items\":[" + lines + "]}", "items");
    }

    @Test
    @DisplayName("a title longer than the column is a 400, not a 500 from the column")
    void overlongTitleIsRefused() throws Exception {
        // VARCHAR(255). cart hit this same wall and removed the field; order
        // still carries it, so until it does the same the bound has to be here.
        String title = "A".repeat(256);
        refused("{\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + ANY_DIGEST + "\",\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\","
                + "\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\","
                + "\"title\":\"" + title + "\",\"quantity\":1,\"unitPrice\":100.00}]}",
                "title");
    }

    @Test
    @DisplayName("a negative unit price is a 400, not a 500 from CHECK (unit_price >= 0)")
    void negativeUnitPriceIsRefused() throws Exception {
        refused("{\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + ANY_DIGEST + "\",\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\","
                + "\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\","
                + "\"title\":\"t\",\"quantity\":1,\"unitPrice\":-100.00}]}",
                "unitPrice");
    }

    @Test
    @DisplayName("a missing currency is refused rather than defaulted to USD")
    void missingCurrencyIsRefused() throws Exception {
        refused("{\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + ANY_DIGEST + "\",\"shippingAddressId\":\"" + ADDRESS + "\","
                + "\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\","
                + "\"title\":\"t\",\"quantity\":1,\"unitPrice\":100.00}]}",
                "currency");
    }

    @ParameterizedTest
    @ValueSource(strings = {"zmw", "Zmw", "Z-W", "12 ", "   "})
    @DisplayName("a currency that is not three upper-case letters is refused")
    void malformedCurrencyIsRefused(String currency) throws Exception {
        // Upper-case specifically: PaymentIntentVerifier compares this order's
        // currency to the intent's with equals(), so a lower-case order could
        // never be settled by an upper-case intent.
        refused("{\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + ANY_DIGEST + "\",\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"" + currency + "\","
                + "\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\","
                + "\"title\":\"t\",\"quantity\":1,\"unitPrice\":100.00}]}",
                "currency");
    }

    @Test
    @DisplayName("the Order aggregate refuses to invent a currency")
    void theAggregateRefusesABlankCurrency() {
        // The backstop behind the DTO. Whoever ends up supplying the currency
        // -- the caller today, catalog once order prices orders itself -- an
        // order that does not know what unit its money is in is not an order.
        assertThatThrownBy(() -> new Order("kc-alice", UUID.randomUUID(), null, "SELF_COLLECT", java.math.BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order("kc-alice", UUID.randomUUID(), "  ", "SELF_COLLECT", java.math.BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a well-formed order is still placed, in the currency it named")
    void theHappyPathIsUntouched() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeable(ADDRESS_FIELD, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.subtotal").value(200.00))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    // ----------------------------------------- the quote the shopper saw (item 4.4)

    @Test
    @DisplayName("the delivery option is required: order prices delivery itself, from the shopper's choice")
    void theDeliveryOptionIsRequired() throws Exception {
        refused(bodyWithFields(ADDRESS_FIELD + ANY_DIGEST_FIELD), "deliveryOption");
    }

    @ParameterizedTest
    @ValueSource(strings = {"self collect", "Self_Collect", "SELF-COLLECT", "9LIVES"})
    @DisplayName("a delivery option that is not an option code is a 400, not a 500 from the V4 check")
    void aMalformedDeliveryOptionIsRefused(String option) throws Exception {
        refused(bodyWithFields(ADDRESS_FIELD + "\"deliveryOption\":\"" + option + "\"," + ANY_DIGEST_FIELD),
                "deliveryOption");
    }

    @Test
    @DisplayName("the quote digest is required: only the quote the shopper saw can be placed")
    void theQuoteDigestIsRequired() throws Exception {
        refused(bodyWithFields(ADDRESS_FIELD + SELF_COLLECT_FIELD), "quoteDigest");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "532785FB7C7C7C121F8FFD3528D7DFFAE0DB3163403314BC51216C38CA96802C",
            "532785fb7c7c7c121f8ffd3528d7dffae0db3163403314bc51216c38ca96802",
            "532785fb7c7c7c121f8ffd3528d7dffae0db3163403314bc51216c38ca96802cc",
            "g32785fb7c7c7c121f8ffd3528d7dffae0db3163403314bc51216c38ca96802c"})
    @DisplayName("a digest that is not 64 lower-case hex characters is a 400")
    void aMalformedQuoteDigestIsRefused(String quoteDigest) throws Exception {
        refused(bodyWithFields(ADDRESS_FIELD + SELF_COLLECT_FIELD + "\"quoteDigest\":\"" + quoteDigest + "\","),
                "quoteDigest");
    }

    @Test
    @DisplayName("self-collect needs no delivery address")
    void noAddressIsNeededForSelfCollect() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeable("", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shippingAddressId").doesNotExist());
    }

    @Test
    @DisplayName("any option other than self-collect still needs somewhere to deliver to")
    void aDeliveryNeedsAnAddress() throws Exception {
        refused(bodyWithFields("\"deliveryOption\":\"OWN_DELIVERY\"," + ANY_DIGEST_FIELD), "shippingAddressId");
    }
}
