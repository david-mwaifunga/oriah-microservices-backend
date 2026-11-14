package com.oriah.checkout.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.client.discovery.simple.InstanceProperties;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.oriah.checkout.resilience.RealFeignFailures;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;

import feign.Client;
import feign.RequestInterceptor;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checkout's confirm, end to end over checkout's REAL Feign clients, against a
 * real HTTP server (WireMock) standing in for every service it calls.
 *
 * <h2>Why this exists</h2>
 *
 * What checkout retries is decided by what Feign throws, and that is easy to
 * get wrong on paper: the retry settings once listed {@code java.io.IOException},
 * which Feign never throws, so no network failure was retried at all -- and a
 * unit test with a hand-made exception passed. Here nothing is hand-made: the
 * clients are the ones Spring builds (the load balancer around Feign's
 * HttpURLConnection client), the retry decisions come from application.yaml,
 * and every failure is a real one -- a reply that never comes, a connection
 * dropped after the request went out, a port with nothing listening.
 *
 * <h2>What confirm does (Phase 7)</h2>
 *
 * Cart, catalog (the prices), customer (whose address it is, when there is
 * one), shipping's quote, then order's placement -- the one call that holds the
 * pieces and is NOT idempotent: sent twice, it can place two orders. Then the
 * cart is cleared. Checkout never calls payment: paying is the shopper's step
 * after the order is placed. So nearly everything below is about the
 * placement, and which of its failures may be repeated.
 *
 * <p>Only the waits are shortened, so a timeout costs a test three seconds
 * rather than the fifteen (twenty for order) of application.yaml; what is
 * retried and what is not is unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ConfirmOverRealFeignTest.Attempts.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        // The load balancer reads the instance list afresh, so a test can point
        // one service at a closed port and put it back afterwards.
        "spring.cloud.loadbalancer.cache.enabled=false",
        // Shortened, but not too short: on a busy machine (a full build, other
        // builds beside it) WireMock's first answers can take most of a second.
        // At 1 s for every call, that alone turned ordinary answers into
        // timeouts -- the first test's cart call -- and tests went red for a
        // reason that is not checkout's. So 3 s here, and a test that needs a
        // timeout makes the server wait 4 s.
        "spring.cloud.openfeign.client.config.default.connectTimeout=1000",
        "spring.cloud.openfeign.client.config.default.readTimeout=3000",
        // Order has its OWN entry in application.yaml (20 s to answer a
        // placement), which wins over default for order's client -- so the 3 s
        // above would not reach it, and a placement made to wait 4 s would be
        // waited for and answered. Shortened to the same 3 s; its connect
        // timeout is the default's, as in application.yaml.
        "spring.cloud.openfeign.client.config.order.readTimeout=3000",
        "resilience4j.retry.configs.default.wait-duration=10ms"
})
class ConfirmOverRealFeignTest {

    private static final UUID VARIANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BOX = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ADDRESS = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID TOWN = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String PRICE = "4500.00";
    /** What delivery to {@link #TOWN} by OWN_DELIVERY costs in the delivered tests; Oriah collects it, so it is in the total. */
    private static final String DELIVERY = "50.00";
    private static final String PROBLEMS = "application/problem+json";

    /** Every service checkout calls, all answered by this one server. */
    private static final List<String> SERVICES = List.of("cart", "catalog", "customer", "shipping", "order");

    private static final WireMockServer DOWNSTREAM = new WireMockServer(options().dynamicPort());

    @BeforeAll
    static void start() {
        DOWNSTREAM.start();
    }

    @AfterAll
    static void stop() {
        DOWNSTREAM.stop();
    }

    @DynamicPropertySource
    static void everyServiceIsTheFakeServer(DynamicPropertyRegistry r) {
        // Through service discovery, as in production -- not a configured url,
        // which would bypass the load balancer and its "no instance" answer.
        for (String service : SERVICES) {
            r.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri", DOWNSTREAM::baseUrl);
        }
    }

    /**
     * Counts the calls each Feign client makes. Feign's own retrying is off
     * (Spring Cloud's Retryer.NEVER_RETRY), so every Resilience4j attempt is
     * one call -- including one whose connection is refused, which never
     * reaches a server that could count it.
     */
    @TestConfiguration
    static class Attempts {
        static final Map<String, AtomicInteger> BY_CLIENT = new ConcurrentHashMap<>();

