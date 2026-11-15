package com.oriah.finance.controller;

import java.util.List;
import java.util.UUID;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.finance.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The month is reconciled against the provider's own statement: the bookkeeper types in what each provider says it charged in fees for the month, and the report shows the gap
 * against what finance recorded (to the ngwee: fees are exact, so the tolerance is K0.00). Each entry is kept; the latest per month and provider is the one that counts, and none
 * is ever changed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class StatementCheckHttpTest {

    private static final String PATH = "/api/v1/admin/finance/statement-checks";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor bookkeeper() {
        return jwt().jwt(j -> j.subject("kc-bookkeeper")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"));
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private MvcResult send(String key, String body, RequestPostProcessor as) throws Exception {
        MockHttpServletRequestBuilder request = post(PATH).with(as).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String check(String month, String method, String fees) {
        return "{\"month\":\"" + month + "\",\"method\":\"" + method + "\",\"statementFees\":" + fees + "}";
    }

    @Test
    @DisplayName("what a provider's statement says it charged is kept, by whom, for a month and a provider; it is read back by month, and the latest for a provider is the one that counts")
    void aStatementCheck() throws Exception {
        JsonNode first = ok(send(key(), check("2024-03", "MTN", "180.50"), bookkeeper()), 201);
        JsonNode second = ok(send(key(), "{\"month\":\"2024-03\",\"method\":\"MTN\",\"statementFees\":181.00,\"note\":\"the statement was corrected\"}", bookkeeper()), 201);
        ok(send(key(), check("2024-03", "AIRTEL", "40.00"), bookkeeper()), 201);

        JsonNode all = ok(mvc.perform(get(PATH + "?month=2024-03").with(bookkeeper())).andReturn(), 200);

        assertThat(first.get("enteredBy").asText()).isEqualTo("user:kc-bookkeeper");
        assertThat(second.get("note").asText()).isEqualTo("the statement was corrected");
        assertThat(all).as("every entry is kept").hasSize(3);
        JsonNode latest = ok(mvc.perform(get(PATH + "/latest?month=2024-03").with(bookkeeper())).andReturn(), 200);
        assertThat(latest).as("one per provider: the latest").hasSize(2);
        for (JsonNode c : latest) {
            if (c.get("method").asText().equals("MTN")) {
                assertThat(c.get("statementFees").decimalValue()).isEqualByComparingTo("181.00");
            }
        }
    }

    @Test
    @DisplayName("what is refused: fees below nothing or finer than an ngwee, a month in the future or not a month, a provider that is not a plain code")
    void validation() throws Exception {
        assertThat(ok(send(key(), check("2024-03", "MTN", "-1.00"), bookkeeper()), 400).get("type").asText()).isNotEmpty();
        assertThat(ok(send(key(), check("2024-03", "MTN", "1.005"), bookkeeper()), 400).get("type").asText()).isNotEmpty();
        assertThat(ok(send(key(), check("2999-03", "MTN", "10.00"), bookkeeper()), 400).get("type").asText()).endsWith("/invalid-month");
        assertThat(ok(send(key(), check("March", "MTN", "10.00"), bookkeeper()), 400).get("type").asText()).endsWith("/invalid-month");
        assertThat(ok(send(key(), check("2024-03", "mtn money 0971234567", "10.00"), bookkeeper()), 400).get("type").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("sent again with the same key it is the same entry; no key is 400; only FINANCE_ADMIN may enter or read")
    void idempotencyAndRoles() throws Exception {
        String key = key();
        JsonNode first = ok(send(key, check("2024-02", "MTN", "10.00"), bookkeeper()), 201);
        JsonNode again = ok(send(key, check("2024-02", "MTN", "10.00"), bookkeeper()), 200);

        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(ok(send(null, check("2024-02", "MTN", "10.00"), bookkeeper()), 400).get("type").asText()).endsWith("/idempotency-key-required");
        for (String role : List.of("USER", "MANAGER", "COST_VIEWER")) {
            RequestPostProcessor as = jwt().jwt(j -> j.subject("kc-x")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
            assertThat(mvc.perform(get(PATH + "?month=2024-02").with(as)).andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
            assertThat(send(key(), check("2024-02", "MTN", "1.00"), as).getResponse().getStatus()).as(role).isEqualTo(403);
        }
    }
}
