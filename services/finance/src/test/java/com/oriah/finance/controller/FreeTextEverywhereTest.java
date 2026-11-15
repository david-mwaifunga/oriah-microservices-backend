package com.oriah.finance.controller;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.oriah.finance.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Everything a person types into the books is kept for good in rows that are never edited, so it names no one: a phone number is refused wherever a person can type text (a reason,
 * a note, a description, a reference), not in some of those places. And a payment provider is not free text at all: it is a plain code, written the way the statement check writes
 * it, so a bill typed against "mtn" is reconciled with the statement of "MTN".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class FreeTextEverywhereTest {

    private static final String BASE = "/api/v1/admin/finance";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String NUMBER = "customer 0977 123 456 asked";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private MvcResult send(String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).with(jwt().jwt(j -> j.subject("kc-bookkeeper")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN")))
                .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode answer(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private void refusedAsAPhoneNumber(MvcResult result) throws Exception {
        assertThat(answer(result, 400).get("type").asText()).endsWith("/no-phone-numbers");
    }

    @Test
    @DisplayName("a phone number in a reason, a note or a reference is refused, on every call that takes one")
    void everyTypedStringIsScreened() throws Exception {
        String id = answer(send(BASE + "/expenses", "{\"category\":\"RENT_UTILITIES\",\"amount\":10.00,\"expenseDate\":\"2018-03-10\",\"description\":\"rent\"}"), 201).get("id").asText();
        refusedAsAPhoneNumber(send(BASE + "/expenses/" + id + "/reversal", "{\"reason\":\"" + NUMBER + "\"}"));
        refusedAsAPhoneNumber(send(BASE + "/expenses/" + id + "/correction", "{\"reason\":\"" + NUMBER + "\",\"amount\":11.00}"));
        refusedAsAPhoneNumber(send(BASE + "/expenses", "{\"category\":\"RENT_UTILITIES\",\"amount\":10.00,\"expenseDate\":\"2018-03-10\",\"description\":\"rent\",\"reason\":\"" + NUMBER + "\"}"));
        refusedAsAPhoneNumber(send(BASE + "/tax-entries", "{\"taxType\":\"TURNOVER_TAX\",\"forMonth\":\"2024-03\",\"amount\":5.00,\"paidOn\":\"2024-04-10\",\"reason\":\"" + NUMBER + "\"}"));
        long anomaly = jdbc.queryForObject("INSERT INTO anomaly_log (code, subject, action, severity) VALUES ('test-error', ?, 'RAISE', 'WARN') RETURNING id", Long.class, "free-" + UUID.randomUUID());
        refusedAsAPhoneNumber(send(BASE + "/anomalies/" + anomaly + "/ack", "{\"note\":\"" + NUMBER + "\"}"));
        refusedAsAPhoneNumber(send(BASE + "/reports/profit/2018-03/issue", "{\"acknowledgeIncomplete\":true,\"note\":\"" + NUMBER + "\"}"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense WHERE reason LIKE '%0977%'", Long.class)).as("nothing with a number was kept").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly_log WHERE note LIKE '%0977%'", Long.class)).isZero();
    }

    @Test
    @DisplayName("a provider is a plain code, kept in capitals so it meets the statement check's; anything else is refused")
    void aProviderIsACode() throws Exception {
        String account = "\"category\":\"BANK_MOBILE_ACCOUNT_FEES\",\"amount\":7.00,\"expenseDate\":\"2018-03-10\",\"description\":\"monthly fee\"";

        JsonNode fine = answer(send(BASE + "/expenses", "{" + account + ",\"provider\":\" mtn \"}"), 201);

        assertThat(fine.get("provider").asText()).isEqualTo("MTN");
        assertThat(answer(send(BASE + "/expenses", "{" + account + ",\"provider\":\"0977123456\"}"), 400).get("type").asText()).isNotEmpty();
        assertThat(answer(send(BASE + "/expenses", "{" + account + ",\"provider\":\"Mr Banda at the shop\"}"), 400).get("type").asText()).isNotEmpty();
    }
}
