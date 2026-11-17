package com.oriah.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * What payment's published OpenAPI document says, read from the real springdoc output: the document is the contract the frontend's types and
 * the Postman collection are generated from, not the annotations.
 *
 * <p>Phase 13 retired the first card path. Its two operations (create and confirm an intent) and the staff list of intents are no longer in
 * the document, and nothing under {@code /internal} ever is: an internal door is not a contract anyone outside may build against.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// The same annotations as AdminPaymentSummaryTest, so this shares its cached
// context instead of booting another.
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class OpenApiContractTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private JsonNode spec() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(json);
    }

    @Test
    @DisplayName("the retired card doors and the staff list of intents are not in the document")
    void theRetiredDoorsAreNotPublished() throws Exception {
        JsonNode paths = spec().get("paths");

        assertThat(paths.has("/api/v1/payments/intents")).isFalse();
        assertThat(paths.has("/api/v1/payments/intents/{id}")).isFalse();
        assertThat(paths.has("/api/v1/payments/intents/{id}/confirm")).isFalse();
        assertThat(paths.has("/api/v1/admin/payments")).isFalse();
        assertThat(paths.has("/api/v1/admin/payments/summary")).isFalse();
        // What stays is still published.
        assertThat(paths.has("/api/v1/admin/payments/confirmations")).isTrue();
    }

    @Test
    @DisplayName("no internal door is published")
    void noInternalDoorIsPublished() throws Exception {
        java.util.List<String> internal = new java.util.ArrayList<>();
        spec().get("paths").fieldNames().forEachRemaining(path -> {
            if (path.startsWith("/internal")) {
                internal.add(path);
            }
        });

        assertThat(internal).isEmpty();
    }
}
