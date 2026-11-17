package com.oriah.payment.integration;

import java.math.BigDecimal;
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

import com.oriah.money.MoneyEdgeGuard;
import com.oriah.payment.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A charge is a whole number of ngwee (MVP_PLAN Phase 0, item 7). The amount
 * column is NUMERIC(12,2): an intent for 100.005 would be stored, and charged,
 * as 100.01 -- an amount the order never asked for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class MoneyAtTheEdgeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("every money field in payment's requests carries the ngwee rule")
    void everyMoneyFieldIsGuarded() {
        assertThat(MoneyEdgeGuard.requestRecordCount("com.oriah.payment.dto")).isPositive();
        assertThat(MoneyEdgeGuard.unguardedMoneyFields("com.oriah.payment.dto")).isEmpty();
    }

    @Test
    @DisplayName("money confirmed for a fraction of a ngwee is a 400, and nothing is recorded")
    void anAmountFinerThanANgweeIsRefused() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM payment_confirmation", Long.class);
        mvc.perform(post("/api/v1/admin/payments/confirmations")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")).jwt(j -> j.subject("kc-sara")))
                        .header("Idempotency-Key", "edge-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"method\":\"CASH\",\"gross\":100.005}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("gross")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_confirmation", Long.class)).isEqualTo(before);
    }
}
