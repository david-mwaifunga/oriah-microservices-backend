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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
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
 * <p>Only the waits are shortened, so a timeout costs a test half a second
 * rather than fifteen; what is retried and what is not is unchanged.
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
        "spring.cloud.openfeign.client.config.default.connectTimeout=1000",
        "spring.cloud.openfeign.client.config.default.readTimeout=500",
        "resilience4j.retry.configs.default.wait-duration=10ms"
})
class ConfirmOverRealFeignTest {

    private static final UUID VARIANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID INTENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String PRICE = "4500.00";

    /** Every service checkout calls, all answered by this one server. */
    private static final List<String> SERVICES = List.of("cart", "catalog", "customer", "shipping", "order", "payment");

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

        /**
         * Run as each confirm request is built, before the load balancer picks
         * an address for it: lets a test change the world between createIntent
         * and confirm, as a redeploy of payment would.
         */
        static volatile Runnable beforeEachConfirm = () -> { };

        @Bean
        RequestInterceptor countAttempts() {
            return template -> {
                BY_CLIENT.computeIfAbsent(template.feignTarget().name(), k -> new AtomicInteger())
                        .incrementAndGet();
                if (template.path().endsWith("/confirm")) {
                    beforeEachConfirm.run();
                }
            };
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
        Attempts.beforeEachConfirm = () -> { };
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
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/shipping/rates")).willReturn(okJson("""
                {"destinationCountry":"ZM","options":[{"code":"SELF_COLLECT","carrier":"Oriah Jewelry",
                 "service":"Self-collect","cost":0.00,"currency":"ZMW","etaDaysMin":null,"etaDaysMax":null}]}
                """)));
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/orders")).willReturn(okJson(order("CREATED", null))));
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/payments/intents"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody(intent("PENDING"))));
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/payments/intents/" + INTENT_ID + "/confirm"))
                .willReturn(okJson(intent("SUCCEEDED"))));
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/orders/" + ORDER_ID + "/pay"))
                .willReturn(okJson(order("PAID", INTENT_ID))));
    }

    @AfterEach
    void putEveryServiceBack() {
        discovery.getInstances().clear();
        discovery.getInstances().putAll(instancesAsConfigured);
    }

    private static String order(String status, UUID intentId) {
        return """
                {"id":"%s","keycloakId":"kc-alice","status":"%s","subtotal":%s,"total":%s,"currency":"ZMW",
                 "paymentIntentId":%s}
                """.formatted(ORDER_ID, status, PRICE, PRICE, intentId == null ? "null" : "\"" + intentId + "\"");
    }

    private static String intent(String status) {
        return """
                {"id":"%s","orderId":"%s","amount":%s,"currency":"ZMW","status":"%s","failureReason":null}
                """.formatted(INTENT_ID, ORDER_ID, PRICE, status);
    }

    /** The digest preview shows for this bag, self-collected. */
    private static String quoteDigest() {
        return QuoteDigest.of(new Quote(List.of(new QuoteLine(VARIANT, 1, new BigDecimal(PRICE), BigDecimal.ZERO)),
                "ZMW", "SELF_COLLECT", BigDecimal.ZERO));
    }

    private ResultActions confirm() throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/checkout/confirm")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject("kc-alice")))
                .header("Idempotency-Key", "idem-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"deliveryOption":"SELF_COLLECT","cardNumber":"4242424242424242","quoteDigest":"%s"}
                        """.formatted(quoteDigest())));
    }

    /** Points {@code service} at a port with nothing listening on it. */
    private void nothingListensFor(String service) {
        InstanceProperties closed = new InstanceProperties();
        closed.setServiceId(service);
        closed.setUri(java.net.URI.create("http://localhost:" + RealFeignFailures.closedPort()));
        discovery.getInstances().put(service, List.of(closed));
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

    @Test
    @DisplayName("the happy path: placed, paid, and the cart cleared")
    void theHappyPath() throws Exception {
        confirm().andExpect(status().isOk());

        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders")));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders/" + ORDER_ID + "/pay")));
    }

    // -------------------------------------------- order.place: a POST that is not idempotent

    @Test
    @DisplayName("order.place timing out is NOT retried: order saw exactly one request")
    void anOrderPlacementThatTimesOutIsNotRetried() throws Exception {
        // The request reached order; only the reply is late. Order may have
        // saved the order, so a second attempt could save a second one.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/orders"))
                .willReturn(okJson(order("CREATED", null)).withFixedDelay(2_000)));

        confirm().andExpect(status().isServiceUnavailable());

        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders")));
        DOWNSTREAM.verify(0, postRequestedFor(urlEqualTo("/api/v1/payments/intents")));
    }

    @Test
    @DisplayName("order.place dropped after the request went out is NOT retried: order saw exactly one request")
    void anOrderPlacementResetAfterSendingIsNotRetried() throws Exception {
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/orders"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        confirm().andExpect(status().isServiceUnavailable());

        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders")));
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
    }

    // ------------------------------------------- payment.createIntent: idempotent, so retried

    @Test
    @DisplayName("createIntent's reply lost: the retry gets the same intent back, and the checkout completes with it")
    void aLostCreateIntentReplyIsRetriedAndTheSameIntentIsUsed() throws Exception {
        // Payment makes the intent, but its reply never reaches checkout. A
        // repeated create for the caller's own order is answered with the
        // intent already made (200, commit ffa3187), so retrying is safe --
        // and without it the shopper got a 503 while the order kept a PENDING
        // intent checkout never heard about, and their retry placed a NEW order.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/payments/intents")).inScenario("reply lost")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody(intent("PENDING")).withFixedDelay(2_000))
                .willSetStateTo("intent made"));
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/payments/intents")).inScenario("reply lost")
                .whenScenarioStateIs("intent made")
                .willReturn(okJson(intent("PENDING"))));

        confirm().andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentIntentId").value(INTENT_ID.toString()))
                .andExpect(jsonPath("$.orderStatus").value("PAID"));

        DOWNSTREAM.verify(2, postRequestedFor(urlEqualTo("/api/v1/payments/intents")));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/payments/intents/" + INTENT_ID + "/confirm")));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders")));
    }

    @Test
    @DisplayName("payment unable to make an intent (503, Retry-After) is retried, then checkout's own 503 naming payment")
    void anUnavailableCreateIntentIsRetriedThenServiceUnavailable() throws Exception {
        // Payment answers 503 with Retry-After when it cannot ask order whose
        // order this is: nothing was made. The shopper is told which service
        // -- by its name, not by the name of checkout's retry settings.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/payments/intents")).willReturn(aResponse()
                .withStatus(503).withHeader("Retry-After", "5")
                .withHeader("Content-Type", "application/problem+json").withBody("{\"status\":503}")));

        confirm().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "The payment service is temporarily unavailable. Nothing has been charged")));

        DOWNSTREAM.verify(3, postRequestedFor(urlEqualTo("/api/v1/payments/intents")));
        DOWNSTREAM.verify(0, postRequestedFor(urlPathMatching("/api/v1/payments/intents/.*/confirm")));
    }

    // ------------------------------------------- payment.confirm: the call that moves money

    @Test
    @DisplayName("confirm's reply never came: 502 'may have gone through', sent exactly once, order not marked paid")
    void aConfirmWhoseReplyNeverCameIsBadGatewayAndSentOnce() throws Exception {
        // The request to charge REACHED payment; only the answer is late. The
        // card may have been charged, so the shopper must hear "it may or may
        // not have gone through" (502) -- never the 503 "nothing has been
        // charged; retry", which would invite a second purchase. And it is not
        // sent again: confirm's retry repeats only a request that never left.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/payments/intents/" + INTENT_ID + "/confirm"))
                .willReturn(okJson(intent("SUCCEEDED")).withFixedDelay(2_000)));

        confirm().andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/payment-confirmation-failed"));

        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/payments/intents/" + INTENT_ID + "/confirm")));
        DOWNSTREAM.verify(0, postRequestedFor(urlPathMatching("/api/v1/orders/.*/pay")));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("confirm's connection dropped after the request went out: 502, sent exactly once, order not marked paid")
    @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
    void aConfirmDroppedAfterSendingIsBadGatewayAndSentOnce(Fault fault) throws Exception {
        // As above: payment had the request, so the charge may have happened.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/payments/intents/" + INTENT_ID + "/confirm"))
                .willReturn(aResponse().withFault(fault)));

        confirm().andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/payment-confirmation-failed"));

        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/payments/intents/" + INTENT_ID + "/confirm")));
        DOWNSTREAM.verify(0, postRequestedFor(urlPathMatching("/api/v1/orders/.*/pay")));
    }

    @Test
    @DisplayName("confirm answered 503 'outcome unknown': sent again, payment refuses the repeat (409), shopper gets 502")
    void anOutcomeUnknownConfirmIsSentAgainAndTheRepeatIsRefused() throws Exception {
        // Payment answers confirm 503 when its card gateway gave no answer: the
        // money MAY have been taken. Unlike every other 503 checkout sees, the
        // work may have been done. Confirm is on the default retry, which
        // repeats any 503 -- so the request to charge IS sent a second time.
        // That is safe only because of payment: before charging it records the
        // intent as CHARGING, and a repeat for an intent in that state is
        // refused 409 and charges nothing (PaymentService.confirm). The repeat
        // can never succeed. This pins checkout's half: the repeat is made, its
        // 409 ends the checkout as the 502 "may or may not have gone through",
        // and the order is never marked paid.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/payments/intents/" + INTENT_ID + "/confirm"))
                .inScenario("outcome unknown")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":503,\"detail\":\"The outcome of the charge is not known yet\"}"))
                .willSetStateTo("charging"));
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/payments/intents/" + INTENT_ID + "/confirm"))
                .inScenario("outcome unknown")
                .whenScenarioStateIs("charging")
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":409,\"detail\":\"still in progress\"}")));

        confirm().andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/payment-confirmation-failed"));

        DOWNSTREAM.verify(2, postRequestedFor(urlEqualTo("/api/v1/payments/intents/" + INTENT_ID + "/confirm")));
        DOWNSTREAM.verify(0, postRequestedFor(urlPathMatching("/api/v1/orders/.*/pay")));
    }

    @Test
    @DisplayName("confirm's breaker open: 503 with Retry-After, and payment never asked to charge")
    void anOpenConfirmBreakerIsServiceUnavailableAndNothingWasSent() throws Exception {
        // The intent is made (on payment-create's breaker); confirm's own
        // breaker is open, so the charge request never leaves checkout. That
        // is the 503 "nothing has been charged", which the storefront may
        // offer to retry -- not the 502 "it may have gone through".
        breakers.circuitBreaker("payment").transitionToOpenState();

        confirm().andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));

        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/payments/intents")));
        DOWNSTREAM.verify(0, postRequestedFor(urlPathMatching("/api/v1/payments/intents/.*/confirm")));
        DOWNSTREAM.verify(0, postRequestedFor(urlPathMatching("/api/v1/orders/.*/pay")));
    }

    @Test
    @DisplayName("payment gone from the load balancer by confirm time: 503 'nothing charged', and payment never asked")
    void noInstanceOfPaymentAtConfirmIsServiceUnavailable() throws Exception {
        // The intent is made; then payment drops out of service discovery (a
        // redeploy, say) before confirm is sent. The load balancer has no
        // address to send the charge to, so it answers a 503 of its own making
        // and nothing leaves checkout. That is the 503 "nothing has been
        // charged" -- not the 502 "it may have gone through", which sends the
        // shopper off to check their card for a charge that cannot exist.
        Attempts.beforeEachConfirm = () -> discovery.getInstances().put("payment", List.of());

        confirm().andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "The payment service is temporarily unavailable. Nothing has been charged")));

        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/v1/payments/intents")));
        DOWNSTREAM.verify(0, postRequestedFor(urlPathMatching("/api/v1/payments/intents/.*/confirm")));
        DOWNSTREAM.verify(0, postRequestedFor(urlPathMatching("/api/v1/orders/.*/pay")));
        assertThat(Attempts.of("payment"))
                .as("one create, then three confirm attempts that the load balancer answered itself -- "
                        + "retried, as a 503 is, and safe to retry because none was sent")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("payment ITSELF answering 503 in the load balancer's words is still 'may have gone through': 502")
    void aRealAnswerInTheLoadBalancersWordsIsNotTakenForIt() throws Exception {
        // The test above relies on recognising the load balancer's own 503.
        // The dangerous mistake is the other way round: taking an answer that
        // came back over the network -- so the request reached payment, which
        // may have charged -- for "nothing was sent". Only a response the load
        // balancer made up inside checkout counts, and that one has no HTTP
        // headers at all, which nothing that crossed the network lacks.
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/payments/intents/" + INTENT_ID + "/confirm"))
                .willReturn(aResponse().withStatus(503)
                        .withBody("Load balancer does not contain an instance for the service payment")));

        confirm().andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/payment-confirmation-failed"));

        DOWNSTREAM.verify(0, postRequestedFor(urlPathMatching("/api/v1/orders/.*/pay")));
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
    }
}
