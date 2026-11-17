package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.client.OrderClient;
import com.oriah.payment.provider.PaymentProvider.CredentialsRefused;
import com.oriah.payment.provider.PaymentProvider.NotSent;
import com.oriah.payment.provider.PaymentProvider.Refused;
import com.oriah.payment.provider.PaymentProvider.Unclear;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A shopper starts paying an order through ZynlePay (Phase 13): {@code POST /api/v1/payments/provider-payments}.
 *
 * <p>The order of work is what keeps money safe. The attempt is written first; the ORDER is asked to take it and answers what is owed (so the
 * amount is the order's, never the shopper's); the attempt is committed as PENDING; only then is ZynlePay asked, once, and never again under that
 * reference. Whatever happens after the request has gone, the attempt is on record to be asked about. An answer that is not a definite refusal
 * leaves it pending: nothing ambiguous is a failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScriptedProvider.Config.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProviderStartTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ScriptedProvider zynlePay;
    @Autowired
    private JsonMapper json;
    @MockitoBean
    private OrderClient orders;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /** A shopper of this test's own: the hourly limits count per shopper, and attempts are never deleted. */
    private String shopper;
    private UUID order;
    /** A number of this test's own, for the same reason; it ends in a wallet of MTN. */
    private String phone;

    @BeforeEach
    void anOrderOfK180() {
        shopper = "kc-" + UUID.randomUUID();
        order = UUID.randomUUID();
        phone = "096" + String.format("%07d", (int) (Math.random() * 9_999_999));
        orderOwes(order, "180.00");
    }

    private void orderOwes(UUID orderId, String total) {
        doAnswer(call -> new OrderClient.Taken(orderId, new BigDecimal(total), "ZMW", Instant.now().plusSeconds(2_700)))
                .when(orders).takeProviderPayment(eq(orderId), any());
    }

    private RequestPostProcessor me() {
        return who(shopper);
    }

    private static RequestPostProcessor who(String subject) {
        return jwt().jwt(j -> j.subject(subject).claim("name", "Alice Banda").claim("given_name", "Alice").claim("family_name", "Banda")
                .claim("email", "alice@example.test")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private MvcResult start(RequestPostProcessor who, String key, String body) throws Exception {
        var request = post("/api/v1/payments/provider-payments").with(who).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private String mobileMoney() {
        return "{\"orderId\":\"" + order + "\",\"channel\":\"MOBILE_MONEY\",\"phone\":\"" + phone + "\"}";
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String problem(MvcResult result) throws Exception {
        String type = body(result).get("type").asString();
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM provider_payment WHERE id = ?", id);
    }

    private List<String> events(UUID id) {
        return jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_type = 'provider_payment' AND aggregate_id = ? ORDER BY seq", String.class,
                id.toString());
    }

    private static FeignException orderAnswers(int status, String problemCode) {
        Request request = Request.create(Request.HttpMethod.POST, "http://order/api/v1/orders/x/provider-payment", Map.of(), new byte[0], StandardCharsets.UTF_8,
                new RequestTemplate());
        String body = problemCode == null ? "" : "{\"type\":\"https://oriah.dev/problems/" + problemCode + "\",\"title\":\"Conflict\",\"status\":" + status
                + ",\"detail\":\"this order is CANCELLED: only an order waiting for payment can be paid\"}";
        return FeignException.errorStatus("OrderClient#takeProviderPayment", Response.builder().status(status).reason("x").request(request)
                .headers(Map.of("Content-Type", List.of("application/problem+json"))).body(body.getBytes(StandardCharsets.UTF_8)).build());
    }

    // ---------------------------------------------------------------- the amount and the order of work

    @Test
    @DisplayName("the amount is the order's total, never the shopper's: K180.00 by MTN is asked for once, under our reference, after the order took the attempt")
    void theAmountIsTheOrdersTotalNeverTheShoppers() throws Exception {
        MvcResult typed = start(me(), "k-" + UUID.randomUUID(), "{\"orderId\":\"" + order + "\",\"channel\":\"MOBILE_MONEY\",\"phone\":\"" + phone
                + "\",\"amount\":1.00}");
        assertThat(typed.getResponse().getStatus()).isEqualTo(400);
        assertThat(problem(typed)).isEqualTo("amount-is-not-typed");
        verify(orders, never()).takeProviderPayment(any(), any());

        MvcResult started = start(me(), "k-" + UUID.randomUUID(), mobileMoney());

        assertThat(started.getResponse().getStatus()).as(started.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode view = body(started);
        UUID id = UUID.fromString(view.get("id").asString());
        assertThat(view.get("status").asString()).isEqualTo("PENDING");
        assertThat(view.get("channel").asString()).isEqualTo("MOBILE_MONEY");
        assertThat(view.get("method").asString()).isEqualTo("MTN");
        assertThat(new BigDecimal(view.get("amount").asString())).isEqualByComparingTo("180.00");
        assertThat(view.get("currency").asString()).isEqualTo("ZMW");
        assertThat(view.get("phoneMasked").asString()).isEqualTo("+2609*****" + phone.substring(7));
        assertThat(view.get("nextStep").asString()).isEqualTo("APPROVE_ON_PHONE");
        assertThat(view.get("checkAgainInSeconds").asInt()).isEqualTo(15);
        // Nothing a shopper's page has no business holding: the whole number, our reference, ZynlePay's ids, the fee.
        assertThat(started.getResponse().getContentAsString()).doesNotContain("260" + phone.substring(1)).doesNotContain("OZ").doesNotContain("fee")
                .doesNotContain("TX");

        Map<String, Object> row = row(id);
        String reference = (String) row.get("reference_no");
        assertThat(reference).matches("^OZ[0-9A-Z]{14}$");
        assertThat(row.get("keycloak_id")).isEqualTo(shopper);
        assertThat(row.get("payer_msisdn")).isEqualTo("+260" + phone.substring(1));
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("180.00");
        assertThat((BigDecimal) row.get("fee_percent")).isEqualByComparingTo("2.5");
        assertThat(row.get("holds_order")).isEqualTo(true);
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("next_check_at")).isNotNull();
        assertThat(row.get("provider_transaction_id")).isEqualTo("TX" + reference.substring(2, 10));
        assertThat(row.get("operator_reference_key")).isEqualTo("OP" + reference.substring(2, 10));

        assertThat(zynlePay.askedFor(reference)).singleElement().satisfies(asked -> {
            assertThat(asked.kind()).isEqualTo("MOBILE_MONEY");
            assertThat(asked.msisdnDigits()).isEqualTo("260" + phone.substring(1));
            assertThat(asked.amount()).isEqualByComparingTo("180.00");
            assertThat(asked.currency()).isEqualTo("ZMW");
        });
        ArgumentCaptor<OrderClient.Take> taken = ArgumentCaptor.forClass(OrderClient.Take.class);
        verify(orders, times(1)).takeProviderPayment(eq(order), taken.capture());
        assertThat(taken.getValue().providerPaymentId()).isEqualTo(id);
        assertThat(taken.getValue().supersedes()).isNull();
        assertThat(events(id)).as("nothing to announce: it is in flight").isEmpty();
        assertThat(jdbc.queryForList("SELECT source || ':' || outcome FROM provider_payment_check WHERE payment_id = ?", String.class, id))
                .containsExactly("START:ACCEPTED");
    }

    @Test
    @DisplayName("the same key and request is the same attempt and ZynlePay is not asked twice; the key with another request, or a second attempt for the order, is refused")
    void theSameKeyIsTheSameAttempt() throws Exception {
        String key = "k-" + UUID.randomUUID();
        UUID id = UUID.fromString(body(start(me(), key, mobileMoney())).get("id").asString());

        MvcResult again = start(me(), key, mobileMoney());
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(again).get("id").asString()).isEqualTo(id.toString());
        assertThat(zynlePay.askedFor((String) row(id).get("reference_no"))).hasSize(1);

        MvcResult other = start(me(), key, "{\"orderId\":\"" + UUID.randomUUID() + "\",\"channel\":\"MOBILE_MONEY\",\"phone\":\"" + phone + "\"}");
        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(other)).isEqualTo("idempotency-key-reused");

        MvcResult second = start(me(), "k-" + UUID.randomUUID(), mobileMoney());
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(second)).isEqualTo("provider-payment-in-progress");
        assertThat(body(second).get("providerPaymentId").asString()).isEqualTo(id.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_payment WHERE order_id = ?", Long.class, order)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- the order's word

    @Test
    @DisplayName("an order that will not be paid says why in its own words; nothing is asked of ZynlePay, and the attempt is FAILED with no amount")
    void theOrderRefuses() throws Exception {
        doThrow(orderAnswers(409, "order-not-awaiting-payment")).when(orders).takeProviderPayment(eq(order), any());

        MvcResult refused = start(me(), "k-" + UUID.randomUUID(), mobileMoney());

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(refused)).isEqualTo("order-not-awaiting-payment");
        assertThat(body(refused).get("detail").asString()).contains("CANCELLED");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM provider_payment WHERE order_id = ?", order);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_code")).isEqualTo("ORDER_REFUSED");
        assertThat(row.get("amount")).isNull();
        assertThat(row.get("holds_order")).isEqualTo(false);
        assertThat(zynlePay.askedFor((String) row.get("reference_no"))).isEmpty();
        assertThat(events((UUID) row.get("id"))).singleElement().satisfies(payload -> assertThat(payload).contains("\"status\":\"FAILED\"")
                .contains("\"failureCode\":\"ORDER_REFUSED\"").doesNotContain("amount").doesNotContain(phone.substring(1)));

        // Trying again: the order is told this attempt takes the place of the refused one, though that one never got as far as being sent
        // (the order may have taken it all the same, if its answer was lost on the way back).
        orderOwes(order, "180.00");
        assertThat(start(me(), "k-" + UUID.randomUUID(), mobileMoney()).getResponse().getStatus()).isEqualTo(201);
        ArgumentCaptor<OrderClient.Take> taken = ArgumentCaptor.forClass(OrderClient.Take.class);
        verify(orders, times(2)).takeProviderPayment(eq(order), taken.capture());
        assertThat(taken.getAllValues().get(1).supersedes()).isEqualTo(row.get("id"));

        // An order that is not the shopper's is the same 404 as one that does not exist.
        UUID notMine = UUID.randomUUID();
        doThrow(orderAnswers(404, "not-found")).when(orders).takeProviderPayment(eq(notMine), any());
        MvcResult notFound = start(me(), "k-" + UUID.randomUUID(), "{\"orderId\":\"" + notMine + "\",\"channel\":\"MOBILE_MONEY\",\"phone\":\"" + phone + "\"}");
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(problem(notFound)).isEqualTo("order-not-found");
    }

    @Test
    @DisplayName("an order that cannot be asked: 503, nothing is sent, and the same key carries on with the same attempt once the order answers")
    void theOrderCannotBeAsked() throws Exception {
        doThrow(orderAnswers(503, null)).when(orders).takeProviderPayment(eq(order), any());
        String key = "k-" + UUID.randomUUID();

        MvcResult unavailable = start(me(), key, mobileMoney());

        assertThat(unavailable.getResponse().getStatus()).isEqualTo(503);
        assertThat(problem(unavailable)).isEqualTo("order-unavailable");
        assertThat(unavailable.getResponse().getHeader("Retry-After")).isEqualTo("5");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM provider_payment WHERE order_id = ?", order);
        assertThat(row.get("status")).isEqualTo("STARTING");
        assertThat(zynlePay.askedFor((String) row.get("reference_no"))).isEmpty();

        orderOwes(order, "180.00");
        MvcResult carriedOn = start(me(), key, mobileMoney());
        assertThat(carriedOn.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(carriedOn).get("id").asString()).isEqualTo(row.get("id").toString());
        assertThat(body(carriedOn).get("status").asString()).isEqualTo("PENDING");
        assertThat(zynlePay.askedFor((String) row.get("reference_no"))).hasSize(1);
    }

    // ---------------------------------------------------------------- ZynlePay's answers

    @Test
    @DisplayName("a definite refusal fails the attempt and says so; no connection at all fails it as never sent; both are announced, and the shopper may try again")
    void definiteFailures() throws Exception {
        zynlePay.pushAnswers(new Refused("01"));
        MvcResult refused = start(me(), "k-" + UUID.randomUUID(), mobileMoney());
        assertThat(refused.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(refused).get("status").asString()).isEqualTo("FAILED");
        assertThat(body(refused).get("failureCode").asString()).isEqualTo("PROVIDER_REFUSED");
        assertThat(body(refused).get("nextStep").asString()).isEqualTo("TRY_AGAIN");
        UUID first = UUID.fromString(body(refused).get("id").asString());
        assertThat(row(first).get("final_recheck_at")).as("asked about once more, in case ZynlePay contradicts itself").isNotNull();
        assertThat(events(first)).singleElement().satisfies(payload -> assertThat(payload).contains("\"failureCode\":\"PROVIDER_REFUSED\"")
                .contains("\"amount\":180.00"));

        // Trying again: the order is told which finished attempt this one takes the place of.
        zynlePay.pushAnswers(new NotSent("no connection: ConnectException"));
        MvcResult notSent = start(me(), "k-" + UUID.randomUUID(), mobileMoney());
        assertThat(notSent.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(notSent).get("failureCode").asString()).isEqualTo("NOT_SENT");
        UUID second = UUID.fromString(body(notSent).get("id").asString());
        assertThat(row(second).get("final_recheck_at")).as("nothing reached ZynlePay: there is nothing to ask about").isNull();
        ArgumentCaptor<OrderClient.Take> taken = ArgumentCaptor.forClass(OrderClient.Take.class);
        verify(orders, times(2)).takeProviderPayment(eq(order), taken.capture());
        assertThat(taken.getAllValues().get(1).supersedes()).isEqualTo(first);
    }

    @Test
    @DisplayName("an answer that is not a definite refusal leaves the attempt pending: an unknown code, a lost reply, and Oriah itself being turned away (503)")
    void nothingAmbiguousIsAFailure() throws Exception {
        zynlePay.pushAnswers(new Unclear("777", "code 777 is not a listed answer"));
        MvcResult unclear = start(me(), "k-" + UUID.randomUUID(), mobileMoney());
        assertThat(unclear.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(unclear).get("status").asString()).isEqualTo("PENDING");
        assertThat(body(unclear).get("nextStep").asString()).isEqualTo("WAIT");
        UUID id = UUID.fromString(body(unclear).get("id").asString());
        assertThat(row(id).get("holds_order")).isEqualTo(true);
        assertThat(row(id).get("provider_code")).isEqualTo("777");
        assertThat(events(id)).isEmpty();

        UUID otherOrder = UUID.randomUUID();
        orderOwes(otherOrder, "50.00");
        zynlePay.pushAnswers(new CredentialsRefused("9901"));
        MvcResult turnedAway = start(me(), "k-" + UUID.randomUUID(), "{\"orderId\":\"" + otherOrder + "\",\"channel\":\"MOBILE_MONEY\",\"phone\":\"" + phone + "\"}");
        assertThat(turnedAway.getResponse().getStatus()).isEqualTo(503);
        assertThat(problem(turnedAway)).isEqualTo("payments-unavailable");
        assertThat(jdbc.queryForObject("SELECT status FROM provider_payment WHERE order_id = ?", String.class, otherOrder)).isEqualTo("PENDING");
    }

    // ---------------------------------------------------------------- cards

    @Test
    @DisplayName("a card payment answers with ZynlePay's own page to send the customer to; Oriah takes no card detail of any kind, and no phone")
    void aCardPayment() throws Exception {
        MvcResult details = start(me(), "k-" + UUID.randomUUID(), "{\"orderId\":\"" + order + "\",\"channel\":\"CARD\",\"cardNumber\":\"4242424242424242\"}");
        assertThat(details.getResponse().getStatus()).isEqualTo(400);
        assertThat(problem(details)).isEqualTo("card-details-not-accepted");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_payment WHERE order_id = ?", Long.class, order)).isZero();
        MvcResult withPhone = start(me(), "k-" + UUID.randomUUID(), "{\"orderId\":\"" + order + "\",\"channel\":\"CARD\",\"phone\":\"" + phone + "\"}");
        assertThat(withPhone.getResponse().getStatus()).isEqualTo(400);
        assertThat(problem(withPhone)).isEqualTo("phone-not-for-card");

        MvcResult started = start(me(), "k-" + UUID.randomUUID(), "{\"orderId\":\"" + order + "\",\"channel\":\"CARD\"}");

        assertThat(started.getResponse().getStatus()).as(started.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode view = body(started);
        UUID id = UUID.fromString(view.get("id").asString());
        String reference = (String) row(id).get("reference_no");
        assertThat(view.get("method").asString()).isEqualTo("CARD");
        assertThat(view.get("nextStep").asString()).isEqualTo("OPEN_CARD_PAGE");
        assertThat(view.get("redirectUrl").asString()).isEqualTo("http://localhost:8085/internal/zynlepay-sim/card/" + reference);
        assertThat(view.has("phoneMasked")).isFalse();
        assertThat((BigDecimal) row(id).get("fee_percent")).isEqualByComparingTo("3.5");
        assertThat(zynlePay.askedFor(reference)).singleElement().satisfies(asked -> {
            assertThat(asked.kind()).isEqualTo("CARD");
            assertThat(asked.payer().firstName()).isEqualTo("Alice");
            assertThat(asked.payer().lastName()).isEqualTo("Banda");
            assertThat(asked.payer().email()).isEqualTo("alice@example.test");
            assertThat(asked.returnUrl()).as("where ZynlePay sends the customer back to: this order's own payment page")
                    .isEqualTo("http://localhost:4000/orders/" + order + "/payment?attempt=" + id);
        });
    }

    // ---------------------------------------------------------------- what is refused before anything is written

    @Test
    @DisplayName("a request that makes no sense is a 400 and writes nothing: no key, no phone, a number that is not a wallet's, a channel that is not one")
    void requestsThatMakeNoSense() throws Exception {
        assertThat(start(me(), null, mobileMoney()).getResponse().getStatus()).isEqualTo(400);
        assertThat(problem(start(me(), "k-1", "{\"orderId\":\"" + order + "\",\"channel\":\"MOBILE_MONEY\"}"))).isEqualTo("phone-required");
        assertThat(problem(start(me(), "k-2", "{\"orderId\":\"" + order + "\",\"channel\":\"MOBILE_MONEY\",\"phone\":\"0211234567\"}")))
                .isEqualTo("not-a-mobile-money-number");
        assertThat(problem(start(me(), "k-3", "{\"orderId\":\"" + order + "\",\"channel\":\"MOBILE_MONEY\",\"phone\":\"call me\"}"))).isEqualTo("invalid-phone");
        assertThat(start(me(), "k-4", "{\"orderId\":\"" + order + "\",\"channel\":\"CHEQUE\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(start(me(), "k-5", "{\"channel\":\"CARD\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(start(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")), "k-6", mobileMoney()).getResponse().getStatus())
                .as("staff do not start a shopper's payment").isEqualTo(403);
        assertThat(mvc.perform(post("/api/v1/payments/provider-payments").contentType(MediaType.APPLICATION_JSON).content(mobileMoney())).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_payment WHERE order_id = ?", Long.class, order)).isZero();
        verify(orders, never()).takeProviderPayment(any(), any());
    }

    @Test
    @DisplayName("money already recorded for the order (a staff confirmation that stands) refuses a start: the order is paid or being paid")
    void moneyAlreadyRecorded() throws Exception {
        jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by, confirmed_at,
                                                  idempotency_key, request_fingerprint)
                VALUES (?, ?, 'CASH', NULL, NULL, 180.00, 0, 'ZMW', now(), 'user:kc-sam', now(), ?, 'fp')""", UUID.randomUUID(), order, "idem-" + UUID.randomUUID());

        MvcResult refused = start(me(), "k-" + UUID.randomUUID(), mobileMoney());

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(refused)).isEqualTo("money-already-recorded");
        // Said only once the order has answered that it is the shopper's own: a stranger learns nothing about somebody else's order.
        verify(orders, times(1)).takeProviderPayment(eq(order), any());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM provider_payment WHERE order_id = ?", order);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_code")).isEqualTo("NOT_SENT");
        assertThat(zynlePay.askedFor((String) row.get("reference_no"))).isEmpty();
        assertThat(events((UUID) row.get("id"))).as("the order is told to let the attempt go").hasSize(1);
    }

    @Test
    @DisplayName("one number is asked at most four times an hour, whoever asks: the fifth is 429 with when to try again, and nothing is sent")
    void aNumberIsNotPestered() throws Exception {
        for (int i = 0; i < 4; i++) {
            UUID another = UUID.randomUUID();
            orderOwes(another, "10.00");
            zynlePay.pushAnswers(new Refused("01"));
            assertThat(start(who("kc-" + UUID.randomUUID()), "k-" + UUID.randomUUID(), "{\"orderId\":\"" + another + "\",\"channel\":\"MOBILE_MONEY\",\"phone\":\""
                    + phone + "\"}").getResponse().getStatus()).isEqualTo(201);
        }

        MvcResult fifth = start(me(), "k-" + UUID.randomUUID(), mobileMoney());

        assertThat(fifth.getResponse().getStatus()).isEqualTo(429);
        assertThat(problem(fifth)).isEqualTo("too-many-payment-attempts");
        assertThat(Integer.parseInt(fifth.getResponse().getHeader("Retry-After"))).isBetween(1, 3600);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_payment WHERE order_id = ?", Long.class, order)).isZero();
    }

    // ---------------------------------------------------------------- reading it back

    @Test
    @DisplayName("a shopper reads their own attempt and their attempts for an order; somebody else's is a 404, as if it did not exist")
    void readingItBack() throws Exception {
        UUID id = UUID.fromString(body(start(me(), "k-" + UUID.randomUUID(), mobileMoney())).get("id").asString());

        MvcResult mine = mvc.perform(get("/api/v1/payments/provider-payments/" + id).with(me())).andReturn();
        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(mine).get("status").asString()).isEqualTo("PENDING");
        assertThat(body(mine).get("orderId").asString()).isEqualTo(order.toString());

        MvcResult theirs = mvc.perform(get("/api/v1/payments/provider-payments/" + id).with(who("kc-" + UUID.randomUUID()))).andReturn();
        assertThat(theirs.getResponse().getStatus()).isEqualTo(404);
        assertThat(problem(theirs)).isEqualTo("provider-payment-not-found");

        MvcResult listed = mvc.perform(get("/api/v1/payments/provider-payments").param("orderId", order.toString()).with(me())).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(listed)).hasSize(1);
        assertThat(body(listed).get(0).get("id").asString()).isEqualTo(id.toString());
        assertThat(body(mvc.perform(get("/api/v1/payments/provider-payments").param("orderId", order.toString()).with(who("kc-" + UUID.randomUUID())))
                .andReturn())).isEmpty();
    }
}
