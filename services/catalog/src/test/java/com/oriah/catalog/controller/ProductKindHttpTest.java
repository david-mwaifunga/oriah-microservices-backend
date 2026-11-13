package com.oriah.catalog.controller;

import java.util.UUID;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.PricedCatalogue;
import com.oriah.catalog.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 over HTTP: an admin can make packaging as a catalog entry, packaging
 * is held to its two rules (never priced for sale, never published), and
 * nothing a shopper can reach shows or sells it.
 *
 * <p>Through the real service and a real database, so the rules are proved
 * where they are enforced rather than where a mock says they are.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProductKindHttpTest {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;


    private static final SimpleGrantedAuthority CATALOG_ADMIN =
            new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("kind-admin")).authorities(CATALOG_ADMIN);
    }

    // ------------------------------------------------------------ creating

    @Test
    @DisplayName("an admin can create packaging; it is a DRAFT and says PACKAGING")
    void createsPackaging() throws Exception {
        mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Gift box, small\",\"kind\":\"PACKAGING\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("PACKAGING"))
                .andExpect(jsonPath("$.productType").value("PACKAGING"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("packaging cannot be given another product type, and nothing sellable can be typed PACKAGING")
    void kindAndTypeMustAgree() throws Exception {
        mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Watch box\",\"kind\":\"PACKAGING\",\"productType\":\"WATCH\","
                                + "\"attributes\":{\"movement\":\"QUARTZ\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Boxy ring\",\"productType\":\"PACKAGING\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a PATCH cannot re-type packaging as jewellery or a sellable piece as packaging")
    void typeCannotBeChangedAcrossTheKindBoundary() throws Exception {
        String box = createPackaging("Retype box");
        String ring = createProduct("{\"title\":\"Retype ring\"}");

        mvc.perform(patch("/api/v1/admin/products/" + box).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productType\":\"JEWELLERY\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/products/" + ring).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productType\":\"PACKAGING\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a product created without a kind is SELLABLE, so existing admin clients keep working")
    void kindDefaultsToSellable() throws Exception {
        mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Plain Ring\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("SELLABLE"));
    }

    @Test
    @DisplayName("a kind that does not exist is a 400, not a 500 from the database")
    void unknownKindIsRefused() throws Exception {
        mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Odd\",\"kind\":\"SERVICE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Location points at the admin read, because a new product is a DRAFT and the public read 404s it")
    void locationPointsAtTheAdminRead() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Located Ring\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String location = created.getResponse().getHeader("Location");

        assertThat(location).isEqualTo("/api/v1/admin/products/" + idOf(created));
        mvc.perform(get(location).with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/" + idOf(created))).andExpect(status().isNotFound());
    }

    // ----------------------------------------------------- the two rules

    @Test
    @DisplayName("packaging cannot be published: 409, and it says it is packaging")
    void packagingCannotBePublished() throws Exception {
        String box = createPackaging("Gift box, medium");
        addVariant(box, "BOX-M", "0.00").andExpect(status().isCreated());

        mvc.perform(post("/api/v1/admin/products/" + box + "/publish").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("packaging")));
        mvc.perform(get("/api/v1/admin/products/" + box).with(admin()))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("packaging has no selling price: a price above zero is refused when the variant is added")
    void packagingVariantMustBeFree() throws Exception {
        String box = createPackaging("Gift box, large");

        addVariant(box, "BOX-L", "25.00").andExpect(status().isBadRequest());
        addVariant(box, "BOX-L", "0.00").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a sellable product is priced and published exactly as before, and its kind shows on the public read")
    void sellableIsUnchanged() throws Exception {
        String ring = createProduct("{\"title\":\"Kind Test Ring\"}");
        MvcResult added = addVariant(ring, "KIND-RING", "0.00").andExpect(status().isCreated()).andReturn();
        PricedCatalogue.priceLastAdded(jdbc, added.getResponse().getContentAsString(), "4500.00");
        mvc.perform(post("/api/v1/admin/products/" + ring + "/publish").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SELLABLE"));

        mvc.perform(get("/api/v1/products/" + ring))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SELLABLE"));
    }

    // --------------------------------------------------- kind is fixed

    @Test
    @DisplayName("kind is fixed at creation: a PATCH naming a different kind is refused, not silently ignored")
    void kindCannotBeChanged() throws Exception {
        String box = createPackaging("Gift pouch");

        mvc.perform(patch("/api/v1/admin/products/" + box).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SELLABLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("kind")));
        mvc.perform(get("/api/v1/admin/products/" + box).with(admin()))
                .andExpect(jsonPath("$.kind").value("PACKAGING"));
    }

    @Test
    @DisplayName("a refused kind change changes nothing else in the same PATCH")
    void refusedKindChangeAppliesNothing() throws Exception {
        String box = createPackaging("Atomic box");

        mvc.perform(patch("/api/v1/admin/products/" + box).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SELLABLE\",\"title\":\"Renamed anyway\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/products/" + box).with(admin()))
                .andExpect(jsonPath("$.title").value("Atomic box"))
                .andExpect(jsonPath("$.kind").value("PACKAGING"));
    }

    @Test
    @DisplayName("a form that always sends both fields can create packaging: kind PACKAGING with productType PACKAGING is fine")
    void consistentKindAndTypeAreAccepted() throws Exception {
        mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Explicit box\",\"kind\":\"PACKAGING\",\"productType\":\"PACKAGING\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("PACKAGING"))
                .andExpect(jsonPath("$.productType").value("PACKAGING"));
    }

    @Test
    @DisplayName("a PATCH that repeats the kind it already has is fine, and so is one that leaves it out")
    void sameOrAbsentKindIsAccepted() throws Exception {
        String box = createPackaging("Ribbon");

        mvc.perform(patch("/api/v1/admin/products/" + box).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PACKAGING\",\"title\":\"Ribbon, gold\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PACKAGING"));
        mvc.perform(patch("/api/v1/admin/products/" + box).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ribbon, silver\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PACKAGING"));
    }

    // ------------------------------------------- what a shopper can reach

    @Test
    @DisplayName("a shopper's product read 404s packaging, and the public list never carries it")
    void shopperCannotSeePackaging() throws Exception {
        String box = createPackaging("Hidden box");
        addVariant(box, "BOX-HIDDEN", "0.00").andExpect(status().isCreated());

        mvc.perform(get("/api/v1/products/" + box)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products?size=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(hasItem(box))));
    }

    @Test
    @DisplayName("the price the cart and checkout trust says UNKNOWN for a packaging variant, with no id and no title")
    void pricingTellsAShopperNothingAboutPackaging() throws Exception {
        String box = createPackaging("Secret box");
        MvcResult added = addVariant(box, "BOX-SECRET", "0.00").andExpect(status().isCreated()).andReturn();
        String variantId = mapper.readTree(added.getResponse().getContentAsString())
                .get("variants").get(0).get("id").asText();

        mvc.perform(post("/api/v1/variants/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[\"" + variantId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.results[0].productId").doesNotExist())
                .andExpect(jsonPath("$.results[0].productTitle").doesNotExist());
    }

    // ------------------------------------------------------------ helpers

    private String createPackaging(String title) throws Exception {
        return createProduct("{\"title\":\"" + title + "\",\"kind\":\"PACKAGING\"}");
    }

    private String createProduct(String json) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(created);
    }

    private org.springframework.test.web.servlet.ResultActions addVariant(String productId, String sku, String price)
            throws Exception {
        return mvc.perform(post("/api/v1/admin/products/" + productId + "/variants").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"" + sku + "-" + UUID.randomUUID().toString().substring(0, 8)
                        + "\",\"unitPrice\":" + price + "}"));
    }

    private String idOf(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
