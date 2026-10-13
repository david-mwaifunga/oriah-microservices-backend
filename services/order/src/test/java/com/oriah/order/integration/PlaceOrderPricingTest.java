package com.oriah.order.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.model.Order;
import com.oriah.order.repository.OrderRepository;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * order prices its own orders, and the price in the request is a CLAIM.
 *
 * <h2>The hole</h2>
 *
 * {@code POST /api/v1/orders} is ROLE_USER and
 * {@code PlaceOrderRequest.items[]} carries {@code unitPrice}, which order
 * stored verbatim -- its own javadoc said "order-service trusts the supplied
 * prices/titles ... Today this service is just the persister". cart and
 * checkout both stopped trusting a client price earlier in this phase; order
 * was the last writer that did.
 *
 * <h2>Why the claim is CHECKED rather than ignored</h2>
 *
 * Repricing and overwriting would have introduced a worse bug than it closed.
 * Today the checkout path has exactly ONE price read: checkout reprices the
 * bag, sends those prices here, and charges that same total. Had order read
 * catalog independently and overwritten, the path would have TWO reads with
 * nothing reconciling them -- so a price moving in between leaves an order
 * whose recorded total exceeds what the card paid. An invisible UNDER-CHARGE
 * on the cart path, which is worse than the direct-path hole being closed.
 *
 * <p>So a stated price that disagrees with catalog's refuses the placement.
 * That closes the window in the same change: placement runs BEFORE
 * {@code payment.createIntent} ({@code CheckoutService.java:595} then
 * {@code :601}), so a refusal costs a retry and nothing else. And the claim
 * can only ever cause a refusal -- it cannot make the price lower.
 *
 * <p>The TITLE is overwritten, not asserted. Refusing a sale because an admin
 * renamed a product would be absurd. Money is asserted; labels are replaced.
 *
 * <h2>What this does NOT close</h2>
 *
 * A shopper can still mint a 0.01 intent against a correctly-priced order and
 * have it accepted, because {@code PaymentIntentVerifier} still does not
 * compare amounts. That needs an authoritative-money marker per row so
 * pre-existing CREATED orders stay payable, and it is a separate change.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.stream.bindings.orderEvents-out-0.destination=oriah.order"
})
class PlaceOrderPricingTest {

    private static final SimpleGrantedAuthority ROLE_USER =
            new SimpleGrantedAuthority("ROLE_USER");

    private static final String ADDRESS = "11111111-2222-3333-4444-555555555555";
    private static final UUID VARIANT = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID OTHER = UUID.fromString("33333333-4444-5555-6666-777777777777");

    /** What catalog says the piece costs. The shopper does not get a vote. */
    private static final BigDecimal REAL_PRICE = new BigDecimal("4500.00");
    private static final String CATALOG_TITLE = "Aurelia 18k Gold Band / Ring Size M";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderRepository repository;

