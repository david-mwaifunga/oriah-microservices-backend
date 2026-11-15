package com.oriah.finance;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * What finance's OpenAPI document says is true and worth having. A client generator and the bookkeeper's screens are built from it, so each call says what it answers when it
 * works (201 for a new entry, 200 for the same request repeated), and every way it can be refused: 400, 401, 403 and 500 as the problem document the service really returns
 * ({@code ProblemDetail}), and 409 where a repeated key or a state can refuse it. The cross-service gate ({@code scripts/openapi_audit.py}) checks the same on the captured
 * documents; this proves it here, before the stack is up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class OpenApiContractTest {

    private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private JsonNode spec() throws Exception {
        return JSON.readTree(mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
    }

    /** Every operation as "METHOD path" -> its node. */
    private Map<String, JsonNode> operations() throws Exception {
        Map<String, JsonNode> operations = new java.util.TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> paths = spec().get("paths").properties().iterator();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> path = paths.next();
            for (Map.Entry<String, JsonNode> method : path.getValue().properties()) {
                operations.put(method.getKey().toUpperCase() + " " + path.getKey(), method.getValue());
            }
        }
        return operations;
    }

    @Test
    @DisplayName("every finance call has a pinned operationId and a tag that was chosen, never the controller's name")
    void everyCallIsNamed() throws Exception {
        Map<String, JsonNode> operations = operations();

        assertThat(operations).as("the finance calls published").hasSize(27);
        List<String> unnamed = new ArrayList<>();
        for (Map.Entry<String, JsonNode> op : operations.entrySet()) {
            JsonNode id = op.getValue().get("operationId");
            if (id == null || id.asText().matches(".*_\\d+$")) {
                unnamed.add(op.getKey());
            }
            for (JsonNode tag : op.getValue().path("tags")) {
                assertThat(tag.asText()).as(op.getKey() + " is tagged with its class name").doesNotEndWith("-controller");
            }
        }
        assertThat(unnamed).as("calls with no pinned operationId").isEmpty();
    }

    @Test
    @DisplayName("every finance call documents 401, 403 and 500 (and 400 when it takes input) as a ProblemDetail, and ProblemDetail is published")
    void everyErrorIsAProblem() throws Exception {
        List<String> undocumented = new ArrayList<>();
        for (Map.Entry<String, JsonNode> op : operations().entrySet()) {
            boolean takesInput = op.getValue().has("parameters") || op.getValue().has("requestBody");
            for (String code : takesInput ? List.of("400", "401", "403", "500") : List.of("401", "403", "500")) {
                JsonNode response = op.getValue().path("responses").path(code);
                if (response.isMissingNode() || !response.toString().contains(PROBLEM_REF)) {
                    undocumented.add(op.getKey() + " " + code);
                }
            }
        }

        assertThat(undocumented).as("errors not documented as a ProblemDetail").isEmpty();
        assertThat(spec().path("components").path("schemas").has("ProblemDetail")).isTrue();
    }

    @Test
    @DisplayName("a new entry is documented 201, the same call repeated 200; and a repeated key or a refused state is documented 409")
    void everyWriteSaysWhatItAnswers() throws Exception {
        Map<String, JsonNode> operations = operations();
        String base = "/api/v1/admin/finance";
        List<String> writes = List.of("POST " + base + "/expenses", "POST " + base + "/expenses/{id}/reversal", "POST " + base + "/expenses/{id}/correction",
                "POST " + base + "/tax-entries", "POST " + base + "/tax-entries/{id}/reversal", "POST " + base + "/tax-entries/{id}/correction", "POST " + base + "/statement-checks",
                "POST " + base + "/reports/profit/{month}/issue", "POST " + base + "/anomalies/{id}/ack", "POST " + base + "/catch-up/reverify");

        for (String write : writes) {
            JsonNode responses = operations.get(write).get("responses");
            assertThat(responses.has("201")).as(write + " documents 201").isTrue();
            assertThat(responses.has("200")).as(write + " documents 200: the same request repeated").isTrue();
            assertThat(responses.has("409")).as(write + " documents 409").isTrue();
        }
        assertThat(operations.get("POST " + base + "/anomalies/{id}/ack").get("responses").has("404")).as("acknowledging an anomaly that is not there").isTrue();
        assertThat(operations.get("POST " + base + "/expenses/{id}/reversal").get("responses").has("404")).as("reversing an expense that is not there").isTrue();
        assertThat(operations.get("GET " + base + "/expenses/{id}").get("responses").has("404")).as("reading an expense that is not there").isTrue();
    }
}