        @Bean
        RequestInterceptor countAttempts() {
            return template -> BY_CLIENT.computeIfAbsent(template.feignTarget().name(), k -> new AtomicInteger())
                    .incrementAndGet();
        }

        static int of(String client) {
            AtomicInteger n = BY_CLIENT.get(client);
            return n == null ? 0 : n.get();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Client feignClient;

    @Autowired
    private SimpleDiscoveryProperties discovery;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private Map<String, List<InstanceProperties>> instancesAsConfigured;

    @BeforeEach
    void aShopperWithOnePieceInTheBag() {
        instancesAsConfigured = Map.copyOf(discovery.getInstances());
        breakers.getAllCircuitBreakers().forEach(b -> b.reset());
        Attempts.BY_CLIENT.clear();
        DOWNSTREAM.resetAll();

        DOWNSTREAM.stubFor(get(urlEqualTo("/api/v1/carts/me")).willReturn(okJson("""
                {"id":"%s","subtotal":%s,"items":[{"id":"%s","variantId":"%s","title":"Aurelia",
                 "quantity":1,"unitPrice":%s,"lineTotal":%s}]}
                """.formatted(UUID.randomUUID(), PRICE, UUID.randomUUID(), VARIANT, PRICE, PRICE))));
        DOWNSTREAM.stubFor(delete(urlEqualTo("/api/v1/carts/me")).willReturn(aResponse().withStatus(204)));
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/variants/pricing")).willReturn(okJson("""
                {"results":[{"variantId":"%s","status":"PRICED","productId":"%s","productTitle":"Aurelia",
                 "variantTitle":"Aurelia 18k","unitPrice":%s,"currency":"ZMW"}]}
                """.formatted(VARIANT, UUID.randomUUID(), PRICE))));
        DOWNSTREAM.stubFor(get(urlEqualTo("/api/v1/customers/me/addresses")).willReturn(okJson("""
                [{"id":"%s","country":"ZM","label":"Home"}]
                """.formatted(ADDRESS))));
        // Shipping and order as they answer a self-collect bag: nothing to pay for delivery, nothing in a town.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/shipping/quote")).willReturn(okJson(selfCollectQuote())));
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/orders")).willReturn(placed(selfCollectOrder())));
    }

    @AfterEach
    void putEveryServiceBack() {
        discovery.getInstances().clear();
        discovery.getInstances().putAll(instancesAsConfigured);
    }

    // ------------------------------------------------------------ what the stubs say

    /** Shipping's answer for self-collect: {@code ShippingClient.QuoteReply}, with no town and no payer. */
    private static String selfCollectQuote() {
        return """
                {"townId":null,"townName":null,"optionCode":"SELF_COLLECT","optionName":"Self-collect","payer":null,
                 "amount":0.00,"currency":"ZMW","listAmount":0.00,"freeByThreshold":false,"etaDaysMin":null,"etaDaysMax":null}
                """;
    }

    /** Shipping's answer for OWN_DELIVERY to {@link #TOWN}, paid by Oriah (so it is in the total). */
    private static String deliveredQuote() {
        return """
                {"townId":"%s","townName":"Lusaka","optionCode":"OWN_DELIVERY","optionName":"Our own delivery",
                 "payer":"ORIAH_COLLECTS","amount":%s,"currency":"ZMW","listAmount":%s,"freeByThreshold":false,
                 "etaDaysMin":1,"etaDaysMax":2}
                """.formatted(TOWN, DELIVERY, DELIVERY);
    }

    /** What order answers once it has placed and held the self-collect bag: {@code OrderClient.OrderResponse}. */
    private static String selfCollectOrder() {
        return """
                {"id":"%s","keycloakId":"kc-alice","status":"CREATED","subtotal":%s,"shippingAmount":0.00,"discountAmount":0.00,
                 "total":%s,"currency":"ZMW","deliveryTownId":null,"deliveryPayer":null,"holdExpiresAt":"2026-11-13T10:10:00Z"}
                """.formatted(ORDER_ID, PRICE, PRICE);
    }

    /** What order answers once it has placed and held the delivered bag: the total carries the delivery. */
    private static String deliveredOrder() {
        return """
                {"id":"%s","keycloakId":"kc-alice","status":"CREATED","subtotal":%s,"shippingAmount":%s,"discountAmount":0.00,
                 "total":4550.00,"currency":"ZMW","deliveryTownId":"%s","deliveryPayer":"ORIAH_COLLECTS","holdExpiresAt":"2026-11-13T10:10:00Z"}
                """.formatted(ORDER_ID, PRICE, DELIVERY, TOWN);
    }

    /** A 201 with this order, as order answers a placement. */
    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder placed(String order) {
        return aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(order);
    }

    /** A refusal as a service sends it: the problem+json body, with its status. */
    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder refusal(int status, String problemJson) {
        return aResponse().withStatus(status).withHeader("Content-Type", PROBLEMS).withBody(problemJson);
    }

    /** The digest preview shows for this bag, self-collected. */
    private static String selfCollectDigest() {
        return QuoteDigest.of(new Quote(List.of(new QuoteLine(VARIANT, 1, new BigDecimal(PRICE), BigDecimal.ZERO)),
                "ZMW", "SELF_COLLECT", BigDecimal.ZERO));
    }

    /** The digest preview shows for this bag delivered to {@link #TOWN} by OWN_DELIVERY, Oriah paying the courier. */
    private static String deliveredDigest() {
        return QuoteDigest.of(new Quote(List.of(new QuoteLine(VARIANT, 1, new BigDecimal(PRICE), BigDecimal.ZERO)),
                "ZMW", "OWN_DELIVERY", new BigDecimal(DELIVERY), TOWN, Quote.ORIAH_COLLECTS));
    }

    private ResultActions confirm() throws Exception {
        return confirm("idem-" + UUID.randomUUID());
    }

    private ResultActions confirm(String idempotencyKey) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/checkout/confirm")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject("kc-alice")))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"deliveryOption":"SELF_COLLECT","quoteDigest":"%s"}
                        """.formatted(selfCollectDigest())));
    }