    @MockitoBean
    private CatalogPricingClient catalog;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        pricesAt(VARIANT, REAL_PRICE, "ZMW", CATALOG_TITLE);
    }

    private static RequestPostProcessor alice() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-alice"));
    }

    private void pricesAt(UUID variantId, BigDecimal price, String currency, String title) {
        doReturn(new CatalogPricingClient.PricingResponse(List.of(
                new CatalogPricingClient.Entry(variantId, "PRICED", UUID.randomUUID(),
                        "Aurelia 18k", title, price, currency))))
                .when(catalog).price(any());
    }

    private void catalogAnswers(CatalogPricingClient.PricingResponse response) {
        doReturn(response).when(catalog).price(any());
    }

    private static CatalogPricingClient.Entry entry(
            UUID variantId, String status, BigDecimal price, String currency, String title) {
        return new CatalogPricingClient.Entry(variantId, status, UUID.randomUUID(),
                "Aurelia 18k", title, price, currency);
    }

    /** One line, with whatever price and title the caller cares to claim. */
    private static String body(UUID variantId, String title, int quantity, String claimedPrice) {
        return "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\","
                + "\"items\":[{\"variantId\":\"" + variantId + "\",\"title\":\"" + title
                + "\",\"quantity\":" + quantity + ",\"unitPrice\":" + claimedPrice + "}]}";
    }

    private void refused(String body, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
        assertThat(repository.count())
                .as("a refused placement must persist nothing")
                .isZero();
    }

    // ----------------------------------------- the claim

    @Test
    @DisplayName("a one-cent claim against a 4,500 variant is refused, and nothing is persisted")
    void theOneCentClaimIsRefused() throws Exception {
        // The live exploit: POST /orders naming your own price. It used to be
        // stored verbatim and the order's total became 0.01.
        refused(body(VARIANT, "Aurelia 18k", 1, "0.01"), 409);
    }

    @Test
    @DisplayName("a claim matching catalog succeeds and stores catalog's figure")
    void aTruthfulClaimSucceeds() throws Exception {
        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VARIANT, "Aurelia 18k", 2, "4500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(9000.00))
                .andExpect(jsonPath("$.currency").value("ZMW"));
    }

    @Test
    @DisplayName("a claim differing only in SCALE succeeds -- 100.0 is not a different price from 100.00")
    void scaleIsNotAPriceDifference() throws Exception {
        pricesAt(VARIANT, new BigDecimal("100.00"), "ZMW", CATALOG_TITLE);

        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VARIANT, "x", 1, "100.0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(100.00));
    }

    @Test
    @DisplayName("a claimed currency that disagrees with catalog is refused")
    void aCurrencyClaimIsCheckedToo() throws Exception {
        // A currency mismatch is a money mismatch: 4500 of a stronger unit is
        // not 4500 of a weaker one, and comparing numbers alone accepts it.
        refused("{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"USD\","
                + "\"items\":[{\"variantId\":\"" + VARIANT + "\",\"title\":\"x\","
                + "\"quantity\":1,\"unitPrice\":4500.00}]}", 409);
    }

    @Test
    @DisplayName("the TITLE is overwritten with catalog's, not asserted against it")
    void theTitleIsOverwritten() throws Exception {
        // An admin renaming a product must not refuse a sale, and the order
        // should record the shop's name for the thing as it was sold.
        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VARIANT, "Free ring lol", 1, "4500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].title").value(CATALOG_TITLE));
    }

    // ----------------------------------------- the batch

    @Test
    @DisplayName("two lines for the SAME variant stay two lines, with both quantities")
    void duplicateVariantLinesBothSurvive() throws Exception {
        // Catalog is asked for DISTINCT ids, so the response has one entry for
        // two lines. Zipping the response back by INDEX would silently drop a
        // line and undercharge by its whole value; mapping BY ID keeps both.
        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\","
                                + "\"items\":["
                                + "{\"variantId\":\"" + VARIANT + "\",\"title\":\"a\",\"quantity\":2,\"unitPrice\":4500.00},"
                                + "{\"variantId\":\"" + VARIANT + "\",\"title\":\"b\",\"quantity\":3,\"unitPrice\":4500.00}"
                                + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.subtotal").value(22500.00));
    }

    @Test
    @DisplayName("a variant catalog will not sell refuses the whole order")
    void anUnsellableVariantRefusesEverything() throws Exception {
        catalogAnswers(new CatalogPricingClient.PricingResponse(List.of(
                entry(VARIANT, "UNPUBLISHED", null, null, CATALOG_TITLE))));

        refused(body(VARIANT, "x", 1, "4500.00"), 409);
    }

    @Test
    @DisplayName("a PRICED entry with no usable title is not sellable either")
    void aNamelessLineIsNotSellable() throws Exception {
        // order_items.title is NOT NULL and an order is the record of what was
        // sold: a nameless line is not a record. Without this the entry would
        // reach the insert and become a 500 from the column.
        catalogAnswers(new CatalogPricingClient.PricingResponse(List.of(
                entry(VARIANT, "PRICED", REAL_PRICE, "ZMW", null))));

        refused(body(VARIANT, "x", 1, "4500.00"), 409);
    }

    @Test
    @DisplayName("a line absent from catalog's answer refuses")
    void anAbsentEntryRefuses() throws Exception {
        catalogAnswers(new CatalogPricingClient.PricingResponse(List.of(
                entry(OTHER, "PRICED", REAL_PRICE, "ZMW", CATALOG_TITLE))));

        refused(body(VARIANT, "x", 1, "4500.00"), 409);
    }

    @Test
    @DisplayName("a null results list refuses rather than NPEs")
    void aNullResultsListRefuses() throws Exception {
        catalogAnswers(new CatalogPricingClient.PricingResponse(null));

        refused(body(VARIANT, "x", 1, "4500.00"), 409);
    }

    @Test
    @DisplayName("an entry with a null variantId is ignored rather than NPEing the map build")
    void aNullVariantIdIsIgnored() throws Exception {
        catalogAnswers(new CatalogPricingClient.PricingResponse(List.of(
                entry(null, "PRICED", REAL_PRICE, "ZMW", CATALOG_TITLE),
                entry(VARIANT, "PRICED", REAL_PRICE, "ZMW", CATALOG_TITLE))));

        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VARIANT, "x", 1, "4500.00")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a duplicated entry for one id takes the first, deterministically")
    void aDuplicatedEntryTakesTheFirst() throws Exception {
        // Catalog should never do this. If it does, the choice must be
        // deterministic rather than whichever the collector happened to keep --
        // checkout's own toMap uses (a, b) -> a for the same reason.
        catalogAnswers(new CatalogPricingClient.PricingResponse(List.of(
                entry(VARIANT, "PRICED", new BigDecimal("4500.00"), "ZMW", CATALOG_TITLE),
                entry(VARIANT, "PRICED", new BigDecimal("1.00"), "ZMW", "cheaper"))));

        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VARIANT, "x", 1, "4500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(4500.00));
    }

    @Test
    @DisplayName("lines in two currencies refuse, as its own condition")
    void mixedCurrenciesRefuse() throws Exception {
        catalogAnswers(new CatalogPricingClient.PricingResponse(List.of(
                entry(VARIANT, "PRICED", REAL_PRICE, "ZMW", CATALOG_TITLE),
                entry(OTHER, "PRICED", REAL_PRICE, "USD", CATALOG_TITLE))));

        refused("{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\","
                + "\"items\":["
                + "{\"variantId\":\"" + VARIANT + "\",\"title\":\"a\",\"quantity\":1,\"unitPrice\":4500.00},"
                + "{\"variantId\":\"" + OTHER + "\",\"title\":\"b\",\"quantity\":1,\"unitPrice\":4500.00}"
                + "]}", 409);
    }

    // ----------------------------------------- catalog's own currency casing

    @Test
    @DisplayName("a lower-case currency from catalog is normalised, not stored as-is")
    void catalogsCurrencyIsNormalised() throws Exception {
        // PaymentIntentVerifier compares the order's currency to the intent's
        // with equals(), so a lower-case order is one that can never be
        // settled. The claim is compared case-insensitively for the same
        // reason -- a casing difference is not a currency difference.
        catalogAnswers(new CatalogPricingClient.PricingResponse(List.of(
                entry(VARIANT, "PRICED", REAL_PRICE, "zmw", CATALOG_TITLE))));

        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VARIANT, "x", 1, "4500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("ZMW"));
    }

    // ----------------------------------------- long titles

    @Test
    @DisplayName("catalog's composed title is stored even past 255 characters")
    void aLongCatalogTitleIsStored() throws Exception {
        // order_items.title was VARCHAR(255) while catalog's composed
        // variantTitle reaches ~332 characters. cart hit this exact wall and
        // widened the column to TEXT; without the same migration this is a 500
        // from the column on a perfectly good sale.
        String longTitle = "A".repeat(300);
        catalogAnswers(new CatalogPricingClient.PricingResponse(List.of(
                entry(VARIANT, "PRICED", REAL_PRICE, "ZMW", longTitle))));

        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VARIANT, "x", 1, "4500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].title").value(longTitle));
    }

    // ----------------------------------------- catalog failing

    @Test
    @DisplayName("catalog unreachable is a 503 and persists nothing")
    void catalogUnreachableIs503() throws Exception {
        doThrow(new FeignException.ServiceUnavailable("catalog down",
                Request.create(Request.HttpMethod.POST, "/api/v1/variants/pricing",
                        java.util.Map.of(), null, new RequestTemplate()),
                null, null))
                .when(catalog).price(any());

        refused(body(VARIANT, "x", 1, "4500.00"), 503);
    }

    @Test
    @DisplayName("a catalog 4xx is OUR bug, so it is a 500 and not shopper-retryable")
    void aCatalogBadRequestIs500() throws Exception {
        // order caps the batch at 100, so catalog answering 400 means order
        // sent something malformed. Reporting that as 503 would tell a shopper
        // to retry something that will never succeed.
        doThrow(new FeignException.BadRequest("malformed batch",
                Request.create(Request.HttpMethod.POST, "/api/v1/variants/pricing",
                        java.util.Map.of(), null, new RequestTemplate()),
                null, null))
                .when(catalog).price(any());

        refused(body(VARIANT, "x", 1, "4500.00"), 500);
    }

    // ----------------------------------------- the transaction boundary

    @Test
    @DisplayName("catalog is called with NO transaction open")
    void pricingHappensOutsideTheTransaction() throws Exception {
        // OrderService's own javadoc, on verifyPaymentClaim: "It makes an
        // outbound HTTP call, so it must not run inside a transaction: holding
        // a database connection open across a network call to another service
        // is how a slow dependency becomes an exhausted connection pool."
        // Four of those calls inside @Transactional placeOrder was a blocking
        // finding against the first version of this plan.
        boolean[] called = {false};
        boolean[] insideTransaction = {false};
        doAnswer(inv -> {
            called[0] = true;
            insideTransaction[0] = TransactionSynchronizationManager.isActualTransactionActive();
            return new CatalogPricingClient.PricingResponse(List.of(
                    entry(VARIANT, "PRICED", REAL_PRICE, "ZMW", CATALOG_TITLE)));
        }).when(catalog).price(any());

        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VARIANT, "x", 1, "4500.00")))
                .andExpect(status().isCreated());

        // Asserted separately: a flag that defaults to "inside" reports a
        // connection leak when the real problem is that catalog was never
        // called, which is a different bug with a different fix.
        assertThat(called[0])
                .as("catalog was never consulted, so nothing was repriced")
                .isTrue();
        assertThat(insideTransaction[0])
                .as("the catalog call held a database connection open")
                .isFalse();
    }

    // ----------------------------------------- the aggregate

    @Test
    @DisplayName("a repriced subtotal past the column's capacity refuses, naming no figure")
    void theAggregateIsBounded() throws Exception {
        // Per-line caps bound each input; nothing bounded the sum. 100 lines x
        // 99 units x a mistyped admin price overflows orders.subtotal
        // NUMERIC(12,2), and catalog's unit_price has a floor and no ceiling.
        catalogAnswers(new CatalogPricingClient.PricingResponse(List.of(
                entry(VARIANT, "PRICED", new BigDecimal("9000000000.00"), "ZMW", CATALOG_TITLE))));

        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VARIANT, "x", 2, "9000000000.00")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("the boundary itself is accepted")
    void theAggregateBoundIsInclusive() throws Exception {
        catalogAnswers(new CatalogPricingClient.PricingResponse(List.of(
                entry(VARIANT, "PRICED", new BigDecimal("9999999999.99"), "ZMW", CATALOG_TITLE))));

        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VARIANT, "x", 1, "9999999999.99")))
                .andExpect(status().isCreated());
    }

    // ----------------------------------------- the aggregate, as the model sees it

    @Test
    @DisplayName("the stored order's own totals agree with what was repriced")
    void theStoredTotalsAgree() throws Exception {
        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VARIANT, "x", 2, "4500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].unitPrice").value(4500.00));

        // Only the aggregate off the entity: items is lazy and findAll has no
        // @EntityGraph, so touching it here throws outside the session. The
        // line price is asserted through the response, which is also where a
        // shopper would see it.
        Order stored = repository.findAll().getFirst();
        assertThat(stored.subtotal()).isEqualByComparingTo("9000.00");
        assertThat(stored.total())
                .as("total is still subtotal -- discount and shipping are a "
                        + "separate change, and PaymentIntentVerifier still "
                        + "cannot compare amounts because of it")
                .isEqualByComparingTo("9000.00");
    }
}
