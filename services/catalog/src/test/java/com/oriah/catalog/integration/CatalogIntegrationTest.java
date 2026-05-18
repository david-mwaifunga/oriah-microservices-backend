package com.oriah.catalog.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.repository.ProductRepository;

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
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        // Disable Eureka in tests so they don't hang trying to register
        // with a Eureka server that isn't running.
        "eureka.client.enabled=false"
})
class CatalogIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductRepository repository;

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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.title").value("Aurelia Solitaire Ring"))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Aurelia Solitaire Ring"))
                .andExpect(jsonPath("$.description").value("A single-stone solitaire in 18k recycled gold."))
                .andExpect(jsonPath("$.tags.length()").value(3))
                .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.containsInAnyOrder("rings", "engagement", "18k")))
                // No inventory service running in tests + Eureka client
                // disabled, so the Resilience4j fallback engages and
                // availability comes back as UNKNOWN. This is the exact
                // behavior we want when inventory is unreachable in prod.
                .andExpect(jsonPath("$.availability").value("UNKNOWN"));
    }

    @Test
    @DisplayName("creating three products increments the list and total count")
    void createIncrementsListAndTotalCount() throws Exception {
        createProduct("Aurelia", "rings");
        createProduct("Lume", "necklaces");
        createProduct("Cassini", "earrings");

        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    @DisplayName("tag filter returns only products carrying that tag — real MEMBER OF in JPQL")
    void tagFilterReturnsOnlyMatchingProducts() throws Exception {
        createProduct("Aurelia", "rings");
        createProduct("Lume", "necklaces");
        createProduct("Cassini", "earrings");

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

        mvc.perform(patch(location.replace("/api/v1/products", "/api/v1/admin/products"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Aurelia Renamed", "description": "Updated copy."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Aurelia Renamed"))
                .andExpect(jsonPath("$.description").value("Updated copy."));

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Aurelia Renamed"))
                .andExpect(jsonPath("$.description").value("Updated copy."))
                .andExpect(jsonPath("$.tags[0]").value("rings"));
    }

    @Test
    @DisplayName("DELETE removes the row — GET-by-id 404s and list count drops")
    void deleteRemovesFromDatabase() throws Exception {
        String location = createProduct("Aurelia", "rings");
        String adminPath = location.replace("/api/v1/products", "/api/v1/admin/products");

        mvc.perform(delete(adminPath))
                .andExpect(status().isNoContent());

        mvc.perform(get(location))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(delete(adminPath))
                .andExpect(status().isNotFound());
    }

    /**
     * Convenience helper: creates a product with one tag and returns the
     * {@code Location} header (a {@code /api/v1/products/{id}} path).
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");
    }
}
