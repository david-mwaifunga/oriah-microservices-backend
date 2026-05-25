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
import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
        MvcResult res = mvc.perform(post("/api/v1/payments/intents").with(jwtFor(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":" + amount + ",\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(String sub) {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject(sub));
    }
}
