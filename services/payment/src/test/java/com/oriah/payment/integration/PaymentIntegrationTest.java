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
    private ObjectMapper objectMapper;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
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
