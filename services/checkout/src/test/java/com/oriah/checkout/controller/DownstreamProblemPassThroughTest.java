package com.oriah.checkout.controller;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.oriah.checkout.client.CartClient;
import com.oriah.checkout.client.CatalogPricingClient;
import com.oriah.checkout.client.CustomerClient;
import com.oriah.checkout.client.OrderClient;
import com.oriah.checkout.client.ShippingClient;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a shopper whose delivery or whose bag a downstream service refuses
 * actually receives from checkout (Phase 7).
 *
 * <p>Shipping and order refuse with problem+json answers, and some of them are
 * the shopper's to act on: choose a town the shop has priced
 * ({@code town-not-offered}), another courier payer ({@code payer-not-offered}),
 * take a piece out of the bag that inventory has none of
 * ({@code insufficient-stock}, naming every short line). Checkout used to turn
 * every downstream 4xx into a generic 500 -- "the shop is broken". It now
 * passes on, under the downstream's OWN problem type and with its words and its
 * extra members ({@code shortages}), exactly the refusals it knows: shipping's
 * {@code town-not-offered}, {@code payer-not-offered} and {@code town-not-found}
 * and order's list of the shopper's own. Anything else -- a type checkout has
 * never heard of, a body that is no problem at all, another host's type, a
 * refusal that retired with the old zone and country model such as
 * {@code outside-delivery-area} -- is checkout's own bug and stays a generic
 * 500 that names nothing internal.
 *
 * <p>Here the real CheckoutService and the real error advice run; only the
 * Feign clients are stubbed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class DownstreamProblemPassThroughTest {

    private static final String PROBLEM_BASE = "https://oriah.dev/problems/";
    private static final UUID ADDRESS = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TOWN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BOX = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID VARIANT = UUID.fromString("55555555-5555-5555-5555-555555555555");
    /**
     * A well-formed digest that no quote has: enough where the refusal comes before the digest is compared (shipping is asked before it is), so the
     * request passes validation and reaches shipping.
     */
    private static final String ANY_DIGEST = "0".repeat(64);
    /** The generic answer for a failure that is ours: it names nothing about the downstream. */
    private static final String GENERIC_500 = PROBLEM_BASE + "internal-server-error";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @MockitoBean private CartClient cart;
    @MockitoBean private CatalogPricingClient catalog;
    @MockitoBean private CustomerClient customer;
    @MockitoBean private ShippingClient shipping;
    @MockitoBean private OrderClient order;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void aBagForALusakaDeliveryThatEverythingAccepts() {
        breakers.getAllCircuitBreakers().forEach(b -> b.reset());
        doReturn(new CartClient.CartView(UUID.randomUUID(), new BigDecimal("4500.00"),
                List.of(new CartClient.CartItem(UUID.randomUUID(), VARIANT, "Aurelia", 1,
                        new BigDecimal("4500.00"), new BigDecimal("4500.00")))))
                .when(cart).getMyCart();
        doReturn(new CatalogPricingClient.PricingResponse(List.of(new CatalogPricingClient.Entry(
                VARIANT, "PRICED", UUID.randomUUID(), "Aurelia", "Aurelia 18k",
                new BigDecimal("4500.00"), "ZMW"))))
                .when(catalog).price(any());
        doReturn(List.of(new CustomerClient.Address(ADDRESS, "ZM"))).when(customer).myAddresses();
        // Shipping as it answers when it offers the delivery: OWN_DELIVERY to Lusaka at K50, Oriah collecting it.
        doReturn(new ShippingClient.QuoteReply(TOWN, "Lusaka", "OWN_DELIVERY", "Our own delivery", "ORIAH_COLLECTS",
                new BigDecimal("50.00"), "ZMW", new BigDecimal("50.00"), false, 1, 2))
                .when(shipping).quote(any());
        // Order as it answers when it places and holds the bag: the quote's total, K4,550.00.
        doReturn(new OrderClient.OrderResponse(ORDER_ID, "kc-alice", "CREATED", new BigDecimal("4500.00"), new BigDecimal("50.00"),
                new BigDecimal("0.00"), new BigDecimal("4550.00"), "ZMW", TOWN, "ORIAH_COLLECTS", Instant.parse("2026-11-13T10:10:00Z")))
                .when(order).place(any(), any());
    }

    // ------------------------------------------------------------ the requests, and what answers them

    /** The refusal a downstream service sends: this status and this problem+json body, as Feign raises it. */
    private static FeignException refusal(String service, String path, int status, String body) {
        Request request = Request.create(Request.HttpMethod.POST, "http://" + service + path,
                Map.of(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus(service + "Client#call",
                Response.builder().status(status).reason("refused").request(request)
                        .headers(Map.of("Content-Type", List.of("application/problem+json")))
                        .body(body.getBytes(StandardCharsets.UTF_8)).build());
    }

    private static FeignException shippingRefuses(int status, String body) {
        return refusal("shipping", "/api/v1/shipping/quote", status, body);
    }

    private static FeignException orderRefuses(int status, String body) {
        return refusal("order", "/api/v1/orders", status, body);
    }

    /** A problem+json body of one of Oriah's own types. */
    private static String problem(String code, int status, String detail) {
        return "{\"type\":\"" + PROBLEM_BASE + code + "\",\"title\":\"x\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}";
    }

    private static String choice() {
        // (the recipient is sent too: a preview ignores it, and a confirm needs it)
        return "\"shippingAddressId\":\"" + ADDRESS + "\",\"deliveryOption\":\"OWN_DELIVERY\",\"deliveryTownId\":\"" + TOWN
                + "\",\"recipientName\":\"Mulenga Chanda\",\"recipientPhone\":\"0971234567\"";
    }

    private ResultActions post(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject("kc-alice")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions preview() throws Exception {
        return post("/api/v1/checkout/preview", "{" + choice() + "}");
    }

    private ResultActions confirm(String digest) throws Exception {
        return post("/api/v1/checkout/confirm", "{" + choice() + ",\"quoteDigest\":\"" + digest + "\"}");
    }

    /** The digest a preview of this delivery hands the shopper -- what a real confirm would send back. */
    private String digestOfAPreview() throws Exception {
        return com.jayway.jsonpath.JsonPath.read(preview().andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.quoteDigest");
    }

    // ------------------------------------------------------------ a throttled or cut-off bag read is "busy", never an empty bag (H1)

    @Autowired
    private io.github.resilience4j.ratelimiter.RateLimiterRegistry limiters;

    @Test
    @DisplayName("preview while the cart read is throttled: 503 with Retry-After, not a 200 with an empty bag")
    void aThrottledPreviewIsServiceUnavailableNotAnEmptyBag() throws Exception {
        // Every permit of this second is taken, as a burst from one account takes them: the read is refused before cart is asked anything.
        limiters.rateLimiter("cart").drainPermissions();
        try {
            preview().andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            verify(cart, never()).getMyCart();
        } finally {
            // A fresh limiter from the same settings: the next test must not inherit this second's empty bucket.
            limiters.remove("cart");
        }
    }

    @Test
    @DisplayName("preview while the cart circuit is open: 503 with Retry-After, not a 200 with an empty bag")
    void aPreviewBehindAnOpenCircuitIsServiceUnavailableNotAnEmptyBag() throws Exception {
        breakers.circuitBreaker("cart").transitionToOpenState();

        preview().andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
        org.mockito.Mockito.verify(cart, org.mockito.Mockito.never()).getMyCart();
    }

    // ------------------------------------------------------------ shipping's refusals the shopper can act on

    /** Shipping's own refusals of a choice, as it sends them: its status, its type and its words. */
    static Stream<Arguments> shippingRefusalsTheShopperCanActOn() {
        return Stream.of(
                Arguments.of(409, "town-not-offered", "Delivery to Kabwe by OWN_DELIVERY is not priced yet."),
                Arguments.of(409, "payer-not-offered", "This delivery is not offered with RECIPIENT_PAYS: it is offered with ORIAH_COLLECTS."),
                // Shipping answers a town it does not know with a 404; the shopper sees a 409 all the same.
                Arguments.of(404, "town-not-found", "There is no town " + TOWN + "."));
    }

    @ParameterizedTest(name = "{0} {1}")
    @DisplayName("preview of a delivery shipping does not offer: 409 with shipping's own type and words")
    @MethodSource("shippingRefusalsTheShopperCanActOn")
    void previewPassesOnWhatShippingDoesNotOffer(int shippingStatus, String code, String detail) throws Exception {
        doThrow(shippingRefuses(shippingStatus, problem(code, shippingStatus, detail))).when(shipping).quote(any());

        preview().andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + code))
                .andExpect(jsonPath("$.detail").value(detail))
                // Checkout's answer, not shipping's body echoed: no internal path.
                .andExpect(content().string(not(containsString("/api/v1/shipping"))));
    }

    @ParameterizedTest(name = "{0} {1}")
    @DisplayName("confirm of a delivery shipping does not offer: 409 with shipping's own type, and nothing is ordered")
    @MethodSource("shippingRefusalsTheShopperCanActOn")
    void confirmPassesOnWhatShippingDoesNotOfferAndNothingIsPlaced(int shippingStatus, String code, String detail) throws Exception {
        doThrow(shippingRefuses(shippingStatus, problem(code, shippingStatus, detail))).when(shipping).quote(any());

        confirm(ANY_DIGEST).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + code))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(content().string(not(containsString("/api/v1/shipping"))));

        verify(order, never()).place(any(), any());
        verify(cart, never()).clearMyCart();
    }

    // ------------------------------------------------------------ shipping's refusals checkout does not pass on

    /** Answers shipping can give that checkout has no words for: its own bug, so a generic 500. */
    static Stream<Arguments> shippingRefusalsCheckoutDoesNotPassOn() {
        return Stream.of(
                Arguments.of("a 409 of a type checkout has never heard of", 409,
                        problem("some-refusal-checkout-has-never-heard-of", 409, "something new")),
                Arguments.of("a 422 of a type checkout has never heard of", 422,
                        problem("some-refusal-checkout-has-never-heard-of", 422, "something new")),
                // The refusal of the retired zone-and-country model: there is no country any more, so nothing to act on.
                Arguments.of("the retired 422 outside-delivery-area", 422,
                        problem("outside-delivery-area", 422, "We deliver within Zambia only.")),
                // A 404 that is not about the town (the route is missing, say) is no town-not-found.
                Arguments.of("a 404 that is not town-not-found", 404, problem("not-found", 404, "no such route")),
                Arguments.of("a 409 of another host's type", 409,
                        "{\"type\":\"https://elsewhere.example/problems/town-not-offered\",\"status\":409,\"detail\":\"not ours\"}"),
                Arguments.of("a 409 whose body is no problem at all", 409, "<html>conflict</html>"),
                Arguments.of("a 409 with no body", 409, ""));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("preview: a shipping refusal checkout does not know is NOT passed on -- a generic 500")
    @MethodSource("shippingRefusalsCheckoutDoesNotPassOn")
    void previewDoesNotPassOnAShippingRefusalItDoesNotKnow(String what, int shippingStatus, String body) throws Exception {
        doThrow(shippingRefuses(shippingStatus, body)).when(shipping).quote(any());

        preview().andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value(GENERIC_500))
                .andExpect(content().string(not(containsString("outside-delivery-area"))))
                .andExpect(content().string(not(containsString("some-refusal"))))
                .andExpect(content().string(not(containsString("shipping"))));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("confirm: a shipping refusal checkout does not know is NOT passed on -- a generic 500, nothing ordered")
    @MethodSource("shippingRefusalsCheckoutDoesNotPassOn")
    void confirmDoesNotPassOnAShippingRefusalItDoesNotKnow(String what, int shippingStatus, String body) throws Exception {
        doThrow(shippingRefuses(shippingStatus, body)).when(shipping).quote(any());

        confirm(ANY_DIGEST).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value(GENERIC_500))
                .andExpect(content().string(not(containsString("outside-delivery-area"))))
                .andExpect(content().string(not(containsString("some-refusal"))))
                .andExpect(content().string(not(containsString("shipping"))));

        verify(order, never()).place(any(), any());
        verify(cart, never()).clearMyCart();
    }

    // ------------------------------------------------------------ order's refusals the shopper can act on

    @Test
    @DisplayName("order refusing for lack of stock is checkout's 409 insufficient-stock, every short line kept, the cart left alone")
    void ordersInsufficientStockReachesTheShopperWithItsShortages() throws Exception {
        // Inventory has no ring and no box left; order passes its refusal on
        // naming both lines, and checkout passes order's on again, so the
        // storefront can say WHICH piece is gone.
        String digest = digestOfAPreview();
        doThrow(orderRefuses(409, "{\"type\":\"" + PROBLEM_BASE + "insufficient-stock\",\"title\":\"Conflict\",\"status\":409,"
                + "\"detail\":\"not everything on the order can be held: 2 line(s) short\","
                + "\"shortages\":[{\"variantId\":\"" + VARIANT + "\",\"kind\":\"PIECE\",\"requested\":1,\"available\":0},"
                + "{\"variantId\":\"" + BOX + "\",\"kind\":\"BOX\",\"requested\":1,\"available\":0}]}"))
                .when(order).place(any(), any());

        confirm(digest).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "insufficient-stock"))
                .andExpect(jsonPath("$.detail").value("not everything on the order can be held: 2 line(s) short"))
                .andExpect(jsonPath("$.shortages", hasSize(2)))
                .andExpect(jsonPath("$.shortages[0].variantId").value(VARIANT.toString()))
                .andExpect(jsonPath("$.shortages[0].kind").value("PIECE"))
                .andExpect(jsonPath("$.shortages[0].requested").value(1))
                .andExpect(jsonPath("$.shortages[0].available").value(0))
                .andExpect(jsonPath("$.shortages[1].variantId").value(BOX.toString()))
                .andExpect(jsonPath("$.shortages[1].kind").value("BOX"))
                .andExpect(content().string(not(containsString("/api/v1/orders"))));

        verify(cart, never()).clearMyCart();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the other refusals order gives the shopper keep their own type too")
    @ValueSource(strings = {
            "hold-limit-reached", "idempotency-key-reused", "no-default-box", "not-a-sellable-product", "product-state-not-received",
            "live-order-has-another-address"})
    void anotherRefusalOfOrdersReachesTheShopperUnderItsOwnName(String code) throws Exception {
        String digest = digestOfAPreview();
        doThrow(orderRefuses(409, problem(code, 409, "order says no: " + code))).when(order).place(any(), any());

        confirm(digest).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + code))
                .andExpect(jsonPath("$.detail").value("order says no: " + code))
                .andExpect(content().string(not(containsString("/api/v1/orders"))));

        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("order finding the quote changed is checkout's 409 pricing-changed, and the cart is left alone")
    void ordersPricingChangedReachesTheShopperAsA409() throws Exception {
        // order prices everything again before saving and refuses a quote that
        // moved. That answer used to become a generic 500 here; the shopper
        // can act on it by reviewing their bag, so it is passed on by TYPE.
        String digest = digestOfAPreview();
        doThrow(orderRefuses(409, problem("pricing-changed", 409,
                "Your basket or its price changed -- please review it before paying.")))
                .when(order).place(any(), any());

        confirm(digest).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "pricing-changed"))
                .andExpect(content().string(not(containsString("/api/v1/orders"))));

        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("order finding a piece no longer sellable is checkout's 409 bag-not-purchasable, not a generic 500")
    void ordersBagNotPurchasableReachesTheShopperAsA409() throws Exception {
        // An admin unpublished a piece between checkout's pricing and order's.
        // The shopper can act on that -- remove it -- so it keeps its type.
        String digest = digestOfAPreview();
        doThrow(orderRefuses(409, problem("bag-not-purchasable", 409, "not sellable"))).when(order).place(any(), any());

        confirm(digest).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "bag-not-purchasable"));

        verify(cart, never()).clearMyCart();
    }

    // ------------------------------------------------------------ order's refusals checkout does not pass on

    /** Answers order can give that checkout has no words for: its own bug, so a generic 500. */
    static Stream<Arguments> ordersRefusalsCheckoutDoesNotPassOn() {
        return Stream.of(
                Arguments.of("a 409 of a type checkout has never heard of",
                        problem("some-refusal-checkout-has-never-heard-of", 409, "something new")),
                // The type of a refusal the shopper CAN act on, but from another host: only Oriah's own types are read.
                Arguments.of("a 409 insufficient-stock of another host's type",
                        "{\"type\":\"https://elsewhere.example/problems/insufficient-stock\",\"status\":409,\"detail\":\"not ours\","
                                + "\"shortages\":[]}"),
                Arguments.of("a 409 whose body is no problem at all", "<html>conflict</html>"),
                Arguments.of("a 409 with no body", ""));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("an order refusal checkout does not know is NOT passed on -- a generic 500, and the cart is left alone")
    @MethodSource("ordersRefusalsCheckoutDoesNotPassOn")
    void anOrderRefusalItDoesNotKnowIsAGeneric500(String what, String body) throws Exception {
        String digest = digestOfAPreview();
        doThrow(orderRefuses(409, body)).when(order).place(any(), any());

        confirm(digest).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value(GENERIC_500))
                .andExpect(content().string(not(containsString("some-refusal"))))
                .andExpect(content().string(not(containsString("insufficient-stock"))))
                .andExpect(content().string(not(containsString("/api/v1/orders"))));

        verify(cart, never()).clearMyCart();
    }
}
