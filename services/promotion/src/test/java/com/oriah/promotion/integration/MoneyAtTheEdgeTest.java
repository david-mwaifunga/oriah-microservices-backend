package com.oriah.promotion.integration;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
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
import com.oriah.promotion.TestcontainersConfiguration;
import com.oriah.promotion.model.Promotion;
import com.oriah.promotion.model.PromotionType;
import com.oriah.promotion.repository.PromotionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Promotion amounts and subtotals are whole numbers of ngwee (MVP_PLAN Phase 0, item 7). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class MoneyAtTheEdgeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PromotionRepository repository;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("every money field in promotion's requests carries the ngwee rule")
    void everyMoneyFieldIsGuarded() {
        assertThat(MoneyEdgeGuard.requestRecordCount("com.oriah.promotion.dto")).isPositive();
        assertThat(MoneyEdgeGuard.unguardedMoneyFields("com.oriah.promotion.dto")).isEmpty();
    }

    @Test
    @DisplayName("a promotion amount finer than a ngwee is a 400, and nothing is created")
    void anAmountFinerThanANgweeIsRefused() throws Exception {
        mvc.perform(post("/api/v1/admin/promotions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"TINY\",\"type\":\"FIXED\",\"amount\":10.005,\"maxRedemptions\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("amount")));
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("a promotion cannot be built with an amount finer than a ngwee")
    void theAggregateRefusesItToo() {
        assertThatThrownBy(() -> new Promotion("TINY", PromotionType.FIXED, new BigDecimal("10.005"), null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10.005");
    }
}