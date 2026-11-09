package com.oriah.catalog.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.repository.ProductRepository;
import com.oriah.money.MoneyEdgeGuard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A price is a whole number of ngwee (MVP_PLAN Phase 0, item 7).
 *
 * <p>{@code unit_price} is NUMERIC(12,2): Postgres would store 4500.005 as
 * 4500.01 without a word, so the shop would sell at a price nobody typed.
 * The request is refused instead, with a field error the admin can act on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class MoneyAtTheEdgeTest {

    private static final SimpleGrantedAuthority CATALOG_ADMIN =
            new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository products;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        products.deleteAll();
    }

    /**
     * Leaves nothing behind either. This class shares its application, and so
     * its database, with the event tests, and SKUs are unique catalogue-wide:
     * a variant left here as AUR-1 would make the golden test's own AUR-1 a
     * 409 if that test ran later.
     */
    @AfterEach
    void cleanUp() {
        products.deleteAll();
    }

    @Test
    @DisplayName("every money field in catalog's requests carries the ngwee rule")
    void everyMoneyFieldIsGuarded() {
        assertThat(MoneyEdgeGuard.requestRecordCount("com.oriah.catalog.dto")).isPositive();
        assertThat(MoneyEdgeGuard.unguardedMoneyFields("com.oriah.catalog.dto")).isEmpty();
    }

    @Test
    @DisplayName("a new variant priced at a tenth of a ngwee is refused, and nothing is added")
    void aNewVariantFinerThanANgweeIsRefused() throws Exception {
        String id = createProduct("Aurelia 18k");

        mvc.perform(post("/api/v1/admin/products/" + id + "/variants")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"AUR-1\",\"unitPrice\":4500.005}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("unitPrice")));

        assertThat(variants(id)).isZero();
    }

    @Test
    @DisplayName("a reprice to a tenth of a ngwee is refused, and the price stays as it was")
    void aRepriceFinerThanANgweeIsRefused() throws Exception {
        String id = createProduct("Aurelia 18k");
        mvc.perform(post("/api/v1/admin/products/" + id + "/variants")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"AUR-1\",\"unitPrice\":4500.00}"))
                .andExpect(status().isCreated());
        String variantId = objectMapper.readTree(adminProduct(id)).get("variants").get(0).get("id").asText();

        mvc.perform(patch("/api/v1/admin/products/" + id + "/variants/" + variantId)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPrice\":4600.001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("unitPrice")));

        assertThat(objectMapper.readTree(adminProduct(id)).get("variants").get(0).get("unitPrice").decimalValue())
                .isEqualByComparingTo("4500.00");
    }

    private String createProduct(String title) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/admin/products")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"tags\":[]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String location = created.getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private String adminProduct(String id) throws Exception {
        return mvc.perform(get("/api/v1/admin/products/" + id).with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private int variants(String id) throws Exception {
        return objectMapper.readTree(adminProduct(id)).get("variants").size();
    }
}
