package com.oriah.catalog.integration;

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

import com.oriah.catalog.TestcontainersConfiguration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating a product of a given TYPE, with that type's specification.
 *
 * <h2>Everything for this existed except the way in</h2>
 *
 * {@code ProductTypeRegistry} declares JEWELLERY and WATCH with their
 * attributes -- a watch's movement is a required enum of AUTOMATIC, QUARTZ and
 * MANUAL; its case diameter is a number in mm -- and
 * {@code ProductTypeRegistry.validate} coerces and checks them, collecting
 * every failure so an operator filling six spec fields learns about all six at
 * once. {@code Product.applyType} applies the result. {@code products.attributes}
 * is a JSONB column and {@code product_type_code} a foreign key, and
 * {@code ProductTypeConsistencyVerifier} refuses to start the service if the
 * table and the registry disagree.
 *
 * <p>And {@code ProductCreateRequest} was
 * {@code (String title, String description, List<String> tags)}, so none of it
 * could be reached. Every product created through the API was a JEWELLERY with
 * {@code attributes = '{}'}, and the WATCH type -- fully defined, seeded,
 * validated, and verified at startup -- was unreachable.
 *
 * <p>This is the two fields that connect them.
 *
 * <h2>Why the default is preserved rather than made explicit</h2>
 *
 * Omitting {@code productType} still produces a JEWELLERY. The column was
 * added with {@code DEFAULT 'JEWELLERY'} and the default then dropped, so
 * every existing row is one; requiring the field now would break every caller
 * for no gain, and a shop that sells mostly jewellery should not have to say
 * so on every request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProductTypeWriteTest {

    private static final SimpleGrantedAuthority CATALOG_ADMIN =
            new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private MvcResult create(String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/products")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static String idOf(MvcResult res) {
        String location = res.getResponse().getHeader("Location");
        return location == null ? null : location.substring(location.lastIndexOf('/') + 1);
    }

    private void refused(String body, String field) throws Exception {
        mvc.perform(post("/api/v1/admin/products")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(
                        org.hamcrest.Matchers.hasItem(field)));
    }

    // ------------------------------------------------------------ watches

    @Test
    @DisplayName("a watch can be created, with its movement and case diameter")
    void aWatchCanBeCreated() throws Exception {
        MvcResult res = create("""
                {
                  "title": "Oriah Field Automatic",
                  "description": "38mm field watch",
                  "tags": ["watches"],
                  "productType": "WATCH",
                  "attributes": {
                    "movement": "AUTOMATIC",
                    "caseDiameterMm": 38,
                    "caseMaterial": "Stainless steel",
                    "sapphireCrystal": true
                  }
                }
                """);

        mvc.perform(get("/api/v1/admin/products/" + idOf(res))
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productType").value("WATCH"))
                .andExpect(jsonPath("$.attributes.movement").value("AUTOMATIC"))
                .andExpect(jsonPath("$.attributes.caseMaterial").value("Stainless steel"))
                // Coerced to its declared kind on the way in, not stored as
                // whatever the client sent.
                .andExpect(jsonPath("$.attributes.caseDiameterMm").value(38))
                .andExpect(jsonPath("$.attributes.sapphireCrystal").value(true));
    }

    @Test
    @DisplayName("a watch without a movement is refused, naming the field")
    void movementIsRequiredForAWatch() throws Exception {
        // The only required attribute in the registry. Required because a
        // watch with no stated movement is not a listing anyone can buy from.
        refused("""
                {
                  "title": "Oriah Field",
                  "tags": [],
                  "productType": "WATCH",
                  "attributes": {"caseDiameterMm": 38}
                }
                """, "movement");
    }

    @Test
    @DisplayName("a movement outside the declared values is refused")
    void anUnknownMovementIsRefused() throws Exception {
        refused("""
                {
                  "title": "Oriah Field",
                  "tags": [],
                  "productType": "WATCH",
                  "attributes": {"movement": "SOLAR"}
                }
                """, "movement");
    }

    @Test
    @DisplayName("an attribute the type does not declare is REPORTED, not dropped")
    void anUndeclaredAttributeIsReported() throws Exception {
        // Silently discarding a field an operator filled in loses their work
        // with no indication, and a carat weight on a watch usually means the
        // wrong type was picked.
        refused("""
                {
                  "title": "Oriah Field",
                  "tags": [],
                  "productType": "WATCH",
                  "attributes": {"movement": "QUARTZ", "caratWeight": 0.5}
                }
                """, "caratWeight");
    }

    @Test
    @DisplayName("every attribute problem is reported at once, not one per submission")
    void allAttributeFailuresComeBackTogether() throws Exception {
        // A watch has six spec fields on one form. Failing on the first would
        // make an operator submit six times to discover six problems.
        mvc.perform(post("/api/v1/admin/products")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Oriah Field",
                                  "tags": [],
                                  "productType": "WATCH",
                                  "attributes": {
                                    "movement": "SOLAR",
                                    "caseDiameterMm": "wide",
                                    "caratWeight": 0.5
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(3));
    }

    @Test
    @DisplayName("a numeric attribute sent as a form string is coerced, not rejected")
    void numbersArriveAsStringsFromForms() throws Exception {
        MvcResult res = create("""
                {
                  "title": "Oriah Field Quartz",
                  "tags": [],
                  "productType": "WATCH",
                  "attributes": {"movement": "QUARTZ", "caseDiameterMm": "36.5"}
                }
                """);

        mvc.perform(get("/api/v1/admin/products/" + idOf(res))
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(jsonPath("$.attributes.caseDiameterMm").value(36.5));
    }

    @Test
    @DisplayName("a negative measurement is refused")
    void negativeMeasurementsAreRefused() throws Exception {
        refused("""
                {
                  "title": "Oriah Field",
                  "tags": [],
                  "productType": "WATCH",
                  "attributes": {"movement": "QUARTZ", "waterResistanceM": -50}
                }
                """, "waterResistanceM");
    }

    // ------------------------------------------------------------ jewellery

    @Test
    @DisplayName("jewellery keeps its own attributes, including the purity enum")
    void jewelleryHasItsOwnSpec() throws Exception {
        MvcResult res = create("""
                {
                  "title": "Aurelia 18k Band",
                  "tags": ["rings"],
                  "productType": "JEWELLERY",
                  "attributes": {"metal": "Gold", "metalPurity": "18k", "hallmarked": true}
                }
                """);

        mvc.perform(get("/api/v1/admin/products/" + idOf(res))
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(jsonPath("$.attributes.metalPurity").value("18k"))
                .andExpect(jsonPath("$.attributes.hallmarked").value(true));
    }

    @Test
    @DisplayName("a watch attribute on a jewellery product is refused")
    void typesDoNotShareAttributes() throws Exception {
        refused("""
                {
                  "title": "Aurelia 18k Band",
                  "tags": [],
                  "productType": "JEWELLERY",
                  "attributes": {"movement": "AUTOMATIC"}
                }
                """, "movement");
    }

    // ------------------------------------------------------------ defaults

    @Test
    @DisplayName("omitting productType still creates a jewellery piece")
    void theDefaultIsPreserved() throws Exception {
        // Every existing row is one, and a shop that sells mostly jewellery
        // should not have to say so on every request.
        MvcResult res = create("""
                {"title": "Aurelia 18k Band", "tags": ["rings"]}
                """);

        mvc.perform(get("/api/v1/admin/products/" + idOf(res))
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(jsonPath("$.productType").value("JEWELLERY"))
                .andExpect(jsonPath("$.attributes").isMap());
    }

    @Test
    @DisplayName("an unknown product type is refused")
    void anUnknownTypeIsRefused() throws Exception {
        // The column has a foreign key to product_types, so an unchecked value
        // would be a constraint violation rendered as a 500.
        refused("""
                {"title": "Mystery", "tags": [], "productType": "SPACESHIP"}
                """, "productType");
    }

    // ------------------------------------------------------------ updating

    @Test
    @DisplayName("a product's type and spec can be corrected after the fact")
    void theSpecCanBeCorrected() throws Exception {
        String id = idOf(create("""
                {
                  "title": "Oriah Field",
                  "tags": [],
                  "productType": "WATCH",
                  "attributes": {"movement": "QUARTZ"}
                }
                """));

        mvc.perform(patch("/api/v1/admin/products/" + id)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productType": "WATCH",
                                  "attributes": {"movement": "AUTOMATIC", "caseDiameterMm": 40}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.movement").value("AUTOMATIC"))
                .andExpect(jsonPath("$.attributes.caseDiameterMm").value(40));
    }

    @Test
    @DisplayName("a patch that mentions neither leaves the type and spec alone")
    void anUnrelatedPatchIsNotDestructive() throws Exception {
        // PATCH is partial. A title edit that silently blanked the spec would
        // be the kind of data loss nobody notices until a listing goes thin.
        String id = idOf(create("""
                {
                  "title": "Oriah Field",
                  "tags": [],
                  "productType": "WATCH",
                  "attributes": {"movement": "AUTOMATIC", "caseDiameterMm": 40}
                }
                """));

        mvc.perform(patch("/api/v1/admin/products/" + id)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Oriah Field Automatic\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Oriah Field Automatic"))
                .andExpect(jsonPath("$.productType").value("WATCH"))
                .andExpect(jsonPath("$.attributes.movement").value("AUTOMATIC"))
                .andExpect(jsonPath("$.attributes.caseDiameterMm").value(40));
    }

    // ------------------------------------------------------------ storefront

    @Test
    @DisplayName("the storefront can browse by type once watches exist")
    void theStorefrontCanFilterByType() throws Exception {
        // findStorefront already accepts a typeCode; until now there was only
        // ever one type to ask for.
        String id = idOf(create("""
                {
                  "title": "Oriah Field Automatic",
                  "tags": [],
                  "productType": "WATCH",
                  "attributes": {"movement": "AUTOMATIC"}
                }
                """));
        addPricedVariantAndPublish(id);

        mvc.perform(get("/api/v1/products").param("type", "WATCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.productType == 'JEWELLERY')]").isEmpty())
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')]").exists());
    }

    private void addPricedVariantAndPublish(String id) throws Exception {
        mvc.perform(post("/api/v1/admin/products/" + id + "/variants")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"W-" + id.substring(0, 8)
                                + "\",\"unitPrice\":4500.00,\"currency\":\"ZMW\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/products/" + id + "/publish")
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isOk());
    }
}
