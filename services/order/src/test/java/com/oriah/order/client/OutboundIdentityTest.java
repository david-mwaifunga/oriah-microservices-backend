package com.oriah.order.client;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.repository.OrderRepository;

import feign.RequestInterceptor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who order says it is, to each service it calls (MVP_PLAN Phase 0, item 4.2).
 *
 * <ul>
 *   <li><b>shipping</b> gets ORDER's own token -- a client-credentials token for
 *       {@code service-account-oriah-admin} -- never the shopper's. Order asks
 *       on the shop's behalf what delivery costs; a staff member's token would
 *       be refused there, since shipping admits {@code USER}.</li>
 *   <li><b>payment</b> still gets the SHOPPER's token: its "is this your
 *       payment?" check needs the shopper's identity.</li>
 *   <li><b>catalog</b>'s pricing endpoint is anonymous and gets no token.</li>
 * </ul>
 *
 * <p>A real HTTP server (WireMock) stands in for all three and for Keycloak,
 * so these assert the Authorization header each one actually received -- one
 * value, not two appended. Feign's interceptors APPEND, so a global forwarder
 * running beside the machine-token interceptor would send both.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
class OutboundIdentityTest {

    private static final String TOKEN_PATH = "/realms/oriah/protocol/openid-connect/token";
    private static final UUID VARIANT = UUID.fromString("22222222-3333-4444-5555-666666666666");
    /** The digest of the one quote these tests place: VARIANT once at 100.00, self-collect. */
    private static final String SEEN = com.oriah.order.QuoteFixtures.selfCollect("ZMW",
            com.oriah.order.QuoteFixtures.line(VARIANT, 1, "100.00"));

    private static final WireMockServer WM = new WireMockServer(options().dynamicPort());

    @BeforeAll
    static void start() {
        WM.start();
    }

    @AfterAll
    static void stop() {
        WM.stop();
    }

    @DynamicPropertySource
    static void downstreamsAreTheFakeServer(DynamicPropertyRegistry r) {
        // A configured URL bypasses service discovery for that client.
        r.add("spring.cloud.openfeign.client.config.shipping.url", WM::baseUrl);
        r.add("spring.cloud.openfeign.client.config.payment.url", WM::baseUrl);
        r.add("spring.cloud.openfeign.client.config.catalog.url", WM::baseUrl);
        r.add("spring.security.oauth2.client.provider.keycloak.token-uri", () -> WM.baseUrl() + TOKEN_PATH);
        r.add("spring.security.oauth2.client.registration.oriah-admin.client-secret", () -> "test-secret");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
        r.add("spring.cloud.stream.bindings.orderEvents-out-0.destination", () -> "oriah.order");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private OAuth2AuthorizedClientService authorizedClients;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void freshStubs() {
        WM.resetAll();
        orders.deleteAll();
        // Forget any cached machine token, so each test sees the token request.
        authorizedClients.removeAuthorizedClient("oriah-admin", "order-service");
        WM.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(okJson(
                "{\"access_token\":\"machine-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        WM.stubFor(post(urlEqualTo("/api/v1/variants/pricing")).willReturn(okJson(
                "{\"results\":[{\"variantId\":\"" + VARIANT + "\",\"status\":\"PRICED\",\"productId\":\""
                        + UUID.randomUUID() + "\",\"productTitle\":\"Aurelia\",\"variantTitle\":\"Aurelia 18k\","
                        + "\"unitPrice\":100.00,\"currency\":\"ZMW\"}]}")));
        WM.stubFor(post(urlEqualTo("/api/v1/shipping/rates")).willReturn(okJson(
                "{\"destinationCountry\":\"ZM\",\"options\":[{\"code\":\"SELF_COLLECT\",\"cost\":0.00,\"currency\":\"ZMW\"}]}")));
    }

