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
 *   <li><b>customer</b> gets the SHOPPER's token too: "which addresses are
 *       yours?" is answered for whoever the token belongs to. Order's own token
 *       would ask about the shop's service account, which owns no address.</li>
 *   <li><b>catalog</b>'s pricing endpoint is anonymous and gets no token.</li>
 * </ul>
 *
 * <p>A real HTTP server (WireMock) stands in for all four and for Keycloak,
 * so these assert the Authorization header each one actually received -- one
 * value, not two appended. Feign's interceptors APPEND, so a global forwarder
 * running beside the machine-token interceptor would send both.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
class OutboundIdentityTest {

    private static final String TOKEN_PATH = "/realms/oriah/protocol/openid-connect/token";
    private static final String ADDRESSES_PATH = "/api/v1/customers/me/addresses";
    private static final String SHIPPING_QUOTE = "/api/v1/shipping/quote";
    private static final String RESERVE = "/api/v1/admin/inventory/reservations";
    /** The address every placement here sends, and the one customer lists as the shopper's. */
    private static final String ADDRESS = "11111111-2222-3333-4444-555555555555";
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
        r.add("spring.cloud.openfeign.client.config.inventory.url", WM::baseUrl);
        r.add("spring.cloud.openfeign.client.config.catalog.url", WM::baseUrl);
        r.add("spring.cloud.openfeign.client.config.catalogLookup.url", WM::baseUrl);
        r.add("spring.cloud.openfeign.client.config.customer.url", WM::baseUrl);
        r.add("spring.security.oauth2.client.provider.keycloak.token-uri", () -> WM.baseUrl() + TOKEN_PATH);
        r.add("spring.security.oauth2.client.registration.oriah-admin.client-secret", () -> "test-secret");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

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
        WM.stubFor(post(urlEqualTo(SHIPPING_QUOTE)).willReturn(okJson(
                "{\"optionCode\":\"SELF_COLLECT\",\"optionName\":\"Self-collect\",\"amount\":0.00,\"currency\":\"ZMW\","
                        + "\"listAmount\":0.00,\"freeByThreshold\":false}")));
        // inventory holds the pieces (a static answer: HELD, lapsing long after any test)
        WM.stubFor(post(urlEqualTo(RESERVE)).willReturn(okJson(
                "{\"reservationId\":\"" + UUID.randomUUID() + "\",\"orderId\":\"" + UUID.randomUUID() + "\",\"status\":\"HELD\","
                        + "\"heldAt\":\"2099-01-01T00:00:00Z\",\"expiresAt\":\"2099-01-01T00:10:00Z\",\"endedAt\":null,\"lines\":[]}")));
        // customer's real response shape, every field included, so the
        // id-only binding is proved to tolerate the ones order does not read.
        WM.stubFor(get(urlEqualTo(ADDRESSES_PATH)).willReturn(okJson(
                "[{\"id\":\"" + ADDRESS + "\",\"label\":\"Home\",\"line1\":\"Plot 12, Kabulonga Road\","
                        + "\"line2\":null,\"city\":\"Lusaka\",\"state\":\"Lusaka\",\"postalCode\":\"10101\","
                        + "\"country\":\"ZM\",\"isDefault\":true}]")));
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

