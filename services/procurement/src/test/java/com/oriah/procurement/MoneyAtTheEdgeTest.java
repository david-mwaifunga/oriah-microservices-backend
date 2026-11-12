package com.oriah.procurement;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import com.jayway.jsonpath.JsonPath;
import com.oriah.money.MoneyEdgeGuard;
import com.oriah.procurement.client.CatalogClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An amount Oriah records is a whole number of ngwee (MVP_PLAN Phase 0, item 7).
 *
 * <p>The total paid for a line is NUMERIC(12,2): Postgres would store 10500.005 as 10500.01
 * without a word, and the books would carry a figure nobody typed. It is refused at the edge
 * instead, with a field error the person can act on, and nothing is saved or asked of catalog.
 *
 * <p>The guard also walks every request record in {@code dto}, so a money field added in a
 * later phase (a cost, a duty) fails this test until it carries the rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class MoneyAtTheEdgeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalog;

    @BeforeEach
    void clean() {
        ProcurementTestData.wipe(jdbc);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    @Test
    @DisplayName("every money field in procurement's requests carries the ngwee rule")
    void everyMoneyFieldIsGuarded() {
        assertThat(MoneyEdgeGuard.requestRecordCount("com.oriah.procurement.dto")).isPositive();
        assertThat(MoneyEdgeGuard.unguardedMoneyFields("com.oriah.procurement.dto")).isEmpty();
    }

    @Test
    @DisplayName("a line paid a tenth of a ngwee is refused naming the field, and nothing is saved or asked of catalog")
    void aTotalFinerThanANgweeIsRefused() throws Exception {
        String supplier = JsonPath.read(mvc.perform(post("/api/v1/admin/procurement/suppliers").with(maya())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Ngwee Ltd\",\"country\":\"AE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/v1/admin/procurement/purchase-orders").with(maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplier + "\",\"lines\":[{\"variantId\":\"" + UUID.randomUUID()
                                + "\",\"quantity\":1,\"totalPaid\":10500.005}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("lines[0].totalPaid")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class)).isZero();
        verifyNoInteractions(catalog);
    }
}