    private static RequestPostProcessor shopper(String subject, String token) {
        return jwt().jwt(j -> j.tokenValue(token).subject(subject))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private String place(RequestPostProcessor who) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .with(who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"11111111-2222-3333-4444-555555555555\","
                                + "\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + SEEN + "\","
                                + "\"items\":[{\"variantId\":\"" + VARIANT + "\",\"title\":\"x\",\"quantity\":1,"
                                + "\"unitPrice\":100.00}]}"))
                .andReturn().getResponse().getContentAsString();
    }

    private static java.util.List<String> authorizationOf(LoggedRequest request) {
        var header = request.getHeaders().getHeader("Authorization");
        return header.isPresent() ? header.values() : java.util.List.of();
    }

    @Test
    @DisplayName("shipping receives order's own machine token -- once, and never the shopper's")
    void shippingSeesTheServiceAccountNotTheShopper() throws Exception {
        place(shopper("kc-alice", "shopper-token"));

        var calls = WM.findAll(postRequestedFor(urlEqualTo("/api/v1/shipping/rates")));
        assertThat(calls).hasSize(1);
        assertThat(authorizationOf(calls.get(0))).containsExactly("Bearer machine-token");
    }

    @Test
    @DisplayName("the machine token is obtained with client credentials, as oriah-admin")
    void theTokenIsObtainedWithClientCredentials() throws Exception {
        place(shopper("kc-alice", "shopper-token"));

        var tokenCalls = WM.findAll(postRequestedFor(urlEqualTo(TOKEN_PATH)));
        assertThat(tokenCalls).hasSize(1);
        assertThat(tokenCalls.get(0).getBodyAsString()).contains("grant_type=client_credentials");
        assertThat(authorizationOf(tokenCalls.get(0))).containsExactly("Basic "
                + Base64.getEncoder().encodeToString("oriah-admin:test-secret".getBytes()));
    }

    @Test
    @DisplayName("one machine token serves every shopper: the principal is order, not the caller")
    void oneMachineTokenServesEveryShopper() throws Exception {
        place(shopper("kc-alice", "alice-token"));
        place(shopper("kc-maya", "maya-token"));

        // A per-shopper principal would fetch -- and keep -- a token per shopper.
        WM.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        WM.verify(2, postRequestedFor(urlEqualTo("/api/v1/shipping/rates")));
    }

    @Test
    @DisplayName("catalog's anonymous pricing endpoint receives no token at all")
    void catalogReceivesNoShopperToken() throws Exception {
        place(shopper("kc-alice", "shopper-token"));

        var calls = WM.findAll(postRequestedFor(urlEqualTo("/api/v1/variants/pricing")));
        assertThat(calls).hasSize(1);
        assertThat(authorizationOf(calls.get(0))).isEmpty();
    }

    @Test
    @DisplayName("payment still receives the SHOPPER's token: its owner check needs who is paying")
    void paymentStillSeesTheShopper() throws Exception {
        String placed = place(shopper("kc-alice", "shopper-token"));
        String orderId = com.jayway.jsonpath.JsonPath.read(placed, "$.id");
        UUID intent = UUID.randomUUID();
        WM.stubFor(get(urlEqualTo("/api/v1/payments/intents/" + intent)).willReturn(okJson(
                "{\"id\":\"" + intent + "\",\"orderId\":\"" + orderId + "\",\"amount\":100.00,"
                        + "\"currency\":\"ZMW\",\"status\":\"SUCCEEDED\"}")));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/orders/" + orderId + "/pay")
                        .with(shopper("kc-alice", "shopper-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentIntentId\":\"" + intent + "\"}"))
                .andExpect(status().isOk());

        var calls = WM.findAll(getRequestedFor(urlPathMatching("/api/v1/payments/intents/.*")));
        assertThat(calls).hasSize(1);
        assertThat(authorizationOf(calls.get(0))).containsExactly("Bearer shopper-token");
    }

    @Test
    @DisplayName("no Feign interceptor is global: each client carries only its own")
    void noRequestInterceptorIsGlobal() {
        assertThat(context.getBeansOfType(RequestInterceptor.class)).isEmpty();
    }

    @Test
    @DisplayName("the token server down: 503, and nothing is saved")
    void aTokenEndpointOutageIsServiceUnavailableAndNothingIsSaved() throws Exception {
        WM.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(503)));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .with(shopper("kc-alice", "shopper-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + SEEN + "\","
                                + "\"shippingAddressId\":\"11111111-2222-3333-4444-555555555555\","
                                + "\"items\":[{\"variantId\":\"" + VARIANT + "\",\"title\":\"x\",\"quantity\":1,"
                                + "\"unitPrice\":100.00}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/upstream-unavailable"));
        assertThat(orders.count()).isZero();
    }

    @Test
    @DisplayName("a wrong client secret is OUR misconfiguration: 500, not a 503 that invites endless retries")
    void aWrongClientSecretIsOurFault() throws Exception {
        WM.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_client\",\"error_description\":\"Invalid client credentials\"}")));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .with(shopper("kc-alice", "shopper-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + SEEN + "\","
                                + "\"shippingAddressId\":\"11111111-2222-3333-4444-555555555555\","
                                + "\"items\":[{\"variantId\":\"" + VARIANT + "\",\"title\":\"x\",\"quantity\":1,"
                                + "\"unitPrice\":100.00}]}"))
                .andExpect(status().isInternalServerError());
        assertThat(orders.count()).isZero();
    }

    @Test
    @DisplayName("shipping hanging: order gives up within its own budget with a 503, and saves nothing")
    void aHangingShippingTimesOut() throws Exception {
        WM.stubFor(post(urlEqualTo("/api/v1/shipping/rates")).willReturn(okJson(
                "{\"destinationCountry\":\"ZM\",\"options\":[]}").withFixedDelay(6000)));

        long began = System.nanoTime();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .with(shopper("kc-alice", "shopper-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + SEEN + "\","
                                + "\"shippingAddressId\":\"11111111-2222-3333-4444-555555555555\","
                                + "\"items\":[{\"variantId\":\"" + VARIANT + "\",\"title\":\"x\",\"quantity\":1,"
                                + "\"unitPrice\":100.00}]}"))
                .andExpect(status().isServiceUnavailable());
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        // Read timeout 3s, inside checkout's 15s budget for the whole placement.
        assertThat(tookMs).isLessThan(5_500);
        assertThat(orders.count()).isZero();
    }
}
