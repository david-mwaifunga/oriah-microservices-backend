package com.oriah.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * What catalog's published OpenAPI document actually says.
 *
 * <h2>Why this exists</h2>
 *
 * <p>An audit of all 12 baseline specs found that <strong>every one of the 51
 * operations documented exactly one response: {@code 200}</strong>. Not a
 * single 400, 401, 403, 404 or 409 anywhere, and {@code ProblemDetail} — the
 * shape every error in this system actually returns — was published as a schema
 * <em>nowhere</em>. So the contract claimed every call succeeds.
 *
 * <p>Worse than silent: it was <strong>wrong</strong> about success on 11
 * endpoints across the platform, two of them here.
 * {@code POST /api/v1/admin/products} answers <strong>201</strong> with a
 * {@code Location} header and {@code DELETE} answers <strong>204</strong>, and
 * both were documented as 200 — because springdoc cannot see inside
 * {@code ResponseEntity.created(...)} and falls back to 200.
 *
 * <p>The additions-only gate ({@code scripts/openapi-diff.ps1}) proves nothing
 * was <em>removed</em>. It cannot prove the document says anything useful. That
 * is what this test is for, and it is deliberately asserted against the real
 * springdoc output rather than against the annotations, because the annotations
 * are not the contract — the generated document is.
 */
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
// Deliberately the SAME annotations and properties as CatalogIntegrationTest,
// so this shares its cached context instead of booting a second one. Adding
// spring.cloud.config.enabled=false here (as the advice and contact-info
// guards do, where it is load-bearing) changed the context cache key and cost
// a 120-second boot for assertions that do not care either way.
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class OpenApiContractTest {

    private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static JsonNode spec;

    @BeforeAll
    static void resetCache() {
        spec = null;
    }

    private JsonNode spec() throws Exception {
        if (spec == null) {
            String json = mvc.perform(get("/v3/api-docs"))
                    .andReturn().getResponse().getContentAsString();
            spec = new ObjectMapper().readTree(json);
        }
        return spec;
    }

    private JsonNode op(String method, String path) throws Exception {
        JsonNode node = spec().path("paths").path(path).path(method);
        assertThat(node.isMissingNode())
                .as("%s %s must be present in the document", method.toUpperCase(), path)
                .isFalse();
        return node;
    }

    // ------------------------------------------------------------------
    // The document is wrong about success on two endpoints
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /admin/products documents 201, which is what it returns")
    void createDocumentsCreated() throws Exception {
        JsonNode responses = op("post", "/api/v1/admin/products").path("responses");

        assertThat(responses.has("201"))
                .as("the handler is ResponseEntity.created(...).body(...) -- springdoc cannot "
                        + "see inside ResponseEntity and documented 200")
                .isTrue();
    }

    @Test
    @DisplayName("DELETE /admin/products/{id} documents 204")
    void deleteDocumentsNoContent() throws Exception {
        JsonNode responses = op("delete", "/api/v1/admin/products/{id}").path("responses");

        assertThat(responses.has("204"))
                .as("the handler returns ResponseEntity.noContent()")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // The error contract is published at all
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ProblemDetail is a published schema")
    void problemDetailIsPublished() throws Exception {
        assertThat(spec().path("components").path("schemas").has("ProblemDetail"))
                .as("every error this service returns is an RFC 7807 ProblemDetail, and the "
                        + "document published the shape nowhere -- so a client generator had "
                        + "no type for any failure")
                .isTrue();
    }

    @Test
    @DisplayName("the three 404-capable endpoints document 404 -> ProblemDetail")
    void notFoundIsDocumentedWhereItCanHappen() throws Exception {
        // Exactly the three sites Tier 5 gave a body to. Documented per-endpoint
        // rather than blanket-applied: a 404 on GET /api/v1/products (the list)
        // cannot happen, and documenting it there would be a different kind of
        // lie.
        for (String[] target : new String[][]{
                {"get", "/api/v1/products/{id}"},
                {"patch", "/api/v1/admin/products/{id}"},
                {"delete", "/api/v1/admin/products/{id}"}}) {
            JsonNode notFound = op(target[0], target[1]).path("responses").path("404");

            assertThat(notFound.isMissingNode())
                    .as("%s %s can answer 404 and must say so", target[0], target[1])
                    .isFalse();
            assertThat(notFound.toString())
                    .as("%s %s's 404 must reference ProblemDetail", target[0], target[1])
                    .contains(PROBLEM_REF);
        }
    }

    @Test
    @DisplayName("the product list does NOT document a 404")
    void listDoesNotClaimItCan404() throws Exception {
        // The counterpart to the test above, and the reason the error responses
        // are not applied with a blanket customizer: an empty page is a 200
        // with an empty content array, so a documented 404 here would send a
        // client writing a branch that can never be reached.
        assertThat(op("get", "/api/v1/products").path("responses").has("404"))
                .as("GET /api/v1/products returns an empty page, never a 404")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // operationIds are pinned to what springdoc already generated
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every operationId matches the Tier 0 baseline exactly")
    void operationIdsAreUnchangedFromTheBaseline() throws Exception {
        // Pinning is a deliberate no-op: each id is set to the value springdoc
        // derived from the method name, so the additions-only gate stays clean.
        // The point is to stop springdoc DERIVING them, because the moment two
        // methods in a service share a name it starts appending _1 -- and which
        // handler receives the suffix is decided by registration order, not
        // source order. order already has a get/get_1 pair for exactly that
        // reason.
        assertThat(op("get", "/api/v1/products").path("operationId").asText()).isEqualTo("list");
        assertThat(op("get", "/api/v1/products/{id}").path("operationId").asText()).isEqualTo("getById");
        assertThat(op("post", "/api/v1/admin/products").path("operationId").asText()).isEqualTo("create");
        assertThat(op("patch", "/api/v1/admin/products/{id}").path("operationId").asText()).isEqualTo("update");
        assertThat(op("delete", "/api/v1/admin/products/{id}").path("operationId").asText()).isEqualTo("delete");
        assertThat(op("get", "/api/v1/products/contact-info").path("operationId").asText())
                .isEqualTo("fetchCatalogContactInfo");
    }

    @Test
    @DisplayName("tags are human names, not springdoc's class-name defaults")
    void tagsAreNamedDeliberately() throws Exception {
        // Free to change: openapi-typescript, which generates oriah-web's
        // types, emits paths/operations/components and does not read tags at
        // all. They drive the Swagger UI grouping, which is who this is for.
        assertThat(op("get", "/api/v1/products").path("tags").toString())
                .as("was the generated 'product-controller'")
                .contains("Products");
        assertThat(op("post", "/api/v1/admin/products").path("tags").toString())
                .as("was the generated 'admin-product-controller'")
                .contains("Products (admin)");
    }
}