        var calls = WM.findAll(postRequestedFor(urlEqualTo(SHIPPING_QUOTE)));
        assertThat(calls).hasSize(1);
        assertThat(authorizationOf(calls.get(0))).containsExactly("Bearer machine-token");
    }

    @Test
    @DisplayName("inventory receives order's own machine token -- once, and never the shopper's: its reservation endpoints are for the order system only")
    void inventorySeesTheServiceAccountNotTheShopper() throws Exception {
        place(shopper("kc-alice", "shopper-token"));

        var calls = WM.findAll(postRequestedFor(urlEqualTo(RESERVE)));
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
        WM.verify(2, postRequestedFor(urlEqualTo(SHIPPING_QUOTE)));
        WM.verify(2, postRequestedFor(urlEqualTo(RESERVE)));
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
    @DisplayName("customer receives the SHOPPER's token -- once, and never order's machine token")
    void customerSeesTheShopperNotTheServiceAccount() throws Exception {
        String placed = place(shopper("kc-alice", "shopper-token"));

        // Placed, with the address customer listed: the id-only binding read
        // customer's full response without tripping on the fields it ignores.
        assertThat((String) com.jayway.jsonpath.JsonPath.read(placed, "$.shippingAddressId"))
                .isEqualTo(ADDRESS);
        var calls = WM.findAll(getRequestedFor(urlEqualTo(ADDRESSES_PATH)));
        assertThat(calls).hasSize(1);
        // One value, not two appended: the machine-token interceptor is
        // shipping's alone, so it must not reach this client.
        assertThat(authorizationOf(calls.get(0))).containsExactly("Bearer shopper-token");
    }

    @Test
    @DisplayName("catalog's box-name lookup receives the PACKER's token -- once, and never order's machine token: it is catalog's internal door for a person")
    void catalogLookupSeesThePackerNotTheServiceAccount() throws Exception {
        String placed = place(shopper("kc-alice", "shopper-token"));
        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(placed, "$.id"));
        UUID box = UUID.randomUUID();
        jdbc.update("UPDATE orders SET status = 'PAID', hold_state = 'CONSUMED', paid_at = now(), receipt_number = 'OR-000001', paid_confirmation_id = ? WHERE id = ?", UUID.randomUUID(), id);
        jdbc.update("UPDATE order_items SET box_variant_id = ?, box_line_id = ? WHERE order_id = ?", box, UUID.randomUUID(), id);
        WM.stubFor(post(urlEqualTo("/api/v1/admin/variants/lookup")).willReturn(okJson(
                "{\"results\":[{\"variantId\":\"" + box + "\",\"found\":true,\"productTitle\":\"Velvet box\",\"variantTitle\":\"Large\"}]}")));

        String slip = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/orders/" + id + "/dispatch")
                        .with(jwt().jwt(j -> j.tokenValue("packer-token").subject("kc-dave")).authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT"))))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) com.jayway.jsonpath.JsonPath.read(slip, "$.lines[0].box.name")).isEqualTo("Velvet box / Large");
        var calls = WM.findAll(postRequestedFor(urlEqualTo("/api/v1/admin/variants/lookup")));
        assertThat(calls).hasSize(1);
        assertThat(authorizationOf(calls.get(0))).containsExactly("Bearer packer-token");
    }

    @Test
    @DisplayName("each shopper's address check carries THEIR token, never the previous shopper's")
    void eachShoppersAddressCheckCarriesTheirOwnToken() throws Exception {
        // The machine token is cached and shared; the shopper's must never be.
        // A forwarder that kept the first caller's token would let maya's order
        // be checked against alice's addresses.
        place(shopper("kc-alice", "alice-token"));
        place(shopper("kc-maya", "maya-token"));

        var calls = WM.findAll(getRequestedFor(urlEqualTo(ADDRESSES_PATH)));
        assertThat(calls).hasSize(2);
        assertThat(authorizationOf(calls.get(0))).containsExactly("Bearer alice-token");
        assertThat(authorizationOf(calls.get(1))).containsExactly("Bearer maya-token");
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

    /**
     * One ordinary placement, then forget it: so the clock in a hanging test
     * times the wait for the hanging service and not the first time order
     * reaches each of its clients.
     *
     * <p>The first call through a Feign client builds that client's own
     * Spring context (customer, catalog, shipping and the token each have
     * one), and the first request through MockMvc builds the security chain.
     * On a busy machine that took 2.8 s, and the customer test measured
     * 4,791 ms against its 4,500 ms bound (2026-11-09) with nothing wrong in
     * order. The bounds below are unchanged; only that one-off cost is paid
     * before the clock starts.
     *
     * <p>What the placement leaves behind is removed: the order, the cached
     * machine token (so the timed call still fetches one) and WireMock's
     * record of requests (so each test's own count of calls starts at zero).
     */
    private void warmUpTheWritePath() throws Exception {
        place(shopper("kc-alice", "shopper-token"));
        orders.deleteAll();
        authorizedClients.removeAuthorizedClient("oriah-admin", "order-service");
        WM.resetRequests();
    }

    @Test
    @DisplayName("shipping hanging: order gives up within its own budget with a 503, and saves nothing")
    void aHangingShippingTimesOut() throws Exception {
        warmUpTheWritePath();
        WM.stubFor(post(urlEqualTo(SHIPPING_QUOTE)).willReturn(okJson(
                "{\"optionCode\":\"SELF_COLLECT\",\"amount\":0.00,\"currency\":\"ZMW\"}").withFixedDelay(6000)));

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

        // Read timeout 2s (connect 0.5s), inside checkout's 15s budget for the
        // whole placement. Feign's own default would wait 60s.
        assertThat(tookMs).isLessThan(5_500);
        assertThat(orders.count()).isZero();
    }

    @Test
    @DisplayName("customer hanging: order gives up within its own budget with a 503, saves nothing, prices nothing")
    void aHangingCustomerTimesOut() throws Exception {
        warmUpTheWritePath();
        // An empty list, if it ever arrived, would be a 400 -- so the 503 below
        // can only be order giving up on the wait.
        WM.stubFor(get(urlEqualTo(ADDRESSES_PATH)).willReturn(okJson("[]").withFixedDelay(6000)));

        long began = System.nanoTime();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .with(shopper("kc-alice", "shopper-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + SEEN + "\","
                                + "\"shippingAddressId\":\"" + ADDRESS + "\","
                                + "\"items\":[{\"variantId\":\"" + VARIANT + "\",\"title\":\"x\",\"quantity\":1,"
                                + "\"unitPrice\":100.00}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/upstream-unavailable"));
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        // Read timeout 2s (application.yaml): its share of checkout's 15s.
        // Feign's own default would wait 60s.
        assertThat(tookMs).isLessThan(4_500);
        assertThat(orders.count()).isZero();
        // The check runs before pricing, so a placement that could not be
        // checked never reached catalog or shipping.
        WM.verify(0, postRequestedFor(urlEqualTo("/api/v1/variants/pricing")));
        WM.verify(0, postRequestedFor(urlEqualTo(SHIPPING_QUOTE)));
    }

    @Test
    @DisplayName("inventory hanging: order gives up within its own budget with a 503; the PENDING order stays, unannounced, for the repeat to finish")
    void aHangingInventoryTimesOut() throws Exception {
        warmUpTheWritePath();
        WM.stubFor(post(urlEqualTo(RESERVE)).willReturn(okJson("{}").withFixedDelay(6000)));

        long began = System.nanoTime();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .with(shopper("kc-alice", "shopper-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + SEEN + "\","
                                + "\"shippingAddressId\":\"11111111-2222-3333-4444-555555555555\","
                                + "\"items\":[{\"variantId\":\"" + VARIANT + "\",\"title\":\"x\",\"quantity\":1,"
                                + "\"unitPrice\":100.00}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/upstream-unavailable"));
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        // Read timeout 2s (connect 0.5s), the last call inside checkout's 20s wait for the whole placement. Feign's own default would wait 60s.
        assertThat(tookMs).isLessThan(5_500);
        // Nobody has been told of the order: it is written, PENDING, and the shopper's repeat finishes it with the same reservation id.
        assertThat(orders.count()).isEqualTo(1);
        assertThat(orders.findAll().get(0).holdState()).isEqualTo(com.oriah.order.model.HoldState.PENDING);
    }
}
