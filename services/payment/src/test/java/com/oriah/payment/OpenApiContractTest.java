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
 *   <li>createIntent: <b>200</b> with the intent already made, when the
 *       caller sends the same create again (checkout's retry after a lost
 *       answer); <b>404</b> when the order is not the caller's or does not
 *       exist; <b>400</b> also when the amount or currency is not the order's
 *       total; <b>503</b> when order could not be asked;</li>
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

    private JsonNode createIntentResponses() throws Exception {
        return spec().path("paths").path(INTENTS).path("post").path("responses");
    }

    @Test
    @DisplayName("createIntent documents 200: the caller's pending intent for the same amount, already made")
    void createIntentDocumentsTheSameIntentAgain() throws Exception {
        // checkout retries a create whose answer was lost; the retry is given
        // the intent already made. A client that knows only 201 would treat
        // that 200 as unexpected.
        JsonNode ok = createIntentResponses().path("200");

        assertThat(ok.isMissingNode()).as("createIntent answers 200 for the same create again").isFalse();
        assertThat(ok.path("description").asText())
                .contains("already")
                .contains("same amount and currency")
                .contains("PENDING");
        assertThat(ok.toString()).contains("#/components/schemas/PaymentIntentResponse");
    }

    @Test
    @DisplayName("createIntent still documents 201 for a new intent")
    void createIntentDocumentsCreated() throws Exception {
        assertThat(createIntentResponses().path("201").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("createIntent documents 400: also an amount or currency that is not the order's total")
    void createIntentDocumentsTheWrongAmount() throws Exception {
        JsonNode badRequest = createIntentResponses().path("400");

        assertThat(badRequest.path("description").asText())
                .contains("fieldErrors")
                .contains("not the order's total");
        assertThat(badRequest.toString()).contains(PROBLEM_REF);
    }

    @Test
    @DisplayName("createIntent documents 404: no order of the caller's has this id")
    void createIntentDocumentsNoSuchOrderOfYours() throws Exception {
        JsonNode notFound = createIntentResponses().path("404");

        assertThat(notFound.isMissingNode()).as("createIntent answers 404 for an order that is not the caller's")
                .isFalse();
        assertThat(notFound.path("description").asText())
                .contains("No order of yours")
                .contains("nothing was created");
        assertThat(notFound.toString()).contains(PROBLEM_REF);
    }

    @Test
    @DisplayName("createIntent's 409 says a pending intent for a different amount or currency is refused too")
    void createIntentConflictCoversADifferentPendingIntent() throws Exception {
        assertThat(createIntentResponses().path("409").path("description").asText())
                .contains("different amount or currency");
    }

    @Test
    @DisplayName("createIntent's 409 says an order that is no longer waiting for payment is refused too")
    void createIntentConflictCoversAnOrderThatCannotBePaid() throws Exception {
        assertThat(createIntentResponses().path("409").path("description").asText())
                .contains("no longer waiting for payment")
                .contains("cancelled or already paid");
    }

    @Test
    @DisplayName("createIntent documents 503: order could not be asked, nothing created, retry")
    void createIntentDocumentsOrderUnavailable() throws Exception {
        JsonNode unavailable = createIntentResponses().path("503");

        assertThat(unavailable.isMissingNode()).as("createIntent answers 503 when order cannot be asked")
                .isFalse();
        assertThat(unavailable.path("description").asText())
                .contains("could not be asked")
                .contains("nothing was created")
                .contains("Retry-After");
        assertThat(unavailable.toString()).contains(PROBLEM_REF);
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
