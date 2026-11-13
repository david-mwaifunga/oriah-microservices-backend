package com.oriah.catalog.integration;

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
import com.oriah.catalog.PricedCatalogue;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.repository.CategoryRepository;
import com.oriah.catalog.repository.ProductRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating categories, and putting products in them.
 *
 * <h2>The gap this closes</h2>
 *
 * The taxonomy was read-only and therefore empty. {@code categories} and
 * {@code product_categories} are real tables with a parent tree and a
 * self-parent CHECK; {@link com.oriah.catalog.model.Category} can rename,
 * reparent and reposition, and refuses a cycle the database cannot express;
 * {@link com.oriah.catalog.model.Product#assignCategories} enforces that a
 * primary category is one the product is actually in; and the storefront list
 * endpoint has always accepted a {@code category} filter resolved to a slug
 * AND its descendants.
 *
 * <p>And {@code CategoryService} exposed only {@code tree()},
 * {@code findBySlug} and {@code descendantIdsOf}. Nothing could create a
 * category, so {@code GET /api/v1/categories} returned {@code []} forever and
 * the filter had nothing to filter by. An explicit taxonomy that cannot be
 * populated is tags with extra steps.
 *
 * <h2>Why these endpoints are admin-only while the reads are anonymous</h2>
 *
 * The tree is storefront navigation, so reading it needs no token. Writing it
 * reshapes every shopper's view of the catalogue, so it is CATALOG_ADMIN --
 * the same role that creates the products it organises.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CategoryWriteTest {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;


    private static final SimpleGrantedAuthority CATALOG_ADMIN =
            new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");
    private static final SimpleGrantedAuthority PLAIN_USER =
            new SimpleGrantedAuthority("ROLE_USER");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private ProductRepository products;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /**
     * Each test starts with an empty taxonomy.
     *
     * <p>Without this the second test to create "Rings" gets a 409: the slug
     * is derived from the name and carries a unique index, so a shared
     * database makes every later test fail for a reason that has nothing to do
     * with what it is testing. Products go first because
     * {@code product_categories} and {@code products.primary_category_id}
     * reference categories.
     */
    @BeforeEach
    void clean() {
        products.deleteAll();
        categories.deleteAll();
    }

    /** @return the created category's id */
    private String createCategory(String body) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/admin/categories")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("id").asText();
    }

    private String createProduct(String title) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/admin/products")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"tags\":[]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String location = res.getResponse().getHeader("Location");
        return location == null ? null : location.substring(location.lastIndexOf('/') + 1);
    }

    // ------------------------------------------------------------ creating

    @Test
    @DisplayName("a category can be created, and appears in the public tree")
    void aCategoryCanBeCreated() throws Exception {
        createCategory("{\"name\":\"Rings\",\"description\":\"Bands and signets\"}");

        // Anonymously: the tree is storefront navigation.
        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == 'rings')].name").value(
                        org.hamcrest.Matchers.hasItem("Rings")));
    }

    @Test
    @DisplayName("the slug is derived from the name, not supplied")
    void theSlugIsDerived() throws Exception {
        // Same reasoning as a product's slug: a client-chosen slug is a client
        // choosing a URL, and two of them would collide in a way only the
        // database notices.
        createCategory("{\"name\":\"Engagement & Bridal\"}");

        mvc.perform(get("/api/v1/categories"))
                .andExpect(jsonPath("$[?(@.name == 'Engagement & Bridal')].slug").value(
                        org.hamcrest.Matchers.hasItem("engagement-bridal")));
    }

    @Test
    @DisplayName("a child nests under its parent in the tree")
    void childrenNest() throws Exception {
        String parent = createCategory("{\"name\":\"Rings\"}");
        createCategory("{\"name\":\"Signet\",\"parentId\":\"" + parent + "\"}");

        mvc.perform(get("/api/v1/categories"))
                .andExpect(jsonPath("$[?(@.slug == 'rings')].children[0].slug").value(
                        org.hamcrest.Matchers.hasItem("signet")));
    }

    @Test
    @DisplayName("a blank name is refused")
    void aBlankNameIsRefused() throws Exception {
        mvc.perform(post("/api/v1/admin/categories")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown parent is a 400, not a foreign key violation")
    void anUnknownParentIsRefused() throws Exception {
        mvc.perform(post("/api/v1/admin/categories")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Orphan\",\"parentId\":"
                                + "\"11111111-2222-3333-4444-555555555555\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a second category with the same name is refused, not silently renamed")
    void duplicateNamesCollide() throws Exception {
        // Products resolve a slug collision by appending a suffix, because two
        // pieces may genuinely share a title. Two categories called "Rings" is
        // an operator mistake, not a catalogue with two of them -- so the
        // unique index is allowed to speak, as a 409.
        createCategory("{\"name\":\"Rings\"}");

        mvc.perform(post("/api/v1/admin/categories")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rings\"}"))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------ editing

    @Test
    @DisplayName("a category can be renamed without changing its slug")
    void renamingKeepsTheSlug() throws Exception {
        // The slug is in storefront URLs and in anyone's bookmarks. Renaming
        // the label is a copy edit; silently moving the URL is not.
        String id = createCategory("{\"name\":\"Rings\"}");

        mvc.perform(patch("/api/v1/admin/categories/" + id)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rings & Bands\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Rings & Bands"))
                .andExpect(jsonPath("$.slug").value("rings"));
    }

    @Test
    @DisplayName("a category cannot be parented under its own descendant")
    void cyclesAreRefused() throws Exception {
        // The database CHECK only catches a category parented to itself, so a
        // two-step cycle has to be caught in the domain -- and it is, which
        // this proves reaches the API as a 400 rather than a 500.
        String parent = createCategory("{\"name\":\"Rings\"}");
        String child = createCategory("{\"name\":\"Signet\",\"parentId\":\"" + parent + "\"}");

        mvc.perform(patch("/api/v1/admin/categories/" + parent)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"" + child + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("deleting a category leaves its products alone")
    void deletingDoesNotTakeProductsWithIt() throws Exception {
        // product_categories cascades on delete, which removes the LINK. A
        // taxonomy edit must never remove a sellable product.
        String id = createCategory("{\"name\":\"Rings\"}");
        String productId = createProduct("Aurelia 18k");
        assignCategories(productId, "[\"" + id + "\"]", id);

        mvc.perform(delete("/api/v1/admin/categories/" + id)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/admin/products/" + productId)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isEmpty());
    }

    // ------------------------------------------------------------ products

    private void assignCategories(String productId, String idsJson, String primaryId)
            throws Exception {
        mvc.perform(patch("/api/v1/admin/products/" + productId)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":" + idsJson
                                + (primaryId == null ? ""
                                        : ",\"primaryCategoryId\":\"" + primaryId + "\"")
                                + "}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a product can be put in a category, and reports it")
    void aProductCanBeCategorised() throws Exception {
        String rings = createCategory("{\"name\":\"Rings\"}");
        String productId = createProduct("Aurelia 18k");

        assignCategories(productId, "[\"" + rings + "\"]", rings);

        mvc.perform(get("/api/v1/admin/products/" + productId)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(jsonPath("$.categories[0].slug").value("rings"));
    }

    @Test
    @DisplayName("a product can be filed at CREATE time, not only by a later patch")
    void categoriesApplyOnCreate() throws Exception {
        // Found live: the watch created with categoryIds came back with
        // categories: []. The field was on the DTO and wired only into the
        // UPDATE path, so create accepted it and silently did nothing -- a
        // field on the wire that means nothing is worse than no field, because
        // the caller has every reason to believe it worked.
        String rings = createCategory("{\"name\":\"Rings\"}");

        MvcResult res = mvc.perform(post("/api/v1/admin/products")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Aurelia 18k\",\"tags\":[],"
                                + "\"categoryIds\":[\"" + rings + "\"],"
                                + "\"primaryCategoryId\":\"" + rings + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        org.assertj.core.api.Assertions
                .assertThat(res.getResponse().getContentAsString())
                .as("the create response must report the categories it was given")
                .contains("rings");
    }

    @Test
    @DisplayName("a primary that is not among them is refused at CREATE too")
    void thePrimaryIsCheckedOnCreate() throws Exception {
        String rings = createCategory("{\"name\":\"Rings\"}");
        String necklaces = createCategory("{\"name\":\"Necklaces\"}");

        mvc.perform(post("/api/v1/admin/products")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Aurelia 18k\",\"tags\":[],"
                                + "\"categoryIds\":[\"" + rings + "\"],"
                                + "\"primaryCategoryId\":\"" + necklaces + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a primary category the product is not in is refused")
    void thePrimaryMustBeOneOfThem() throws Exception {
        // The breadcrumb invariant: a trail pointing somewhere the product
        // does not appear is worse than no trail at all.
        String rings = createCategory("{\"name\":\"Rings\"}");
        String necklaces = createCategory("{\"name\":\"Necklaces\"}");
        String productId = createProduct("Aurelia 18k");

        mvc.perform(patch("/api/v1/admin/products/" + productId)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":[\"" + rings + "\"],"
                                + "\"primaryCategoryId\":\"" + necklaces + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a patch that mentions no categories leaves them alone")
    void anUnrelatedPatchKeepsTheCategories() throws Exception {
        String rings = createCategory("{\"name\":\"Rings\"}");
        String productId = createProduct("Aurelia 18k");
        assignCategories(productId, "[\"" + rings + "\"]", rings);

        mvc.perform(patch("/api/v1/admin/products/" + productId)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Aurelia 18k Band\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].slug").value("rings"));
    }

    @Test
    @DisplayName("the storefront can browse a category, including its children")
    void theStorefrontCanBrowseACategory() throws Exception {
        // findStorefront resolves a slug to the category AND its descendants,
        // so a piece filed under Signet appears when browsing Rings. That has
        // always worked; until now there was no category to ask for.
        String rings = createCategory("{\"name\":\"Rings\"}");
        String signet = createCategory("{\"name\":\"Signet\",\"parentId\":\"" + rings + "\"}");
        String productId = createProduct("Aurelia Signet");
        assignCategories(productId, "[\"" + signet + "\"]", signet);
        sellable(productId);

        mvc.perform(get("/api/v1/products").param("category", "rings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + productId + "')]").exists());
    }

    private void sellable(String productId) throws Exception {
        var added = mvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"C-" + productId.substring(0, 8)
                                + "\",\"currency\":\"ZMW\"}"))
                .andExpect(status().isCreated()).andReturn();
        PricedCatalogue.priceLastAdded(jdbc, added.getResponse().getContentAsString(), "4500.00");
        mvc.perform(post("/api/v1/admin/products/" + productId + "/publish")
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------ access

    @Test
    @DisplayName("a plain shopper cannot reshape the catalogue")
    void writingNeedsTheAdminRole() throws Exception {
        mvc.perform(post("/api/v1/admin/categories")
                        .with(jwt().authorities(PLAIN_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mine\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the 403 does not name the role it wanted")
    void theRefusalNamesNoRole() throws Exception {
        // Naming the role tells an attacker exactly which account to go after.
        MvcResult res = mvc.perform(post("/api/v1/admin/categories")
                        .with(jwt().authorities(PLAIN_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mine\"}"))
                .andExpect(status().isForbidden())
                .andReturn();

        org.assertj.core.api.Assertions
                .assertThat(res.getResponse().getContentAsString())
                .doesNotContain("CATALOG_ADMIN");
    }
}