    /** A confirm for OWN_DELIVERY to the shopper's own address in {@link #TOWN}, with the digest the preview showed. */
    private ResultActions confirmDelivered(String idempotencyKey) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/checkout/confirm")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject("kc-alice")))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"shippingAddressId":"%s","deliveryOption":"OWN_DELIVERY","deliveryTownId":"%s",
                         "payer":"ORIAH_COLLECTS","quoteDigest":"%s"}
                        """.formatted(ADDRESS, TOWN, deliveredDigest())));
    }

    /** Points {@code service} at a port with nothing listening on it. */
    private void nothingListensFor(String service) {
        InstanceProperties closed = new InstanceProperties();
        closed.setServiceId(service);
        closed.setUri(java.net.URI.create("http://localhost:" + RealFeignFailures.closedPort()));
        discovery.getInstances().put(service, List.of(closed));
    }

    /** The cart is cleared only once the order is placed: a failed placement leaves the shopper's bag as it was. */
    private void theCartWasNotCleared() {
        DOWNSTREAM.verify(0, deleteRequestedFor(urlEqualTo("/api/v1/carts/me")));
    }

    // ------------------------------------------------------------ what the client is

    @Test
    @DisplayName("checkout's Feign client is the load balancer around Feign's own HttpURLConnection client")
    void theClientIsTheLoadBalancerAroundHttpUrlConnection() {
        // What each network failure looks like depends on this client. No
        // Apache or OkHttp client is on the classpath, and Spring Cloud's
        // loadbalancer retry is off (spring-retry is absent), so the only
        // retrying is Resilience4j's.
        assertThat(feignClient).isInstanceOf(FeignBlockingLoadBalancerClient.class);
        assertThat(((FeignBlockingLoadBalancerClient) feignClient).getDelegate().getClass())
                .isEqualTo(Client.Default.class);
    }

    // ------------------------------------------------------------ the happy paths

    @Test
    @DisplayName("the happy path: shipping quoted, the order placed with the shopper's key and digest, and the cart cleared")
    void theHappyPath() throws Exception {
        confirm("idem-the-happy-path").andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.orderStatus").value("CREATED"))
                .andExpect(jsonPath("$.total").value(4500.0))
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.holdExpiresAt").isNotEmpty());

        // The shopper's OWN token goes to every downstream call that acts for them (order scopes the key, the caps and ownership by its subject, customer
        // scopes "whose address" by it): the Feign interceptor must carry it, or each of them answers 401.
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/shipping/quote"))
                .withHeader("Authorization", equalTo("Bearer token"))
                .withRequestBody(matchingJsonPath("$.optionCode", equalTo("SELF_COLLECT")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("ZMW"))));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders"))
                .withHeader("Authorization", equalTo("Bearer token"))
                // The browser's key goes to order verbatim: it is what makes the shopper's own repeat safe.
                .withHeader("Idempotency-Key", equalTo("idem-the-happy-path"))
                .withRequestBody(matchingJsonPath("$.quoteDigest", equalTo(selfCollectDigest()))));
        DOWNSTREAM.verify(1, deleteRequestedFor(urlEqualTo("/api/v1/carts/me")).withHeader("Authorization", equalTo("Bearer token")));
    }

    @Test
    @DisplayName("a delivered bag: the address is checked, and the town, the payer and the digest reach order")
    void aDeliveredBagIsPlacedWithItsTownItsPayerAndItsDigest() throws Exception {
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/shipping/quote")).willReturn(okJson(deliveredQuote())));
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/orders")).willReturn(placed(deliveredOrder())));

        confirmDelivered("idem-delivered").andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.total").value(4550.0));

        DOWNSTREAM.verify(1, getRequestedFor(urlEqualTo("/api/v1/customers/me/addresses")));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/shipping/quote"))
                .withRequestBody(matchingJsonPath("$.optionCode", equalTo("OWN_DELIVERY")))
                .withRequestBody(matchingJsonPath("$.townId", equalTo(TOWN.toString()))));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders"))
                .withHeader("Idempotency-Key", equalTo("idem-delivered"))
                .withRequestBody(matchingJsonPath("$.deliveryOption", equalTo("OWN_DELIVERY")))
                .withRequestBody(matchingJsonPath("$.deliveryTownId", equalTo(TOWN.toString())))
                .withRequestBody(matchingJsonPath("$.payer", equalTo("ORIAH_COLLECTS")))
                .withRequestBody(matchingJsonPath("$.shippingAddressId", equalTo(ADDRESS.toString())))
                .withRequestBody(matchingJsonPath("$.quoteDigest", equalTo(deliveredDigest()))));
        DOWNSTREAM.verify(1, deleteRequestedFor(urlEqualTo("/api/v1/carts/me")));
    }

    // -------------------------------------------- order.place: a POST that is not idempotent

    @Test
    @DisplayName("order.place timing out is NOT retried: order saw exactly one request")
    void anOrderPlacementThatTimesOutIsNotRetried() throws Exception {
        // The request reached order; only the reply is late. Order may have
        // saved the order, so a second attempt could save a second one.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/orders"))
                .willReturn(placed(selfCollectOrder()).withFixedDelay(4_000)));

        confirm().andExpect(status().isServiceUnavailable());

        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders")));
        theCartWasNotCleared();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("order.place dropped after the request went out is NOT retried: order saw exactly one request")
    @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
    void anOrderPlacementDroppedAfterSendingIsNotRetried(Fault fault) throws Exception {
        // Order had the request, so the order may have been saved: a reset and
        // a connection closed without a word are both "may have arrived".
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/orders"))
                .willReturn(aResponse().withFault(fault)));

        confirm().andExpect(status().isServiceUnavailable());

        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders")));
        theCartWasNotCleared();
    }

    @Test
    @DisplayName("order.place against a refused connection IS retried: three attempts, nothing reached order")
    void anOrderPlacementThatCouldNotConnectIsRetried() throws Exception {
        nothingListensFor("order");

        confirm().andExpect(status().isServiceUnavailable());

        assertThat(Attempts.of("order"))
                .as("attempts at order.place against a refused connection -- the request never left "
                        + "checkout, so trying again cannot make a second order")
                .isEqualTo(3);
        DOWNSTREAM.verify(0, postRequestedFor(urlEqualTo("/api/v1/orders")));
        theCartWasNotCleared();
    }

    // ------------------------------------- an answer that asks for later is not repeated

    @Test
    @DisplayName("order.place answered 503 with Retry-After is NOT repeated: one request, checkout's own 503")
    void anOrderPlacementThatAsksForLaterIsNotRepeated() throws Exception {
        // Order answers 503 with Retry-After when customer, Keycloak, shipping
        // or inventory let it down -- after waiting on them, up to 17.5 s.
        // Asking again 0.5 s later would wait all of that again, twice more,
        // and lean on order while it says it is struggling.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/orders")).willReturn(aResponse()
                .withStatus(503).withHeader("Retry-After", "5")
                .withHeader("Content-Type", PROBLEMS).withBody("{\"status\":503}")));

        confirm().andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));

        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders")));
        theCartWasNotCleared();
    }

    @Test
    @DisplayName("order.place answered 503 WITHOUT Retry-After is still repeated: three requests, then checkout's 503")
    void anOrderPlacementAnswered503WithoutRetryAfterIsRepeated() throws Exception {
        // A 503 that does not say when to come back is still worth another
        // try: it means order did not do the work, and nothing asked to be
        // left alone (NetworkFailures.askedToComeBackLater). Order's own 503s
        // all carry Retry-After, so this is the answer of something else in
        // front of order -- a proxy, say. Each attempt would ask customer,
        // catalog, shipping and inventory again, up to about 61 s in all.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/orders")).willReturn(aResponse()
                .withStatus(503).withHeader("Content-Type", PROBLEMS)
                .withBody("{\"status\":503}")));

        confirm().andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));

        DOWNSTREAM.verify(3, postRequestedFor(urlEqualTo("/api/v1/orders")));
        theCartWasNotCleared();
    }

    @Test
    @DisplayName("no instance of order registered: the load balancer's 503 is retried, and nothing reached order")
    void noInstanceOfOrderIsRetried() throws Exception {
        // The load balancer answers for a service it has no address for with
        // a 503 of its own making -- nothing was sent anywhere.
        discovery.getInstances().put("order", List.of());

        confirm().andExpect(status().isServiceUnavailable());

        assertThat(Attempts.of("order")).isEqualTo(3);
        DOWNSTREAM.verify(0, postRequestedFor(urlEqualTo("/api/v1/orders")));
        theCartWasNotCleared();
    }

    // ------------------------------- a refusal the shopper can act on keeps its own name

    @Test
    @DisplayName("shipping answering 409 town-not-offered: checkout's 409 with THAT type, and order is never asked")
    void aTownShippingDoesNotOfferIsPassedOnAndOrderIsNeverAsked() throws Exception {
        // The shop has not priced delivery to this town. That is the shopper's
        // to act on (choose another town), so it keeps shipping's name and
        // words instead of becoming "something went wrong"; and a quote that
        // was refused has no price to place an order at.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/shipping/quote")).willReturn(refusal(409, """
                {"type":"https://oriah.dev/problems/town-not-offered","title":"Conflict","status":409,
                 "detail":"Delivery to Lusaka by OWN_DELIVERY is not priced yet."}
                """)));

        confirmDelivered("idem-town-not-offered").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/town-not-offered"))
                .andExpect(jsonPath("$.detail").value("Delivery to Lusaka by OWN_DELIVERY is not priced yet."));

        // A 4xx is an answer: asked once, not repeated.
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/shipping/quote")));
        DOWNSTREAM.verify(0, postRequestedFor(urlEqualTo("/api/v1/orders")));
        theCartWasNotCleared();
    }

    @Test
    @DisplayName("order answering 409 insufficient-stock: checkout's 409 with THAT type and every short line, cart kept")
    void aPieceOrderCannotHoldIsPassedOnWithItsShortages() throws Exception {
        // Inventory has no stock left for the ring, nor for its box; order
        // passes inventory's refusal on, naming both lines, and checkout
        // passes order's on again -- so the storefront can say WHICH piece
        // is gone, not just that something failed.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/orders")).willReturn(refusal(409, """
                {"type":"https://oriah.dev/problems/insufficient-stock","title":"Conflict","status":409,
                 "detail":"not everything on the order can be held: 2 line(s) short",
                 "shortages":[{"variantId":"%s","kind":"PIECE","requested":1,"available":0},
                              {"variantId":"%s","kind":"BOX","requested":1,"available":0}]}
                """.formatted(VARIANT, BOX))));

        confirm().andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/insufficient-stock"))
                .andExpect(jsonPath("$.detail").value("not everything on the order can be held: 2 line(s) short"))
                .andExpect(jsonPath("$.shortages", hasSize(2)))
                .andExpect(jsonPath("$.shortages[0].variantId").value(VARIANT.toString()))
                .andExpect(jsonPath("$.shortages[0].kind").value("PIECE"))
                .andExpect(jsonPath("$.shortages[0].requested").value(1))
                .andExpect(jsonPath("$.shortages[0].available").value(0))
                .andExpect(jsonPath("$.shortages[1].variantId").value(BOX.toString()))
                .andExpect(jsonPath("$.shortages[1].kind").value("BOX"));

        // Nothing was held, so the shopper's bag is left as it was, and the 409 is not asked again.
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders")));
        theCartWasNotCleared();
    }
}
