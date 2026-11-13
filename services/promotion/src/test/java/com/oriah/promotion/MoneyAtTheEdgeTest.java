package com.oriah.promotion;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A discount is a whole number of ngwee (MVP_PLAN Phase 0, item 7): a promotion's amount finer than that is a 400 on that field. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class MoneyAtTheEdgeTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("every money field in promotion's requests carries the ngwee rule")
    void everyMoneyFieldIsGuarded() {
        assertThat(MoneyEdgeGuard.requestRecordCount("com.oriah.promotion.dto")).isPositive();
        assertThat(MoneyEdgeGuard.unguardedMoneyFields("com.oriah.promotion.dto")).isEmpty();
    }

    @Test
    @DisplayName("a discount finer than a ngwee is a 400 on the amount field")
    void aDiscountFinerThanANgweeIsRefused() throws Exception {
        mvc.perform(post("/api/v1/admin/promotions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"scopeType\":\"SHOP\",\"discountType\":\"FIXED\",\"amount\":10.005,"
                                + "\"startsOn\":\"2026-12-01\",\"endsOn\":\"2026-12-31\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("amount")));
    }
}
