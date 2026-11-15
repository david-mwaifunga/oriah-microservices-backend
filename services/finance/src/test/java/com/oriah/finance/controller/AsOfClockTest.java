package com.oriah.finance.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.finance.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A report is cut on the DATABASE's clock, the same one that stamps every ledger row (recorded_at). If it were cut on the service's own clock, a machine whose clock is behind the
 * database's would leave a row that was just committed out of "the books as they are now".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AsOfClockTest.BehindTheDatabase.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class AsOfClockTest {

    /** A service clock that is years behind the database's. */
    @TestConfiguration
    static class BehindTheDatabase {
        @Bean
        @Primary
        Clock behind() {
            return Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("a row committed just now is in the report asked for now, whatever the service's own clock says")
    void theReportIsCutOnTheDatabaseClock() throws Exception {
        jdbc.update("INSERT INTO ledger_entry (entry_key, account, amount, occurred_at, effect_day, source, actor) VALUES (?, 'REVENUE_GOODS', 10.00, now(), '2018-11-05', 'ORDER_PAID', 'system:test')",
                "asof-clock-" + java.util.UUID.randomUUID());

        JsonNode report = JsonMapper.builder().build().readTree(mvc.perform(get("/api/v1/admin/finance/reports/profit/2018-11")
                .with(jwt().jwt(j -> j.subject("kc-bookkeeper")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN")))).andReturn().getResponse().getContentAsString());

        assertThat(report.get("lines").get(0).get("amount").decimalValue()).isEqualByComparingTo("10.00");
    }
}
