package com.oriah.finance;

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

import com.oriah.money.MoneyEdgeGuard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An amount Oriah records is a whole number of ngwee (MVP_PLAN Phase 0, item 7): Postgres would store 1200.005 as 1200.01 without a word, and the books would carry a figure
 * nobody typed. It is refused at the edge with a field error the person can act on, and nothing is written.
 *
 * <p>The guard also walks every request record in {@code dto}, so a money field added in a later phase fails this test until it carries the rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class MoneyAtTheEdgeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("every money field in finance's requests carries the ngwee rule")
    void everyMoneyFieldIsGuarded() {
        assertThat(MoneyEdgeGuard.requestRecordCount("com.oriah.finance.dto")).isPositive();
        assertThat(MoneyEdgeGuard.unguardedMoneyFields("com.oriah.finance.dto")).isEmpty();
    }

    @Test
    @DisplayName("an expense of a tenth of a ngwee is refused naming the field, and nothing is written")
    void anAmountFinerThanANgweeIsRefused() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);

        mvc.perform(post("/api/v1/admin/finance/expenses").with(jwt().jwt(j -> j.subject("kc-book")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN")))
                        .header("Idempotency-Key", "k-ngwee").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"RENT_UTILITIES\",\"amount\":1200.005,\"expenseDate\":\"2026-10-15\",\"description\":\"October rent\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("amount")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class)).isEqualTo(before);
    }
}
