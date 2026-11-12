package com.oriah.catalog.integration;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.repository.ProductRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration tests for the catalog service.
 *
 * <p>Unlike the slice tests in {@code controller/} (which mock the service) and
 * {@code repository/} (which short-circuit Spring's MVC layer), these tests
 * boot the entire application context and drive HTTP through MockMvc all the
 * way down into a real PostgreSQL 16 container managed by Testcontainers.
 *
 * <p>What this catches that the slice tests can't:
 * <ul>
 *   <li>Jackson serialization quirks (e.g. Optional&lt;String&gt; mapping)</li>
 *   <li>Bean Validation pipeline misconfiguration</li>
 *   <li>JPA transaction boundary errors (dirty checking, flush timing)</li>
 *   <li>Real SQL behavior — JPQL syntax bugs, FK violations, the actual
 *       {@code MEMBER OF} semantics for the tag filter</li>
 *   <li>Flyway migration mismatches with the {@code @Entity} mapping</li>
 * </ul>
 *
 * <p>State is cleaned via {@link ProductRepository#deleteAll()} in
 * {@link #cleanDatabase()} so each test starts from an empty {@code products}
 * table. Not using {@code @Transactional} class-level rollback because it can
 * mask flush-related bugs that real callers would actually hit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        // Disable Eureka in tests so they don't hang trying to register
        // with a Eureka server that isn't running.
        "eureka.client.enabled=false"
})
class CatalogIntegrationTest {

    private static final SimpleGrantedAuthority CATALOG_ADMIN =
            new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductRepository repository;

    /**
     * Replaces the OAuth2 resource-server JwtDecoder so the context
     * starts without a live Keycloak. {@code with(jwt())} bypasses the
     * decoder entirely on each request — this mock is never invoked.
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("POST then GET-by-id round-trips a product through the full stack")
    void createThenGetByIdRoundTrips() throws Exception {
        String body = """
                {
                  "title": "Aurelia Solitaire Ring",
                  "description": "A single-stone solitaire in 18k recycled gold.",
                  "tags": ["rings", "engagement", "18k"]
                }
                """;

        String location = mvc.perform(post("/api/v1/admin/products")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.title").value("Aurelia Solitaire Ring"))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        // A NEW PRODUCT IS NOT PUBLICLY READABLE, because it is DRAFT and has
        // no price. Hiding it from the LIST is not enough: before this
        // assertion existed the list excluded DRAFT products while
        // GET-by-id served one happily, so an unpublished product page was
        // live to anyone who knew the URL. 404 rather than 403, because a 403
        // confirms the id exists.
        mvc.perform(get(publicPath(location)))
                .andExpect(status().isNotFound());

        // The admin can read it, which is how an operator works on a draft.
        mvc.perform(get("/api/v1/admin/products/" + idOf(location))
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Aurelia Solitaire Ring"))
                .andExpect(jsonPath("$.description").value("A single-stone solitaire in 18k recycled gold."))
                .andExpect(jsonPath("$.tags.length()").value(3))
                .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.containsInAnyOrder("rings", "engagement", "18k")))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.productType").value("JEWELLERY"))
                .andExpect(jsonPath("$.slug").value("aurelia-solitaire-ring"));

        // Pricing it and publishing it is what puts it on the storefront.
        addVariant(idOf(location), "AUR-001", "4500.00");
        publish(idOf(location));

        mvc.perform(get(publicPath(location)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Aurelia Solitaire Ring"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.priceFrom").value(4500.00))
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].sku").value("AUR-001"))
                // No inventory service running in tests + Eureka client
                // disabled, so the Resilience4j fallback engages and
                // availability comes back as UNKNOWN. This is the exact
                // behavior we want when inventory is unreachable in prod.
                .andExpect(jsonPath("$.availability").value("UNKNOWN"));
    }

    @Test
    @DisplayName("publishing is refused until the product has a variant priced above zero")
    void cannotPublishWithoutAPrice() throws Exception {
        String id = idOf(createProduct("Unpriced", "rings"));

        // No variant at all.
        mvc.perform(post("/api/v1/admin/products/{id}/publish", id)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isConflict());

        // A variant at 0.00, which is exactly what the variants migration
        // wrote for every pre-existing product. Zero is not a price, so this
        // must not be publishable either -- otherwise the migration would have
        // made the entire existing catalogue free.
        addVariant(id, "UNP-001", "0.00");
        mvc.perform(post("/api/v1/admin/products/{id}/publish", id)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("only PUBLISHED products reach the storefront list")
    void onlyPublishedProductsAreListed() throws Exception {
        String aurelia = idOf(createProduct("Aurelia", "rings"));
        String lume = idOf(createProduct("Lume", "necklaces"));
        createProduct("Cassini", "earrings");

        // All three exist; none is listed, because creating a product cannot
        // price it, and an unpriced product must not be sellable.
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        addVariant(aurelia, "AUR-1", "4500.00");
        publish(aurelia);
        addVariant(lume, "LUM-1", "1800.00");
        publish(lume);

        // Cassini stays DRAFT, so the count is 2 rather than 3. That exact
        // number is the assertion -- "some products are listed" would pass
        // even if publishing were ignored entirely.
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    @DisplayName("tag filter returns only products carrying that tag — real MEMBER OF in JPQL")
    void tagFilterReturnsOnlyMatchingProducts() throws Exception {
        String[][] seeded = {
                {idOf(createProduct("Aurelia", "rings")), "AUR-1"},
                {idOf(createProduct("Lume", "necklaces")), "LUM-1"},
                {idOf(createProduct("Cassini", "earrings")), "CAS-1"},
        };
        for (String[] each : seeded) {
            addVariant(each[0], each[1], "4500.00");
            publish(each[0]);
        }

        mvc.perform(get("/api/v1/products").param("tag", "rings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Aurelia"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/v1/products").param("tag", "unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("PATCH persists changes — visible to a subsequent GET via dirty checking")
    void patchPersistsChangesToDatabase() throws Exception {
        String location = createProduct("Aurelia Original", "rings");

        mvc.perform(patch(location)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Aurelia Renamed", "description": "Updated copy."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Aurelia Renamed"))
                .andExpect(jsonPath("$.description").value("Updated copy."));

        // Read back through the ADMIN path, because the product is still a
        // draft and the public endpoint deliberately 404s anything not
        // ACTIVE. The subject of this test is that JPA dirty checking
        // persisted the change, not who may read it.
        mvc.perform(get("/api/v1/admin/products/" + idOf(location))
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Aurelia Renamed"))
                .andExpect(jsonPath("$.description").value("Updated copy."))
                .andExpect(jsonPath("$.tags[0]").value("rings"))
                // The slug is NOT regenerated by a rename: it is the product's
                // URL identity, and silently changing it would break every
                // existing link to the product the moment someone fixed a typo
                // in its title.
                .andExpect(jsonPath("$.slug").value("aurelia-original"));

        // And once published, the public path serves the renamed product.
        addVariant(idOf(location), "AUR-REN", "4500.00");
        publish(idOf(location));
        mvc.perform(get(publicPath(location)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Aurelia Renamed"));
    }

    @Test
    @DisplayName("DELETE removes the row — GET-by-id 404s and list count drops")
    void deleteRemovesFromDatabase() throws Exception {
        String location = createProduct("Aurelia", "rings");
        String adminPath = location;

        mvc.perform(delete(adminPath).with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isNoContent());

        mvc.perform(get(publicPath(location)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(delete(adminPath).with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isNotFound());
    }

    /**
     * Helper: deletes via the admin path but WITHOUT a JWT, expecting 401.
     * Confirms the security filter chain rejects unauthenticated requests
     * before they even reach the controller.
     */
    @Test
    @DisplayName("admin DELETE without a JWT is rejected with 401")
    void adminEndpointRejectsUnauthenticated() throws Exception {
        UUID anyId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        mvc.perform(delete("/api/v1/admin/products/{id}", anyId))
                .andExpect(status().isUnauthorized());
    }

    /** The public read path for the product a Location header names. */
    private static String publicPath(String location) {
        return "/api/v1/products/" + idOf(location);
    }

    /** The id out of a Location header of the form /api/v1/admin/products/{id}. */
    private static String idOf(String location) {
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void addVariant(String productId, String sku, String price) throws Exception {
        String body = "{\"sku\": \"" + sku + "\", \"unitPrice\": " + price
                + ", \"currency\": \"ZMW\", \"options\": {}}";
        mvc.perform(post("/api/v1/admin/products/{id}/variants", productId)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private void publish(String productId) throws Exception {
        mvc.perform(post("/api/v1/admin/products/{id}/publish", productId)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isOk());
    }

    /**
     * Convenience helper: creates a product with one tag and returns the
     * {@code Location} header, which is the ADMIN read ({@code /api/v1/admin/products/{id}}):
     * a new product is a DRAFT and the public path 404s it.
     */
    private String createProduct(String title, String tag) throws Exception {
        String body = """
                {
                  "title": "%s",
                  "description": null,
                  "tags": ["%s"]
                }
                """.formatted(title, tag);

        return mvc.perform(post("/api/v1/admin/products")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");
    }
}
