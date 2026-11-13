package com.oriah.catalog.controller;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.PricedCatalogue;
import com.oriah.catalog.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A product that comes in more than one size, colour or strap -- "the product
 * with its variants", step 1 of the real-world order -- over HTTP.
 *
 * <p>The domain has always had option axes ({@code Product.declareOptionNames}:
 * a ring varies on "Ring Size", a watch on "Strap" and "Case Size"), and a
 * variant's options must match them exactly. But nothing in the API could set
 * them: neither create nor update carried the field, so every product had no
 * axes and could hold exactly ONE variant, with empty options. A ring in sizes
 * 6, 7 and 8, or a gift box in three sizes, could not be entered. These tests
 * drive the whole path: declare the axes, add variants, see them on the product,
 * see them in the label the cart shows, find them in the operator's list.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProductOptionAxesHttpTest {

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
        return jwt().jwt(token -> token.subject("axes-admin")).authorities(CATALOG_ADMIN);
    }

    @Test
    @DisplayName("a product declares its option axes when it is created, and says them back")
    void axesAreDeclaredAtCreate() throws Exception {
        mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Axes Ring\",\"optionNames\":[\"Ring Size\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.optionNames[0]").value("Ring Size"));
    }

    @Test
    @DisplayName("a product with axes takes one variant per combination, and refuses a repeat or a mismatch")
    void variantsFollowTheAxes() throws Exception {
        String ring = create("{\"title\":\"Sized Ring\",\"optionNames\":[\"Ring Size\"]}");

        variant(ring, "SIZED-M", "4500.00", "{\"Ring Size\":\"M\"}").andExpect(status().isCreated());
        variant(ring, "SIZED-L", "4500.00", "{\"Ring Size\":\"L\"}").andExpect(status().isCreated());
        variant(ring, "SIZED-M2", "4500.00", "{\"Ring Size\":\"M\"}").andExpect(status().isBadRequest());
        variant(ring, "SIZED-X", "4500.00", "{\"Colour\":\"Red\"}").andExpect(status().isBadRequest());
        variant(ring, "SIZED-NONE", "4500.00", "{}").andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/admin/products/" + ring).with(admin()))
                .andExpect(jsonPath("$.variants.length()").value(2));
    }

    @Test
    @DisplayName("a variant that names one axis twice (once with stray spaces) is a 400, not stored with the wrong value")
    void collidingOptionKeysAreRefused() throws Exception {
        String ring = create("{\"title\":\"Collision Ring\",\"optionNames\":[\"Size\"]}");

        variant(ring, "COLLIDE", "4500.00", "{\"Size\":\"M\",\" Size \":\"L\"}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/products/" + ring).with(admin()))
                .andExpect(jsonPath("$.variants.length()").value(0));
    }

    @Test
    @DisplayName("a product made without axes still holds one variant with no options, exactly as before")
    void noAxesIsUnchanged() throws Exception {
        String ring = create("{\"title\":\"One-off Ring\"}");

        mvc.perform(get("/api/v1/admin/products/" + ring).with(admin()))
                .andExpect(jsonPath("$.optionNames.length()").value(0));
        variant(ring, "ONEOFF", "4500.00", "{}").andExpect(status().isCreated());
        variant(ring, "ONEOFF-M", "4500.00", "{\"Ring Size\":\"M\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("axes can be set later on a product that has no variants, and not while a variant would stop matching")
    void axesCanBeChangedUntilTheyWouldStrandAVariant() throws Exception {
        String early = create("{\"title\":\"Later Axes Ring\"}");
        mvc.perform(patch("/api/v1/admin/products/" + early).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionNames\":[\"Ring Size\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optionNames[0]").value("Ring Size"));

        String stranded = create("{\"title\":\"Stranded Ring\"}");
        variant(stranded, "STRANDED", "4500.00", "{}").andExpect(status().isCreated());
        mvc.perform(patch("/api/v1/admin/products/" + stranded).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionNames\":[\"Ring Size\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/products/" + stranded).with(admin()))
                .andExpect(jsonPath("$.optionNames.length()").value(0));
    }

    @Test
    @DisplayName("PATCH with an empty axis list clears the axes; a PATCH that leaves the field out keeps them")
    void emptyListClearsAndAbsentKeeps() throws Exception {
        String ring = create("{\"title\":\"Clearable Ring\",\"optionNames\":[\"Ring Size\"]}");

        mvc.perform(patch("/api/v1/admin/products/" + ring).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Clearable Ring, renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optionNames[0]").value("Ring Size"));
        mvc.perform(patch("/api/v1/admin/products/" + ring).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionNames\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optionNames.length()").value(0));
    }

    @Test
    @DisplayName("a repeated or blank axis name on PATCH is a 400, and the axes are left as they were")
    void badAxesOnPatchAreRefused() throws Exception {
        String ring = create("{\"title\":\"Patched axes ring\",\"optionNames\":[\"Ring Size\"]}");

        for (String bad : new String[] {"[\"Size\",\"Size \"]", "[\"\"]"}) {
            mvc.perform(patch("/api/v1/admin/products/" + ring).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"optionNames\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/admin/products/" + ring).with(admin()))
                .andExpect(jsonPath("$.optionNames[0]").value("Ring Size"));
    }

    @Test
    @DisplayName("an axis named twice is refused")
    void duplicateAxisIsRefused() throws Exception {
        mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Twice\",\"optionNames\":[\"Size\",\"Size \"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an axis list with a null or blank entry is a 400 on create and on update, never a 500")
    void malformedAxesAreClientErrors() throws Exception {
        for (String bad : new String[] {"[null]", "[\"Size\",null]", "[\"\"]", "[\"   \"]"}) {
            mvc.perform(post("/api/v1/admin/products").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Bad axes\",\"optionNames\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        String ring = create("{\"title\":\"Patched bad axes\"}");
        mvc.perform(patch("/api/v1/admin/products/" + ring).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionNames\":[null]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the label the cart shows carries the option: 'Sized Label Ring - Ring Size M'")
    void theCartLabelNamesTheOption() throws Exception {
        String ring = create("{\"title\":\"Sized Label Ring\",\"optionNames\":[\"Ring Size\"]}");
        MvcResult added = variant(ring, "LABEL-M", "4500.00", "{\"Ring Size\":\"M\"}")
                .andExpect(status().isCreated()).andReturn();
        String variantId = mapper.readTree(added.getResponse().getContentAsString())
                .get("variants").get(0).get("id").asText();
        mvc.perform(post("/api/v1/admin/products/" + ring + "/publish").with(admin())).andExpect(status().isOk());

        mvc.perform(post("/api/v1/variants/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[\"" + variantId + "\"]}"))
                .andExpect(jsonPath("$.results[0].status").value("PRICED"))
                .andExpect(jsonPath("$.results[0].variantTitle").value("Sized Label Ring - Ring Size M"));
    }

    @Test
    @DisplayName("packaging that comes in sizes is one entry with a variant per size, and the operator's list shows them all")
    void packagingInSizes() throws Exception {
        String box = create("{\"title\":\"Sized Gift Box\",\"kind\":\"PACKAGING\",\"optionNames\":[\"Size\"]}");
        variant(box, "BOX-S", "0.00", "{\"Size\":\"Small\"}").andExpect(status().isCreated());
        variant(box, "BOX-M", "0.00", "{\"Size\":\"Medium\"}").andExpect(status().isCreated());
        variant(box, "BOX-L", "0.00", "{\"Size\":\"Large\"}").andExpect(status().isCreated());

        MvcResult listed = mvc.perform(get("/api/v1/admin/products").param("q", "Sized Gift Box")
                        .param("kind", "PACKAGING").with(admin()))
                .andExpect(status().isOk()).andReturn();
        JsonNode row = mapper.readTree(listed.getResponse().getContentAsString()).get("content").get(0);

        assertThat(row.get("optionNames").get(0).asText()).isEqualTo("Size");
        assertThat(row.get("variants")).hasSize(3);
    }

    // -------------------------------------------------------------- helpers

    private String create(String json) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    /**
     * SKUs are unique across the whole catalogue, and this test shares its database
     * (and context) with others, so each SKU carries a suffix unique to this run: a
     * fixed "BOX-M" here once collided with the same SKU in the event golden test.
     */
    private static final String RUN = java.util.UUID.randomUUID().toString().substring(0, 8);

    /** Adds a variant (unpriced, as every variant now starts); a wanted price is given directly once it exists: this test is about option axes. */
    private ResultActions variant(String productId, String sku, String price, String optionsJson) throws Exception {
        ResultActions added = mvc.perform(post("/api/v1/admin/products/" + productId + "/variants").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"" + sku + "-" + RUN + "\",\"options\":" + optionsJson + "}"));
        if (added.andReturn().getResponse().getStatus() == 201 && new java.math.BigDecimal(price).signum() > 0) {
            PricedCatalogue.priceLastAdded(jdbc, added.andReturn().getResponse().getContentAsString(), price);
        }
        return added;
    }
}
