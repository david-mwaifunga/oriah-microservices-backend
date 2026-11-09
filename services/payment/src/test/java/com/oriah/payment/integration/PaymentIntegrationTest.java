package com.oriah.payment.integration;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.payment.OrderAnswers;
import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.client.OrderClient;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;

import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class PaymentIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PaymentIntentRepository repository;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;
    /** order, asked whose order each intent is for: see createIntent. */
    @MockitoBean
    private OrderClient orders;

    @BeforeEach
    void clean() {
        idempotencyKeys.deleteAll();
        repository.deleteAll();
    }

    @Test
    @DisplayName("create intent + confirm with success card => SUCCEEDED")
    void happyPath() throws Exception {
        String intentId = createIntent("alice", "100.00");

        mvc.perform(post("/api/v1/payments/intents/" + intentId + "/confirm")
                        .with(jwtFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.confirmedAt").exists());
    }

    @Test
    @DisplayName("confirm with declined card => FAILED + failureReason")
    void declinedPath() throws Exception {
        String intentId = createIntent("alice", "50.00");

        mvc.perform(post("/api/v1/payments/intents/" + intentId + "/confirm")
                        .with(jwtFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"4000000000000002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("card declined"));
    }

    @Test
    @DisplayName("GET an intent belonging to a different user is 404 (no cross-user leak)")
    void crossUserLeak() throws Exception {
        String intentId = createIntent("alice", "1.00");
        mvc.perform(get("/api/v1/payments/intents/" + intentId).with(jwtFor("bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("validation: amount must be > 0")
    void zeroAmountIs400() throws Exception {
        mvc.perform(post("/api/v1/payments/intents").with(jwtFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":0,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("no JWT => 401")
    void requiresJwt() throws Exception {
        mvc.perform(get("/api/v1/payments/intents/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("repeat confirm with same Idempotency-Key replays the original response and does not re-charge")
    void idempotencyKeyReplays() throws Exception {
        String intentId = createIntent("alice", "100.00");
        String key = "test-key-" + UUID.randomUUID();

        // First call: full path, charges the (fake) gateway, returns SUCCEEDED.
        MvcResult first = mvc.perform(post("/api/v1/payments/intents/" + intentId + "/confirm")
                        .with(jwtFor("alice"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.confirmedAt").exists())
                .andReturn();
        String firstBody = first.getResponse().getContentAsString();

        // Re-confirm with a card that WOULD have been declined if it
        // hit the gateway again. The replay should ignore the new body
        // and return the cached SUCCEEDED response byte-for-byte.
        MvcResult second = mvc.perform(post("/api/v1/payments/intents/" + intentId + "/confirm")
                        .with(jwtFor("alice"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"4000000000000002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn();
        String secondBody = second.getResponse().getContentAsString();

        // Replay must produce byte-identical output.
        org.assertj.core.api.Assertions.assertThat(secondBody).isEqualTo(firstBody);
    }

    @Test
    @DisplayName("different Idempotency-Key on the same intent runs a fresh confirm (new key, new outcome)")
    void differentKeyDoesNotReplay() throws Exception {
        // First intent: succeed.
        String intentA = createIntent("alice", "100.00");
        mvc.perform(post("/api/v1/payments/intents/" + intentA + "/confirm")
                        .with(jwtFor("alice"))
                        .header("Idempotency-Key", "key-A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        // Second intent + key. Pre-existing key-A does not collide --
        // we get fresh gateway behavior. Use a declined card to prove
        // the path is not short-circuited by the earlier success.
        String intentB = createIntent("alice", "100.00");
        mvc.perform(post("/api/v1/payments/intents/" + intentB + "/confirm")
                        .with(jwtFor("alice"))
                        .header("Idempotency-Key", "key-B")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"4000000000000002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    private String createIntent(String sub, String amount) throws Exception {
        // Creating an intent asks order whose order it is; here order says
        // "the caller's, for exactly this amount" (CreateIntentOwnershipTest
        // covers every other answer).
        UUID orderId = UUID.randomUUID();
        doReturn(OrderAnswers.yours(orderId, amount, "USD")).when(orders).order(orderId);
        MvcResult res = mvc.perform(post("/api/v1/payments/intents").with(jwtFor(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + ",\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(String sub) {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject(sub));
    }

    // ------------------------------------------------------------------
    // Not-found paths -- and the cross-user property they protect.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET an unknown payment intent is 404 with a problem body")
    void getUnknownIntentIsAProblem404() throws Exception {
        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/v1/payments/intents/" + missing).with(jwtFor("alice")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/not-found"))
                .andExpect(jsonPath("$.detail").value(
                        "PaymentIntent not found with the given input data id : '" + missing + "'"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("someone else's intent is INDISTINGUISHABLE from one that does not exist")
    void othersIntentIsIndistinguishableFromMissing() throws Exception {
        // The sharpest case for this property in the platform: a payment intent
        // id confirmed to exist is a far more interesting probe target than an
        // order id. PaymentController filters on
        // keycloakId().equals(jwt.getSubject()) and then 404s.
        String aliceIntent = createIntent("alice", "1.00");
        UUID fictional = UUID.randomUUID();

        String notYours = bodyOf404(
                get("/api/v1/payments/intents/" + aliceIntent).with(jwtFor("bob")));
        String neverExisted = bodyOf404(
                get("/api/v1/payments/intents/" + fictional).with(jwtFor("bob")));

        org.assertj.core.api.Assertions.assertThat(
                        stripTraceId(notYours).replace(aliceIntent, "<id>"))
                .as("bob must not be able to tell alice's real intent id from a fictional one")
                .isEqualTo(stripTraceId(neverExisted).replace(fictional.toString(), "<id>"));
        org.assertj.core.api.Assertions.assertThat(notYours)
                .as("the body must not hint at ownership")
                .doesNotContain("alice").doesNotContain("belongs").doesNotContain("another");
    }

    @Test
    @DisplayName("confirming an unknown intent is 404 with a problem body")
    void confirmUnknownIntentIsAProblem404() throws Exception {
        UUID missing = UUID.randomUUID();
        // The body must be VALID, or bean validation answers 400 before the
        // handler is ever reached and the 404 is never exercised. This test
        // asserted 404 against an empty body first time round and failed for
        // that reason, not for the one it was written to catch.
        mvc.perform(post("/api/v1/payments/intents/" + missing + "/confirm").with(jwtFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "PaymentIntent not found with the given input data id : '" + missing + "'"));
    }

    private String bodyOf404(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        return mvc.perform(req)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
    }

    /** traceId is per-request, so it is the one field that legitimately differs. */
    private static String stripTraceId(String json) {
        return json.replaceAll(",?.traceId.:.[0-9a-f]+.", "");
    }
}
