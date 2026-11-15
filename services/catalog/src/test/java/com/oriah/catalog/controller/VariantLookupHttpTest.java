package com.oriah.catalog.controller;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.PricedCatalogue;
import com.oriah.catalog.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What procurement asks catalog before it lets a variant onto a purchase order:
 * does this id exist, what is it called, is it something we sell or packaging, and
 * is it still usable.
 *
 * <p>An INTERNAL endpoint: procurement calls it directly with the signed-in
 * person's own token, and the gateway does not route it, so no browser reaches it.
 * It answers for CATALOG_ADMIN (who manage the catalogue) and for PROCUREMENT_ADMIN
 * (who need to resolve what they are ordering). It returns identity, never a price.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class VariantLookupHttpTest {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;


    private static final String LOOKUP = "/api/v1/admin/variants/lookup";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor catalogAdmin() {
        return jwt().jwt(token -> token.subject("lookup-admin")).authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"));
    }

    private static RequestPostProcessor procurementAdmin() {
        return jwt().jwt(token -> token.subject("buyer")).authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    private String createProduct(String json) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/admin/products").with(catalogAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    private String addVariant(String productId, String sku, String price, String optionsJson) throws Exception {
        MvcResult added = mvc.perform(post("/api/v1/admin/products/" + productId + "/variants").with(catalogAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"options\":" + optionsJson + "}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode variants = mapper.readTree(added.getResponse().getContentAsString()).get("variants");
        String id = variants.get(variants.size() - 1).get("id").asText();
        if (new java.math.BigDecimal(price).signum() > 0) {
            PricedCatalogue.priceDirectly(jdbc, java.util.UUID.fromString(id), price);
        }
        return id;
    }

    private JsonNode lookup(RequestPostProcessor who, String... ids) throws Exception {
        String body = "{\"variantIds\":[" + java.util.Arrays.stream(ids).map(i -> "\"" + i + "\"")
                .collect(Collectors.joining(",")) + "]}";
        MvcResult result = mvc.perform(post(LOOKUP).with(who).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("results");
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    @DisplayName("a sellable variant is found with its product, its label, its SKU, its kind and its status")
    void findsASellableVariant() throws Exception {
        String t = token();
        String ring = createProduct("{\"title\":\"Lookup Ring " + t + "\",\"optionNames\":[\"Ring Size\"]}");
        String variant = addVariant(ring, "LOOK-" + t, "0.00", "{\"Ring Size\":\"M\"}");

        JsonNode entry = lookup(catalogAdmin(), variant).get(0);

        assertThat(entry.get("variantId").asText()).isEqualTo(variant);
        assertThat(entry.get("found").asBoolean()).isTrue();
        assertThat(entry.get("productId").asText()).isEqualTo(ring);
        assertThat(entry.get("productTitle").asText()).isEqualTo("Lookup Ring " + t);
        assertThat(entry.get("variantTitle").asText()).isEqualTo("Lookup Ring " + t + " - Ring Size M");
        assertThat(entry.get("sku").asText()).isEqualTo("LOOK-" + t);
        assertThat(entry.get("kind").asText()).isEqualTo("SELLABLE");
        assertThat(entry.get("productStatus").asText()).isEqualTo("DRAFT");
        assertThat(entry.get("active").asBoolean()).isTrue();
        assertThat(entry.get("productDeleted").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("packaging is found too, and says so: it is something procurement orders and stocks")
    void findsPackaging() throws Exception {
        String t = token();
        String box = createProduct("{\"title\":\"Lookup Box " + t + "\",\"kind\":\"PACKAGING\"}");
        String variant = addVariant(box, "BOX-" + t, "0.00", "{}");

        JsonNode entry = lookup(procurementAdmin(), variant).get(0);

        assertThat(entry.get("found").asBoolean()).isTrue();
        assertThat(entry.get("kind").asText()).isEqualTo("PACKAGING");
    }

    @Test
    @DisplayName("an id that is not a variant is reported as not found, in the order asked, duplicates included")
    void unknownIdsAreReportedInOrder() throws Exception {
        String t = token();
        String ring = createProduct("{\"title\":\"Lookup Order " + t + "\"}");
        String variant = addVariant(ring, "ORD-" + t, "0.00", "{}");
        String nobody = UUID.randomUUID().toString();

        JsonNode results = lookup(catalogAdmin(), nobody, variant, nobody);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("variantId").asText()).isEqualTo(nobody);
        assertThat(results.get(0).get("found").asBoolean()).isFalse();
        assertThat(results.get(0).has("productTitle")).isFalse();
        assertThat(results.get(1).get("found").asBoolean()).isTrue();
        assertThat(results.get(2).get("variantId").asText()).isEqualTo(nobody);
    }

    @Test
    @DisplayName("a deleted product's variant is still found, but flagged deleted, so the caller can refuse it")
    void deletedProductIsFlagged() throws Exception {
        String t = token();
        String ring = createProduct("{\"title\":\"Lookup Gone " + t + "\"}");
        String variant = addVariant(ring, "GONE-" + t, "0.00", "{}");
        mvc.perform(delete("/api/v1/admin/products/" + ring).with(catalogAdmin())).andExpect(status().isNoContent());

        JsonNode entry = lookup(catalogAdmin(), variant).get(0);

        assertThat(entry.get("found").asBoolean()).isTrue();
        assertThat(entry.get("productDeleted").asBoolean()).isTrue();
        assertThat(entry.get("productStatus").asText()).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("it carries no price: this is identity, not the pricing route")
    void carriesNoPrice() throws Exception {
        String t = token();
        String ring = createProduct("{\"title\":\"Lookup Price " + t + "\"}");
        String variant = addVariant(ring, "PRICE-" + t, "4500.00", "{}");

        JsonNode entry = lookup(catalogAdmin(), variant).get(0);

        assertThat(entry.has("unitPrice")).isFalse();
        assertThat(entry.has("currency")).isFalse();
    }

    @Test
    @DisplayName("at most 100 ids at a time, and at least one")
    void sizeIsBounded() throws Exception {
        String hundredAndOne = IntStream.range(0, 101).mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));
        mvc.perform(post(LOOKUP).with(catalogAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[" + hundredAndOne + "]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(LOOKUP).with(catalogAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[]}"))
                .andExpect(status().isBadRequest());
        List<String> exactly100 = IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID().toString()).toList();
        mvc.perform(post(LOOKUP).with(catalogAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[" + exactly100.stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(",")) + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(100));
    }

    @Test
    @DisplayName("only a catalogue admin, a procurement admin or a packer (to name the box on a packing slip) may ask; a shopper, a sales person and a signed-out caller may not")
    void whoMayAsk() throws Exception {
        String body = "{\"variantIds\":[\"" + UUID.randomUUID() + "\"]}";

        mvc.perform(post(LOOKUP).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(LOOKUP).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(LOOKUP).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(LOOKUP).with(catalogAdmin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post(LOOKUP).with(procurementAdmin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        // Phase 8: the order system asks, with the packer's own token, what a box is called, so the slip can name it. Identity only: no price, no cost.
        mvc.perform(post(LOOKUP).with(jwt().jwt(token -> token.subject("dave")).authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post(LOOKUP).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }
}
