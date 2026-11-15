package com.oriah.finance.controller;

import java.math.BigDecimal;
import java.util.List;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.finance.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Turnover tax paid to ZRA, typed in by hand (nothing calculates it in the MVP): the month it is FOR, the amount and the day it was paid. It is booked to the month it is
 * for and shown BELOW the profit line, never among operating expenses. Append-only like an expense (reverse, correct, no update). The tax is chosen from a closed list of taxes
 * on the business itself; duty, surtax and import VAT are refused, because they are paid at clearance and reach profit through the cost of goods: entering one here would count
 * it twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class TaxHttpTest {

    private static final String PATH = "/api/v1/admin/finance/tax-entries";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor bookkeeper() {
        return jwt().jwt(j -> j.subject("kc-bookkeeper")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"));
    }

    private static RequestPostProcessor role(String role) {
        return jwt().jwt(j -> j.subject("kc-someone")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private MvcResult send(String path, String key, String body, RequestPostProcessor as) throws Exception {
        MockHttpServletRequestBuilder request = post(path).with(as).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String type(JsonNode problem) {
        return problem.get("type").asText();
    }

    private static String tax(String taxType, String forMonth, String amount, String paidOn) {
        return "{\"taxType\":\"" + taxType + "\",\"forMonth\":\"" + forMonth + "\",\"amount\":" + amount + ",\"paidOn\":\"" + paidOn + "\"}";
    }

    private BigDecimal taxOf(String month) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE account = 'TAX_ENTERED' AND effect_month = ?::date", BigDecimal.class, month);
    }

    @Test
    @DisplayName("turnover tax is booked to the month it is FOR, not the day it was paid, below the profit line; the entry says who typed it, and is read back by id and in the month's list")
    void aTaxEntry() throws Exception {
        MvcResult created = send(PATH, key(), tax("TURNOVER_TAX", "2025-05", "350.00", "2025-06-20"), bookkeeper());

        JsonNode t = ok(created, 201);

        assertThat(created.getResponse().getHeader("Location")).endsWith("/tax-entries/" + t.get("id").asText());
        assertThat(t.get("taxType").asText()).isEqualTo("TURNOVER_TAX");
        assertThat(t.get("forMonth").asText()).isEqualTo("2025-05");
        assertThat(t.get("paidOn").asText()).isEqualTo("2025-06-20");
        assertThat(t.get("amount").decimalValue()).isEqualByComparingTo("350.00");
        assertThat(t.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(t.get("createdBy").asText()).isEqualTo("user:kc-bookkeeper");
        assertThat(taxOf("2025-05-01")).as("booked to the month it is for").isEqualByComparingTo("350.00");
        assertThat(taxOf("2025-06-01")).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT account FROM ledger_entry WHERE entry_key = ?", String.class, "tax:" + t.get("id").asText())).isEqualTo("TAX_ENTERED");
        JsonNode list = ok(mvc.perform(get(PATH + "?month=2025-05").with(bookkeeper())).andReturn(), 200);
        assertThat(list).hasSize(1);
        assertThat(ok(mvc.perform(get(PATH + "/" + t.get("id").asText()).with(bookkeeper())).andReturn(), 200).get("reference").isNull()).isTrue();
    }

    @Test
    @DisplayName("duty, surtax and import VAT are refused in every spelling, with the plain reason: they reach profit through the cost of goods, so entering one here would count it twice; any other tax is unknown")
    void customsChargesAreRefused() throws Exception {
        for (String spelling : List.of("DUTY", "duty", "Surtax", "IMPORT_VAT", "import vat", "Import-VAT", "customs duty", "CUSTOMS_DUTY")) {
            JsonNode problem = ok(send(PATH, key(), tax(spelling, "2025-05", "100.00", "2025-06-01"), bookkeeper()), 400);
            assertThat(type(problem)).as(spelling).endsWith("/customs-charge-not-a-tax-entry");
            assertThat(problem.get("detail").asText()).contains("count it twice");
        }
        assertThat(type(ok(send(PATH, key(), tax("INCOME_TAX", "2025-05", "100.00", "2025-06-01"), bookkeeper()), 400))).endsWith("/unknown-tax");
        assertThat(taxOf("2025-05-01")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("what is refused: an amount of nothing or finer than an ngwee, a month in the future or older than five years, a day paid in the future, a phone number in the reference")
    void validation() throws Exception {
        assertThat(type(ok(send(PATH, key(), tax("TURNOVER_TAX", "2025-05", "0.00", "2025-06-01"), bookkeeper()), 400))).isNotEmpty();
        assertThat(type(ok(send(PATH, key(), tax("TURNOVER_TAX", "2025-05", "10.005", "2025-06-01"), bookkeeper()), 400))).isNotEmpty();
        assertThat(type(ok(send(PATH, key(), tax("TURNOVER_TAX", "2999-01", "10.00", "2025-06-01"), bookkeeper()), 400))).endsWith("/invalid-month");
        assertThat(type(ok(send(PATH, key(), tax("TURNOVER_TAX", "2015-01", "10.00", "2025-06-01"), bookkeeper()), 400))).endsWith("/invalid-month");
        assertThat(type(ok(send(PATH, key(), tax("TURNOVER_TAX", "2025-13", "10.00", "2025-06-01"), bookkeeper()), 400))).endsWith("/invalid-month");
        assertThat(type(ok(send(PATH, key(), tax("TURNOVER_TAX", "2025-05", "10.00", "2999-06-01"), bookkeeper()), 400))).endsWith("/invalid-paid-date");
        assertThat(type(ok(send(PATH, key(), "{\"taxType\":\"TURNOVER_TAX\",\"forMonth\":\"2025-05\",\"amount\":10.00,\"paidOn\":\"2025-06-01\",\"reference\":\"ZRA 0971234567\"}", bookkeeper()), 400)))
                .endsWith("/no-phone-numbers");
    }

    @Test
    @DisplayName("a write sent again with the same key is the same entry; with another request it is refused; with no key it is 400")
    void idempotency() throws Exception {
        String key = key();
        String body = tax("TURNOVER_TAX", "2025-04", "120.00", "2025-05-20");

        JsonNode first = ok(send(PATH, key, body, bookkeeper()), 201);
        JsonNode again = ok(send(PATH, key, body, bookkeeper()), 200);

        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(type(ok(send(PATH, key, tax("TURNOVER_TAX", "2025-04", "121.00", "2025-05-20"), bookkeeper()), 409))).endsWith("/idempotency-key-reused");
        assertThat(type(ok(send(PATH, null, body, bookkeeper()), 400))).endsWith("/idempotency-key-required");
        assertThat(taxOf("2025-04-01")).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("a tax entry is reversed once, with a reason, on the month it was for, and netted out; a reversal is not reversible; a correction is a reversal plus a new entry, and one that changes nothing is refused")
    void reverseAndCorrect() throws Exception {
        JsonNode t = ok(send(PATH, key(), tax("TURNOVER_TAX", "2025-03", "200.00", "2025-04-18"), bookkeeper()), 201);
        String id = t.get("id").asText();

        JsonNode reversal = ok(send(PATH + "/" + id + "/reversal", key(), "{\"reason\":\"paid in the wrong month\"}", bookkeeper()), 201);

        assertThat(reversal.get("status").asText()).isEqualTo("REVERSAL");
        assertThat(reversal.get("amount").decimalValue()).as("the amount that was taken back").isEqualByComparingTo("200.00");
        assertThat(taxOf("2025-03-01")).isEqualByComparingTo("0.00");
        assertThat(ok(mvc.perform(get(PATH + "/" + id).with(bookkeeper())).andReturn(), 200).get("status").asText()).isEqualTo("REVERSED");
        assertThat(type(ok(send(PATH + "/" + id + "/reversal", key(), "{\"reason\":\"paid in the wrong month\"}", bookkeeper()), 409))).endsWith("/tax-entry-already-reversed");
        assertThat(type(ok(send(PATH + "/" + reversal.get("id").asText() + "/reversal", key(), "{\"reason\":\"undo the undo\"}", bookkeeper()), 409))).endsWith("/not-reversible");

        JsonNode second = ok(send(PATH, key(), tax("TURNOVER_TAX", "2025-02", "300.00", "2025-03-18"), bookkeeper()), 201);
        JsonNode corrected = ok(send(PATH + "/" + second.get("id").asText() + "/correction", key(), "{\"reason\":\"the receipt says 310\",\"amount\":310.00}", bookkeeper()), 201);
        assertThat(corrected.get("amount").decimalValue()).isEqualByComparingTo("310.00");
        assertThat(corrected.get("forMonth").asText()).isEqualTo("2025-02");
        assertThat(taxOf("2025-02-01")).isEqualByComparingTo("310.00");
        assertThat(type(ok(send(PATH + "/" + corrected.get("id").asText() + "/correction", key(), "{\"reason\":\"just looking again\"}", bookkeeper()), 400))).endsWith("/correction-changes-nothing");
    }

    @Test
    @DisplayName("a tax entered for a month that was already reported needs a reason and is marked")
    void anIssuedMonthNeedsAReason() throws Exception {
        jdbc.update("INSERT INTO report_issue (issue_id, month, issue_no, as_of, issued_by, snapshot) VALUES (?, '2025-01-01', 1, now(), 'user:kc-owner', '{}'::jsonb)", UUID.randomUUID());

        assertThat(type(ok(send(PATH, key(), tax("TURNOVER_TAX", "2025-01", "90.00", "2025-02-20"), bookkeeper()), 400))).endsWith("/reason-required-for-issued-month");
        JsonNode t = ok(send(PATH, key(), "{\"taxType\":\"TURNOVER_TAX\",\"forMonth\":\"2025-01\",\"amount\":90.00,\"paidOn\":\"2025-02-20\",\"reason\":\"ZRA assessed it after we reported\"}", bookkeeper()), 201);

        assertThat(t.get("inIssuedMonth").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the only tax on the list is turnover tax; only FINANCE_ADMIN reads or writes; the manager, a shopper and a salesperson are refused")
    void listAndRoles() throws Exception {
        JsonNode types = ok(mvc.perform(get("/api/v1/admin/finance/tax-types").with(bookkeeper())).andReturn(), 200);

        assertThat(types).hasSize(1);
        assertThat(types.get(0).get("code").asText()).isEqualTo("TURNOVER_TAX");
        assertThat(mvc.perform(get(PATH)).andReturn().getResponse().getStatus()).isEqualTo(401);
        for (String role : List.of("USER", "MANAGER", "SALES_AGENT", "COST_VIEWER")) {
            assertThat(mvc.perform(get(PATH).with(role(role))).andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
            assertThat(send(PATH, key(), tax("TURNOVER_TAX", "2025-05", "1.00", "2025-06-01"), role(role)).getResponse().getStatus()).as(role).isEqualTo(403);
        }
    }
}
