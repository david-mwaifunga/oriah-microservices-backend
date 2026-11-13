package com.oriah.catalog.controller;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.catalog.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A variant is created unpriced, always. The only way a variant gets a price is the pricing endpoint, which compares it with what
 * the stock cost: a second way in would let a price skip that check and leave history without its first row. The price field a
 * client used to send is refused when it carries a price (a stale client must hear about it, not have its price silently dropped),
 * and the currency is the shop's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class VariantCreationUnpricedHttpTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"));
    }

    private String newProduct() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/products").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ring " + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":\"([0-9a-f-]{36})\".*", "$1");
    }

    private org.springframework.test.web.servlet.ResultActions addVariant(String productId, String json) throws Exception {
        return mvc.perform(post("/api/v1/admin/products/" + productId + "/variants").with(admin()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("a variant with no price in the request is created at 0.00: not priced yet")
    void noPriceMeansUnpriced() throws Exception {
        String product = newProduct();

        addVariant(product, "{\"sku\":\"R-" + UUID.randomUUID() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants[0].unitPrice").value(0.00))
                .andExpect(jsonPath("$.variants[0].currency").value("ZMW"));
    }

    @Test
    @DisplayName("a price of 0.00 is still accepted from a client that sends one (it means 'not priced'), and nothing is saved in the history")
    void zeroIsStillUnpriced() throws Exception {
        String product = newProduct();

        addVariant(product, "{\"sku\":\"R-" + UUID.randomUUID() + "\",\"unitPrice\":0.00}").andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM variant_price WHERE variant_id IN (SELECT id FROM product_variants WHERE product_id = ?::uuid)",
                Long.class, product)).isZero();
    }

    @Test
    @DisplayName("a variant created WITH a price is refused: 400 naming the field, and no variant is made")
    void aPriceIsRefused() throws Exception {
        String product = newProduct();

        addVariant(product, "{\"sku\":\"R-" + UUID.randomUUID() + "\",\"unitPrice\":100.00}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("unitPrice"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(org.hamcrest.Matchers.containsString("pricing")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_variants WHERE product_id = ?::uuid", Long.class, product)).isZero();
    }

    @Test
    @DisplayName("the currency is the shop's: ZMW in any case is fine, anything else is 400")
    void onlyZmw() throws Exception {
        addVariant(newProduct(), "{\"sku\":\"R-" + UUID.randomUUID() + "\",\"currency\":\"zmw\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.variants[0].currency").value("ZMW"));
        addVariant(newProduct(), "{\"sku\":\"R-" + UUID.randomUUID() + "\",\"currency\":\"USD\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("what the service does when handed a price directly: it refuses anything above zero, so no code path creates a priced variant")
    void theServiceRefusesToo() {
        var request = new com.oriah.catalog.dto.VariantCreateRequest("R-" + UUID.randomUUID(), new BigDecimal("5.00"), "ZMW", java.util.Map.of());
        assertThat(request.unitPrice()).isEqualByComparingTo("5.00");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> serviceAddVariant(request)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pricing");
    }

    @Autowired
    private com.oriah.catalog.service.ProductService products;

    private void serviceAddVariant(com.oriah.catalog.dto.VariantCreateRequest request) {
        UUID product = products.create("Service ring " + UUID.randomUUID(), null, java.util.List.of(), "user:test").id();
        products.addVariant(product, request, "user:test");
    }
}
