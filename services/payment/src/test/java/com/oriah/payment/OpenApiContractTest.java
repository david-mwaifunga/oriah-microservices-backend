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
 * What payment's published OpenAPI document says about creating and
 * confirming an intent, read from the real springdoc output -- the document
 * is the contract the frontend's types and the Postman collection are
 * generated from, not the annotations.
 *
 * <p>Phase 1 gave both operations new answers, and a client that does not
 * know them treats them as generic failures:
 * <ul>
 *   <li>createIntent: <b>409</b> when the order already has an intent that is
 *       pending, being charged or paid -- one order is paid through one
 *       intent. The shared error advice turns the refusal into a 409 without
 *       springdoc seeing it, so only an explicit annotation publishes it;</li>
 *   <li>confirm: <b>409</b> also when the intent is not chargeable -- being
 *       charged (its outcome is not known yet) or already settled -- or a
 *       request with the same Idempotency-Key is still in progress;</li>
 *   <li>confirm: <b>503</b> when the outcome is not known yet. Not a decline:
 *       the money may have been taken, the intent is never charged again, and
 *       it settles shortly.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// The same annotations as AdminPaymentSummaryTest, so this shares its cached
// context instead of booting another.
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class OpenApiContractTest {

    private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";
    private static final String INTENTS = "/api/v1/payments/intents";
    private static final String CONFIRM = "/api/v1/payments/intents/{id}/confirm";

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
    @DisplayName("createIntent documents 409: the order already has a live intent, as a ProblemDetail")
    void createIntentDocumentsTheOneLiveIntentRule() throws Exception {
        JsonNode conflict = spec().path("paths").path(INTENTS).path("post").path("responses").path("409");

        assertThat(conflict.isMissingNode())
                .as("createIntent answers 409 when the order already has a live intent").isFalse();
        assertThat(conflict.path("description").asText())
                .contains("already has a payment intent")
                .contains("pending, being charged or paid")
                .contains("only if that one fails");
        assertThat(conflict.toString()).contains(PROBLEM_REF);
    }

    @Test
    @DisplayName("confirm documents 503: the outcome is not known yet, as a ProblemDetail")
    void confirmDocumentsOutcomeUnknown() throws Exception {
        JsonNode unavailable = spec().path("paths").path(CONFIRM).path("post").path("responses").path("503");

        assertThat(unavailable.isMissingNode()).as("confirm answers 503 when the outcome is unknown").isFalse();
        assertThat(unavailable.path("description").asText())
                .contains("not known yet")
                .contains("never charged again");
        assertThat(unavailable.toString()).contains(PROBLEM_REF);
    }

    @Test
    @DisplayName("confirm documents every 409: not chargeable, in progress, key bound elsewhere")
    void confirmDocumentsEveryConflict() throws Exception {
        JsonNode conflict = spec().path("paths").path(CONFIRM).path("post").path("responses").path("409");

        assertThat(conflict.path("description").asText())
                .contains("being charged")
                .contains("already settled")
                .contains("still in progress")
                .contains("different payment intent");
        assertThat(conflict.toString()).contains(PROBLEM_REF);
    }

    @Test
    @DisplayName("CHARGING is a published payment status")
    void chargingIsPublished() throws Exception {
        JsonNode statuses = spec().path("components").path("schemas")
                .path("PaymentIntentResponse").path("properties").path("status").path("enum");

        assertThat(statuses.toString()).contains("\"CHARGING\"");
    }
}
