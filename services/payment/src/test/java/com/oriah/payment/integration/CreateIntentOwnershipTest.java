package com.oriah.payment.integration;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A payment intent can be made only for one of the caller's own orders.
 *
 * <h2>The hole</h2>
 *
 * Payment allows one live intent (pending, being charged or paid) per order --
 * a second would be a second way to take the same money. But createIntent
 * never asked whose order it was. So any signed-in customer who learned an
 * order id could make a PENDING intent for it, and the owner's own payment
 * was then refused (409) for as long as the stranger's intent stood.
 *
 * <h2>What payment does now</h2>
 *
 * Before it writes anything, payment asks order for the order -- with the
 * CALLER's own token. Order answers 404 for an order that is not the caller's,
 * exactly as for one that does not exist, so payment learns "yours" or "not
 * yours" and nothing more. Not yours, or no such order: 404. Order down, slow
 * or failing: 503, because nobody could check. Either way nothing is written.
 *
 * <p>The amount is checked too: an intent must be for the order's total, in
 * the order's currency, or it could never settle that order (order refuses a
 * payment of any other amount) -- and while it stood it would block the
 * order's real payment.
 *
 * <p>A real HTTP server (WireMock) stands in for order, so these see the
 * Authorization header order actually receives, and can make order slow or
 * unreachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CreateIntentOwnershipTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");

    /** alice's order: K4,500.00, self-collect, placed after order's V4 (so its total is the payable amount). */
    private static final UUID ALICES_ORDER = UUID.fromString("0a11ce00-0000-4000-8000-000000004500");

    private static final WireMockServer ORDER = new WireMockServer(options().dynamicPort());

    @BeforeAll
    static void start() {
        ORDER.start();
    }

    @AfterAll
    static void stop() {
        ORDER.stop();
    }

    @DynamicPropertySource
    static void orderIsTheFakeServer(DynamicPropertyRegistry r) {
        // A configured URL bypasses service discovery for that client.
        r.add("spring.cloud.openfeign.client.config.order.url", ORDER::baseUrl);
        r.add("eureka.client.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PaymentIntentRepository intents;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        idempotencyKeys.deleteAll();
        intents.deleteAll();
        ORDER.resetAll();
        // How order answers for any order that is not the caller's, or does
        // not exist: the same 404, by design (OrderController.get).
        ORDER.stubFor(get(urlPathMatching("/api/v1/orders/.*")).atPriority(10)
                .willReturn(notFoundFromOrder()));
        // alice's order, shown only to alice's token.
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .withHeader("Authorization", equalTo("Bearer alice-token"))
                .willReturn(okJson(alicesOrder("4500.00", "0.00", "0.00", "ZMW"))));
    }

    // ----------------------------------------------------------------- not yours

    @Test
    @DisplayName("somebody else's order: 404, nothing written")
    void aStrangersOrderIsNotFound() throws Exception {
        create(bob(), ALICES_ORDER, "4500.00", "ZMW")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        "Order not found with the given input data id : '" + ALICES_ORDER + "'"));

        assertThat(intentsFor(ALICES_ORDER)).as("a stranger's intent must never be written").isEmpty();
        // Asked as BOB: order's own owner check is what refused him.
        assertThat(authorizationSentToOrder()).containsExactly("Bearer bob-token");
    }

    @Test
    @DisplayName("an order that does not exist: 404, nothing written")
    void anUnknownOrderIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();

        create(alice(), unknown, "4500.00", "ZMW")
                .andExpect(status().isNotFound());

        assertThat(intents.count()).isZero();
    }

    @Test
    @DisplayName("'not yours' and 'does not exist' are the same answer, so a 404 confirms nothing")
    void notYoursIsIndistinguishableFromUnknown() throws Exception {
        UUID unknown = UUID.randomUUID();

        String notYours = create(bob(), ALICES_ORDER, "4500.00", "ZMW")
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String neverExisted = create(bob(), unknown, "4500.00", "ZMW")
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(withoutTraceId(notYours).replace(ALICES_ORDER.toString(), "<id>"))
                .as("bob must not be able to tell alice's real order id from a made-up one")
                .isEqualTo(withoutTraceId(neverExisted).replace(unknown.toString(), "<id>"));
    }

    // ----------------------------------------------------------------- yours

    @Test
    @DisplayName("the owner's own order: 201, and order was asked with the owner's own token, once")
    void theOwnersCreateWorks() throws Exception {
        create(alice(), ALICES_ORDER, "4500.00", "ZMW")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(ALICES_ORDER.toString()))
                .andExpect(jsonPath("$.amount").value(4500.00))
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(intentsFor(ALICES_ORDER)).hasSize(1);
        // The shopper's token, one value: payment's own identity would ask
        // about an account that owns no orders.
        assertThat(authorizationSentToOrder()).containsExactly("Bearer alice-token");
    }

    @Test
    @DisplayName("the same amount written differently (4500 and 4500.00) is the order's total")
    void theTotalIsComparedAsAnAmountNotAsText() throws Exception {
        create(alice(), ALICES_ORDER, "4500", "ZMW").andExpect(status().isCreated());
    }

    // ----------------------------------------------------------------- not the order's total

    @Test
    @DisplayName("an amount that is not the order's total: 400, nothing written")
    void anAmountThatIsNotTheTotalIsRefused() throws Exception {
        create(alice(), ALICES_ORDER, "1.00", "ZMW")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "This payment is for 1.00 ZMW, but the order's total is 4500.00 ZMW. "
                                + "A payment must be for exactly the order's total, in its currency."));

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("more than the order's total is refused as well: money the order does not record")
    void anOverpaymentIsRefused() throws Exception {
        create(alice(), ALICES_ORDER, "4500.01", "ZMW").andExpect(status().isBadRequest());

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("a currency that is not the order's: 400, nothing written")
    void aCurrencyThatIsNotTheOrdersIsRefused() throws Exception {
        create(alice(), ALICES_ORDER, "4500.00", "USD")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "This payment is for 4500.00 USD, but the order's total is 4500.00 ZMW. "
                                + "A payment must be for exactly the order's total, in its currency."));

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("an order placed before order's V4 is not held to its total, as order itself does not")
    void anOrderFromBeforeV4IsNotHeldToItsTotal() throws Exception {
        // Before V4 an order's total was its subtotal alone, while checkout
        // charged delivery on top -- so the real charge legitimately differs
        // from that total, and order's PaymentIntentVerifier does not hold it
        // to it. Such an order shows no shipping or discount amount (unknown,
        // not zero). Refusing here would leave it unpayable.
        UUID older = UUID.randomUUID();
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + older)).atPriority(1)
                .withHeader("Authorization", equalTo("Bearer alice-token"))
                .willReturn(okJson(order(older, "4000.00", null, null, "ZMW"))));

        create(alice(), older, "4125.00", "ZMW").andExpect(status().isCreated());

        assertThat(intentsFor(older)).hasSize(1);
    }

    @Test
    @DisplayName("an order placed before V4 must still be paid in its own currency")
    void anOrderFromBeforeV4MustStillBePaidInItsCurrency() throws Exception {
        UUID older = UUID.randomUUID();
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + older)).atPriority(1)
                .withHeader("Authorization", equalTo("Bearer alice-token"))
                .willReturn(okJson(order(older, "4000.00", null, null, "ZMW"))));

        create(alice(), older, "4125.00", "USD").andExpect(status().isBadRequest());

        assertThat(intentsFor(older)).isEmpty();
    }

    // ----------------------------------------------------------------- not waiting for payment

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CANCELLED", "PAID", "FULFILLED", "SHIPPED", "DELIVERED"})
    @DisplayName("the owner's order that is no longer waiting for payment: 409, nothing written")
    void anOrderNoLongerWaitingForPaymentIsRefused(String status) throws Exception {
        // Only CREATED moves to PAID (order's OrderStatus). An intent for any
        // other order could still be confirmed and the card charged -- and
        // then order's /pay would refuse it, leaving money taken that no
        // order records. A cancelled order has no live intent left to stop
        // it, so this check is the only thing that does.
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .withHeader("Authorization", equalTo("Bearer alice-token"))
                .willReturn(okJson(withStatus(alicesOrder("4500.00", "0.00", "0.00", "ZMW"), status))));

        create(alice(), ALICES_ORDER, "4500.00", "ZMW")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Order " + ALICES_ORDER + " is " + status
                        + ", so it is no longer waiting for payment and cannot be paid."));

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("an order that cannot be paid is refused as such, whatever amount is asked")
    void anOrderThatCannotBePaidIsRefusedBeforeItsAmountIsChecked() throws Exception {
        // "This order cannot be paid at all" is the answer that matters; the
        // amount would be wrong for a payment that is never allowed anyway.
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .withHeader("Authorization", equalTo("Bearer alice-token"))
                .willReturn(okJson(withStatus(alicesOrder("4500.00", "0.00", "0.00", "ZMW"), "PAID"))));

        create(alice(), ALICES_ORDER, "1.00", "ZMW").andExpect(status().isConflict());

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("order answering with no status: 500, nothing written -- payment cannot tell if it may be paid")
    void orderAnsweringWithNoStatusIsOurFault() throws Exception {
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .withHeader("Authorization", equalTo("Bearer alice-token"))
                .willReturn(okJson(alicesOrder("4500.00", "0.00", "0.00", "ZMW")
                        .replace("\"status\":\"CREATED\",", ""))));

        create(alice(), ALICES_ORDER, "4500.00", "ZMW").andExpect(status().isInternalServerError());

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    // ----------------------------------------------------------------- order could not be asked

    @Test
    @DisplayName("order unreachable: 503 with Retry-After, nothing written")
    void orderDownIsServiceUnavailable() throws Exception {
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        create(alice(), ALICES_ORDER, "4500.00", "ZMW")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(
                        "We could not check this order just now. No payment has been set up "
                                + "and nothing has been charged; please try again."));

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("order failing (5xx): 503, nothing written -- nobody checked, so it is not a 404")
    void orderFailingIsServiceUnavailable() throws Exception {
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .willReturn(aResponse().withStatus(500)));

        create(alice(), ALICES_ORDER, "4500.00", "ZMW").andExpect(status().isServiceUnavailable());

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("order hanging: payment gives up within its own time with a 503, and writes nothing")
    void orderSlowIsServiceUnavailable() throws Exception {
        // The order alice owns, if it ever arrived, would be a 201 -- so the
        // 503 can only be payment giving up on the wait.
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .willReturn(okJson(alicesOrder("4500.00", "0.00", "0.00", "ZMW")).withFixedDelay(8000)));

        long began = System.nanoTime();
        create(alice(), ALICES_ORDER, "4500.00", "ZMW").andExpect(status().isServiceUnavailable());
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        // Read timeout 3s (application.yaml), inside the 6s checkout waits for
        // payment's create (its payment-create client). Feign's own default
        // would wait 60s.
        assertThat(tookMs).isLessThan(5_500);
        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    // ----------------------------------------------------------------- our fault, not the shopper's

    @Test
    @DisplayName("order refusing payment's request (401): 500, our misconfiguration -- not a 404 or a 503")
    void orderRefusingTheRequestIsOurFault() throws Exception {
        // Payment sends order the very token it has just accepted. A 401 or
        // 403 from order means the two services disagree about tokens or
        // roles: "not your order" would blame the shopper, and a 503 would
        // invite a retry that fails every time.
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .willReturn(aResponse().withStatus(401)));

        create(alice(), ALICES_ORDER, "4500.00", "ZMW").andExpect(status().isInternalServerError());

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("order answering 200 with no order in it: 500, nothing written")
    void orderAnsweringWithNothingIsOurFault() throws Exception {
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .willReturn(aResponse().withStatus(200)));

        create(alice(), ALICES_ORDER, "4500.00", "ZMW").andExpect(status().isInternalServerError());

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("order answering 200 with a body that is not an order: 500, not a 503 to retry")
    void orderAnsweringWithSomethingThatIsNotAnOrderIsOurFault() throws Exception {
        // order answered, in full, and the answer cannot be read as an order:
        // the two services disagree about its shape. The same bytes come back
        // on every attempt, so a 503 would send checkout round its retries for
        // nothing and tell the shopper to keep trying something that cannot
        // work.
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .willReturn(okJson(alicesOrder("4500.00", "0.00", "0.00", "ZMW")
                        .replace("\"total\":4500.00", "\"total\":\"abc\""))));

        create(alice(), ALICES_ORDER, "4500.00", "ZMW")
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Retry-After"));

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("order answering 200 with a web page instead of JSON: 500, nothing written")
    void orderAnsweringWithAWebPageIsOurFault() throws Exception {
        // What a misrouted call typically gets back: a login or proxy page.
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("<html><body>Sign in</body></html>")));

        create(alice(), ALICES_ORDER, "4500.00", "ZMW").andExpect(status().isInternalServerError());

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("order redirecting payment elsewhere: 500 -- an answer with no order in it, not an outage")
    void orderRedirectingIsOurFault() throws Exception {
        // http -> https is a redirect the HTTP client does not follow, so
        // payment sees the 302 itself: a set-up fault, the same every time.
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "https://order.invalid/api/v1/orders/" + ALICES_ORDER)));

        create(alice(), ALICES_ORDER, "4500.00", "ZMW").andExpect(status().isInternalServerError());

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    @Test
    @DisplayName("order's answer cut off on the way: 503 -- the order never arrived, so nobody checked")
    void anAnswerCutOffInTransitIsServiceUnavailable() throws Exception {
        // A 200 status line, then the connection breaks inside the body: the
        // network failed, not the contract. Retrying is right, so this stays
        // a 503 even though order's status was 200.
        ORDER.stubFor(get(urlEqualTo("/api/v1/orders/" + ALICES_ORDER)).atPriority(1)
                .willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)));

        create(alice(), ALICES_ORDER, "4500.00", "ZMW")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));

        assertThat(intentsFor(ALICES_ORDER)).isEmpty();
    }

    // ------------------------------------------------------------------

    private ResultActions create(RequestPostProcessor who, UUID orderId, String amount, String currency)
            throws Exception {
        return mvc.perform(post("/api/v1/payments/intents")
                .with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\",\"amount\":" + amount
                        + ",\"currency\":\"" + currency + "\"}"));
    }

    private static RequestPostProcessor alice() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.tokenValue("alice-token").subject("kc-alice"));
    }

    private static RequestPostProcessor bob() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.tokenValue("bob-token").subject("kc-bob"));
    }

    private List<PaymentIntent> intentsFor(UUID orderId) {
        return intents.findAll().stream().filter(i -> i.orderId().equals(orderId)).toList();
    }

    private static List<String> authorizationSentToOrder() {
        var calls = ORDER.findAll(getRequestedFor(urlPathMatching("/api/v1/orders/.*")));
        assertThat(calls).as("payment asks order exactly once per create").hasSize(1);
        var header = calls.get(0).getHeaders().getHeader("Authorization");
        return header.isPresent() ? header.values() : List.of();
    }

    private static String alicesOrder(String total, String shipping, String discount, String currency) {
        return order(ALICES_ORDER, total, shipping, discount, currency);
    }

    /**
     * order's real response shape (OrderResponse), every field included, so
     * the binding is proved to tolerate the ones payment does not read.
     * {@code shipping} and {@code discount} null is an order placed before V4.
     */
    private static String order(UUID id, String total, String shipping, String discount, String currency) {
        return "{\"id\":\"" + id + "\",\"keycloakId\":\"kc-alice\",\"status\":\"CREATED\","
                + "\"subtotal\":" + total + ",\"shippingAmount\":" + shipping + ",\"discountAmount\":" + discount
                + ",\"total\":" + total + ",\"currency\":\"" + currency + "\","
                + "\"deliveryOption\":" + (shipping == null ? "null" : "\"SELF_COLLECT\"") + ","
                + "\"paymentIntentId\":null,\"shippingAddressId\":null,"
                + "\"placedAt\":\"2026-11-09T10:00:00Z\",\"updatedAt\":\"2026-11-09T10:00:00Z\","
                + "\"items\":[{\"id\":\"" + UUID.randomUUID() + "\",\"variantId\":\"" + UUID.randomUUID()
                + "\",\"title\":\"Aurelia 18k\",\"quantity\":1,\"unitPrice\":" + total + "}]}";
    }

    /** The same order in another state of order's lifecycle. */
    private static String withStatus(String orderJson, String status) {
        String created = "\"status\":\"CREATED\"";
        assertThat(orderJson).as("the order's JSON names its status").contains(created);
        return orderJson.replace(created, "\"status\":\"" + status + "\"");
    }

    /** order's own 404, as its shared error advice renders it. */
    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder notFoundFromOrder() {
        return aResponse().withStatus(404)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"type\":\"https://oriah.dev/problems/not-found\",\"title\":\"Not Found\","
                        + "\"status\":404,\"detail\":\"Order not found\"}");
    }

    /** traceId is per request, so it is the one field that legitimately differs. */
    private static String withoutTraceId(String json) {
        return json.replaceAll(",?.traceId.:.[0-9a-f]+.", "");
    }
}
