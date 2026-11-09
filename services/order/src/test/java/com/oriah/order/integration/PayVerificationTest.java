package com.oriah.order.integration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.client.PaymentClient;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /orders/{id}/pay} must not take the client's word that a payment
 * happened.
 *
 * <h2>The defect being pinned</h2>
 *
 * The endpoint verified that the order belonged to the caller and then
 * attached whatever {@code paymentIntentId} the body contained, transitioning
 * the order to PAID. Its own javadoc recorded the gap: "Caller
 * responsibility: ensure the PaymentIntent is actually SUCCEEDED before
 * calling this. (Future checkout orchestrator validates that.)"
 *
 * <p>So one request -- the shopper's own order id, plus a UUID they invented
 * -- produced a PAID order with no money taken. Two of the existing tests in
 * {@code OrderIntegrationTest} paid with {@code UUID.randomUUID()} and passed,
 * which is how routine the hole was.
 *
 * <p>It is not fixable by restricting the endpoint to the checkout
 * orchestrator. Checkout reaches it through its {@code FeignTokenForwarder},
 * forwarding the shopper's own JWT, so an orchestrated call carries the same
 * subject and the same ROLE_USER as a hand-rolled one. The claim itself has to
 * be checked.
 *
 * <h2>What a settled payment has to satisfy</h2>
 *
 * Four conditions, each with its own tests below: SUCCEEDED, names THIS order,
 * matches the currency -- and matches the order's total EXACTLY, once that total
 * is authoritative (V4). An order placed before V4 is not held to the amount:
 * its total left out delivery, so the charge legitimately differed from it.
 *
 * <p>Ownership of the intent is absent on purpose -- it is enforced by
 * payment's own owner-scoped read, reached with the shopper's forwarded token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class PayVerificationTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");

    /** 2 x 100.00 + 1 x 50.00, self-collected: no delivery charge on top. */
    private static final BigDecimal TOTAL = new BigDecimal("250.00");

    /**
     * Catalog, answering for whatever this test's fixture asks about.
     *
     * <p>order prices every line from catalog now, so a test that places an
     * order needs catalog to answer exactly as it needs Postgres to answer.
     * Without this the real Feign client tries to reach {@code http://catalog}
     * and every placement is a 503.
     *
     * <p>This stub prices BY ID from {@link #catalogPrices} rather than
     * returning one flat figure, so the fixtures keep their exact arithmetic --
     * this class's narrative depends on the order totalling 250.00, and a
     * convenient flat price would have quietly rewritten it.
     *
     * <p>Nothing about what this class TESTS has changed. Its subject is
     * authorization and payment verification, and it asserts the same things
     * about them.
     */
    @MockitoBean
    private CatalogPricingClient catalog;

    /** variantId -> what catalog charges for it, filled by the fixture. */
    private final java.util.Map<UUID, BigDecimal> catalogPrices = new java.util.HashMap<>();

    private static final String CURRENCY = "ZMW";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private OrderRepository repository;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /** The whole point: payment is consulted, and here we control its answer. */
    @MockitoBean
    private PaymentClient payment;

    /** Shipping as it is until Phase 7: self-collect at no charge, in the order's currency. */
    @MockitoBean
    private com.oriah.order.client.ShippingClient shipping;

    /**
     * customer, listing the fixture's address as the caller's. Whose address it
     * is has its own tests ({@link PlaceOrderAddressTest}); this class's
     * subject is payment.
     */
    @MockitoBean
    private com.oriah.order.client.CustomerClient customer;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        org.mockito.Mockito.doReturn(java.util.List.of(new com.oriah.order.client.CustomerClient.Address(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"))))
                .when(customer).myAddresses();
        org.mockito.Mockito.doAnswer(inv -> {
            com.oriah.order.client.ShippingClient.RateRequest asked = inv.getArgument(0);
            return new com.oriah.order.client.ShippingClient.RateResponse("ZM", java.util.List.of(
                    new com.oriah.order.client.ShippingClient.ShippingOption(
                            "SELF_COLLECT", new java.math.BigDecimal("0.00"), asked.currency())));
        }).when(shipping).rates(org.mockito.ArgumentMatchers.any());
        repository.deleteAll();
        catalogPrices.clear();
        doAnswer(inv -> {
            CatalogPricingClient.PricingRequest asked = inv.getArgument(0);
            return new CatalogPricingClient.PricingResponse(asked.variantIds().stream()
                    .map(id -> new CatalogPricingClient.Entry(
                            id, "PRICED", UUID.randomUUID(), "Aurelia 18k",
                            "Aurelia 18k", catalogPrices.get(id), CURRENCY))
                    .toList());
        }).when(catalog).price(any());
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject(subject));
    }

    private UUID placeOrderAs(String subject) throws Exception {
        // Declared rather than inlined, so the stub can price each one at the
        // figure this fixture's 250.00 total depends on.
        UUID aurelia = UUID.randomUUID();
        UUID lume = UUID.randomUUID();
        catalogPrices.put(aurelia, new BigDecimal("100.00"));
        catalogPrices.put(lume, new BigDecimal("50.00"));

        String body = """
                {
                  "shippingAddressId": "11111111-1111-1111-1111-111111111111",
                  "deliveryOption": "SELF_COLLECT",
                  "currency": "%s",
                  "quoteDigest": "%s",
                  "items": [
                    {"variantId":"%s","title":"Aurelia","quantity":2,"unitPrice":100.00},
                    {"variantId":"%s","title":"Lume","quantity":1,"unitPrice":50.00}
                  ]
                }
                """.formatted(CURRENCY, com.oriah.order.QuoteFixtures.selfCollect(CURRENCY,
                        com.oriah.order.QuoteFixtures.line(aurelia, 2, "100.00"),
                        com.oriah.order.QuoteFixtures.line(lume, 1, "50.00")), aurelia, lume);
        String json = mvc.perform(post("/api/v1/orders").with(as(subject))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    private String payBody(UUID intentId) {
        return "{\"paymentIntentId\":\"" + intentId + "\"}";
    }

    private PaymentClient.IntentResponse intent(UUID id, UUID orderId,
                                                BigDecimal amount, String currency, String status) {
        return new PaymentClient.IntentResponse(id, orderId, amount, currency, status);
    }

    /** A real FeignException of the given status, as the client would throw. */
    private static FeignException feignStatus(int status) {
        Request request = Request.create(Request.HttpMethod.GET,
                "http://payment/api/v1/payments/intents/x", Map.of(),
                new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus("PaymentClient#findIntent(UUID)",
                Response.builder().status(status).reason("generated")
                        .request(request).headers(Map.of()).build());
    }

    private OrderStatus statusOf(UUID orderId) {
        return repository.findById(orderId).orElseThrow().status();
    }

    /** Everything in a problem body except the per-request correlation id. */
    private String withoutTraceId(String problemJson) throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode)
                objectMapper.readTree(problemJson);
        node.remove("traceId");
        return objectMapper.writeValueAsString(node);
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("a fabricated intent id is refused and the order stays CREATED")
    void fabricatedIntentIsRejected() throws Exception {
        UUID orderId = placeOrderAs("alice");
        // Payment answers 404 for an intent that does not exist -- and
        // identically for one belonging to another customer.
        when(payment.findIntent(any())).thenThrow(feignStatus(404));

        mvc.perform(post("/api/v1/orders/" + orderId + "/pay").with(as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody(UUID.randomUUID())))
                .andExpect(status().isConflict());

        assertThat(statusOf(orderId))
                .as("an invented payment intent must never produce a PAID order")
                .isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("a settled intent belonging to a DIFFERENT order is refused")
    void intentForAnotherOrderIsRejected() throws Exception {
        UUID orderId = placeOrderAs("alice");
        UUID otherOrder = placeOrderAs("alice");
        UUID intentId = UUID.randomUUID();
        // Genuinely settled, genuinely Alice's -- for her OTHER order. Without
        // the order check she could settle one order and present that intent
        // against every other order she owns.
        when(payment.findIntent(intentId))
                .thenReturn(intent(intentId, otherOrder, TOTAL, CURRENCY, "SUCCEEDED"));

        mvc.perform(post("/api/v1/orders/" + orderId + "/pay").with(as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody(intentId)))
                .andExpect(status().isConflict());

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CREATED);
        assertThat(statusOf(otherOrder)).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("an intent that has not settled yet is refused")
    void unsettledIntentIsRejected() throws Exception {
        UUID orderId = placeOrderAs("alice");
        UUID intentId = UUID.randomUUID();
        // PENDING is a charge that has not happened.
        when(payment.findIntent(intentId))
                .thenReturn(intent(intentId, orderId, TOTAL, CURRENCY, "PENDING"));

        mvc.perform(post("/api/v1/orders/" + orderId + "/pay").with(as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody(intentId)))
                .andExpect(status().isConflict());

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CREATED);
    }

    // ------------------------------------------------- the amount (item 4.5)

    /** Pays {@code orderId} with a settled intent for {@code amount}, and returns the response. */
    private org.springframework.test.web.servlet.ResultActions payWith(UUID orderId, BigDecimal amount)
            throws Exception {
        UUID intentId = UUID.randomUUID();
        when(payment.findIntent(intentId))
                .thenReturn(intent(intentId, orderId, amount, CURRENCY, "SUCCEEDED"));
        return mvc.perform(post("/api/v1/orders/" + orderId + "/pay").with(as("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payBody(intentId)));
    }

    /**
     * The gap this class used to document, closed. A shopper could mint a
     * 1.00 intent naming their own 250.00 order and have it accepted, because
     * the order's total left out delivery and so could not be compared with
     * what checkout charged. The total is now authoritative (V4), so it is.
     */
    @Test
    @DisplayName("an underpayment is refused: a 1.00 intent no longer pays a 250.00 order")
    void anUnderpaymentIsRefused() throws Exception {
        UUID orderId = placeOrderAs("alice");

        payWith(orderId, new BigDecimal("1.00"))
                .andExpect(status().isConflict())
                // The same words as every other refusal: the amount is not an oracle.
                .andExpect(jsonPath("$.detail").value(
                        "The payment intent could not be verified as a settled payment for this order."));

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("an overpayment is refused too: money the order does not record is a mismatch, not a bonus")
    void anOverpaymentIsRefused() throws Exception {
        UUID orderId = placeOrderAs("alice");

        payWith(orderId, new BigDecimal("250.01")).andExpect(status().isConflict());

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("an intent with no amount is refused, never read as a match")
    void anIntentWithNoAmountIsRefused() throws Exception {
        UUID orderId = placeOrderAs("alice");

        payWith(orderId, null).andExpect(status().isConflict());

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("250 and 250.00 are one amount: a difference of scale is not a mismatch")
    void aScaleDifferenceIsNotAMismatch() throws Exception {
        UUID orderId = placeOrderAs("alice");

        payWith(orderId, new BigDecimal("250")).andExpect(status().isOk());

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("an order placed before V4 stays payable: its total was never authoritative")
    void aLegacyOrderStaysPayable() throws Exception {
        // Its total left out delivery, so what checkout charged legitimately
        // differs from it. Comparing would refuse a real payment after the
        // money was taken -- the reason the comparison waited for V4.
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, keycloak_id, status, subtotal, total, currency) "
                + "VALUES (?, 'alice', 'CREATED', 250.00, 250.00, ?)", orderId, CURRENCY);

        payWith(orderId, new BigDecimal("375.00")).andExpect(status().isOk());

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("a settled intent in a different currency is refused")
    void currencyMismatchIsRejected() throws Exception {
        UUID orderId = placeOrderAs("alice");
        UUID intentId = UUID.randomUUID();
        // 250 of a stronger or weaker unit is not 250 ZMW; comparing the
        // numbers alone would accept this.
        when(payment.findIntent(intentId))
                .thenReturn(intent(intentId, orderId, TOTAL, "USD", "SUCCEEDED"));

        mvc.perform(post("/api/v1/orders/" + orderId + "/pay").with(as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody(intentId)))
                .andExpect(status().isConflict());

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("every refusal reads identically, so the 409 is not an oracle")
    void refusalsAreIndistinguishable() throws Exception {
        UUID orderId = placeOrderAs("alice");
        UUID unknown = UUID.randomUUID();
        UUID settledElsewhere = UUID.randomUUID();
        when(payment.findIntent(unknown)).thenThrow(feignStatus(404));
        when(payment.findIntent(settledElsewhere)).thenReturn(
                intent(settledElsewhere, UUID.randomUUID(), TOTAL, CURRENCY, "SUCCEEDED"));

        String missing = mvc.perform(post("/api/v1/orders/" + orderId + "/pay").with(as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(payBody(unknown)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        String wrongOrder = mvc.perform(post("/api/v1/orders/" + orderId + "/pay").with(as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(payBody(settledElsewhere)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        // "wrong order" would otherwise confirm that an intent exists and is
        // settled, while "not found" would not -- a probe over other
        // customers' payments.
        //
        // traceId is removed before comparing because it is per-request by
        // design; requiring it to match would be asserting a bug. Everything
        // that describes the OUTCOME -- status, title, type, detail, instance
        // -- must be identical, and the detail is asserted explicitly so this
        // cannot pass by both bodies being empty.
        assertThat(withoutTraceId(wrongOrder))
                .as("a refused payment must not reveal WHICH condition failed")
                .isEqualTo(withoutTraceId(missing));
        assertThat(objectMapper.readTree(missing).get("detail").asText())
                .isEqualTo("The payment intent could not be verified as a settled payment for this order.");
    }

    // ------------------------------------------------------------- happy path

    @Test
    @DisplayName("a genuinely settled intent for this order pays it")
    void settledIntentIsAccepted() throws Exception {
        UUID orderId = placeOrderAs("alice");
        UUID intentId = UUID.randomUUID();
        when(payment.findIntent(intentId))
                .thenReturn(intent(intentId, orderId, TOTAL, CURRENCY, "SUCCEEDED"));

        mvc.perform(post("/api/v1/orders/" + orderId + "/pay").with(as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody(intentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentIntentId").value(intentId.toString()));

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
    }

    // ------------------------------------------------------------ fail closed

    @Test
    @DisplayName("payment being unreachable is 503 and leaves the order unpaid")
    void paymentUnreachableIsNotPaid() throws Exception {
        UUID orderId = placeOrderAs("alice");
        when(payment.findIntent(any())).thenThrow(feignStatus(503));

        // 503, NOT 409: the claim was never checked, so calling it false would
        // tell a shopper their good payment was invalid during an outage. The
        // order stays unpaid either way -- this fails closed.
        mvc.perform(post("/api/v1/orders/" + orderId + "/pay").with(as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody(UUID.randomUUID())))
                .andExpect(status().isServiceUnavailable());

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("paying somebody else's order is 404 and never consults payment")
    void foreignOrderIs404AndNeverConsultsPayment() throws Exception {
        UUID alices = placeOrderAs("alice");

        mvc.perform(post("/api/v1/orders/" + alices + "/pay").with(as("bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        // The 404 must not depend on a downstream service, and a probe for
        // another customer's order should not reach payment at all.
        verifyNoInteractions(payment);
        assertThat(statusOf(alices)).isEqualTo(OrderStatus.CREATED);
    }
}
