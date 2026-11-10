package com.oriah.procurement;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * What procurement's published OpenAPI document actually says about suppliers.
 *
 * <p>The document is the contract the admin screens and the generated frontend
 * types are built from, and the annotations are not the contract: the generated
 * document is, so it is what is asserted here. Two earlier audits found that the
 * platform's documents claimed every call answers 200 and published the error
 * shape nowhere. This pins the opposite for suppliers:
 *
 * <ul>
 *   <li>every operation has the id the frontend will call it by, set by hand so a
 *       second method of the same name can never rename it ({@code _1});</li>
 *   <li>success is documented as what it is (201 for add, 204 for delete), and
 *       every status an operation can answer is there, with the platform's
 *       {@code ProblemDetail} as the body of each failure;</li>
 *   <li>the edit's request says which fields can be cleared (sent as
 *       {@code null}) and which cannot, because that is the whole point of its
 *       three states and a generated form needs to know.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class OpenApiContractTest {

    private static final String SUPPLIERS = "/api/v1/admin/procurement/suppliers";
    private static final String SUPPLIER = SUPPLIERS + "/{id}";
    private static final String CONTACT_INFO = "/api/v1/admin/procurement/contact-info";
    private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private String spec;

    @BeforeEach
    void readTheDocument() throws Exception {
        spec = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
    }

    private <T> T at(String path) {
        try {
            return JsonPath.read(spec, path);
        } catch (PathNotFoundException absent) {
            return null;
        }
    }

    private static String operation(String path, String method) {
        return "$.paths['" + path + "']." + method;
    }

    private Map<String, Object> responses(String path, String method) {
        Map<String, Object> responses = at(operation(path, method) + ".responses");
        assertThat(responses).as("%s %s must be in the document with responses", method.toUpperCase(), path)
                .isNotNull();
        return responses;
    }

    @Test
    @DisplayName("every operation has the id the frontend calls it by")
    void operationIdsArePinned() {
        assertThat((String) at(operation(SUPPLIERS, "post") + ".operationId")).isEqualTo("createSupplier");
        assertThat((String) at(operation(SUPPLIERS, "get") + ".operationId")).isEqualTo("listSuppliers");
        assertThat((String) at(operation(SUPPLIER, "get") + ".operationId")).isEqualTo("getSupplier");
        assertThat((String) at(operation(SUPPLIER, "patch") + ".operationId")).isEqualTo("updateSupplier");
        assertThat((String) at(operation(SUPPLIER, "delete") + ".operationId")).isEqualTo("deleteSupplier");
        assertThat((String) at(operation(CONTACT_INFO, "get") + ".operationId"))
                .isEqualTo("fetchProcurementContactInfo");
    }

    @Test
    @DisplayName("success is documented as what it is: 201 for add, 204 for delete, 200 for the rest")
    void successStatusesAreTruthful() {
        assertThat(responses(SUPPLIERS, "post")).containsKey("201").doesNotContainKey("200");
        assertThat(responses(SUPPLIER, "delete")).containsKey("204").doesNotContainKey("200");
        assertThat(responses(SUPPLIERS, "get")).containsKey("200");
        assertThat(responses(SUPPLIER, "get")).containsKey("200");
        assertThat(responses(SUPPLIER, "patch")).containsKey("200");
    }

    @Test
    @DisplayName("every failure an operation can answer is documented, and says what is wrong in ProblemDetail")
    void failuresAreDocumentedWhereTheyCanHappen() {
        Map<String, List<String>> expected = Map.of(
                "post " + SUPPLIERS, List.of("400", "401", "403", "409", "500"),
                "get " + SUPPLIERS, List.of("400", "401", "403", "500"),
                "get " + SUPPLIER, List.of("400", "401", "403", "404", "500"),
                "patch " + SUPPLIER, List.of("400", "401", "403", "404", "409", "500"),
                "delete " + SUPPLIER, List.of("401", "403", "404", "500"));

        expected.forEach((operation, statuses) -> {
            String[] parts = operation.split(" ");
            Map<String, Object> responses = responses(parts[1], parts[0]);
            for (String status : statuses) {
                assertThat(responses).as("%s must document %s", operation, status).containsKey(status);
                assertThat(String.valueOf(responses.get(status)))
                        .as("%s %s must reference ProblemDetail", operation, status)
                        .contains(PROBLEM_REF)
                        .contains("application/problem+json");
            }
        });
    }

    @Test
    @DisplayName("a failure is not documented where it cannot happen: a list never answers 404, a delete never 400 or 409")
    void impossibleFailuresAreNotClaimed() {
        assertThat(responses(SUPPLIERS, "get")).doesNotContainKey("404").doesNotContainKey("409");
        assertThat(responses(SUPPLIER, "delete")).doesNotContainKey("400").doesNotContainKey("409");
    }

    @Test
    @DisplayName("ProblemDetail is a published schema")
    void problemDetailIsPublished() {
        assertThat((Object) at("$.components.schemas.ProblemDetail")).isNotNull();
    }

    @Test
    @DisplayName("the edit's request lists its six fields, none of them required")
    void updateRequestHasSixOptionalFields() {
        Map<String, Object> properties = at("$.components.schemas.UpdateSupplierRequest.properties");

        assertThat(properties).as("UpdateSupplierRequest must be published with its fields").isNotNull();
        assertThat(properties.keySet())
                .containsExactlyInAnyOrder("name", "country", "contactName", "contactDetails", "paymentNotes",
                        "active");
        List<String> required = at("$.components.schemas.UpdateSupplierRequest.required");
        assertThat(required).as("an edit sends only what changes, so nothing is required")
                .isNullOrEmpty();
    }

    @Test
    @DisplayName("the edit's request says which fields can be cleared with null and which cannot")
    void updateRequestSaysWhatCanBeCleared() {
        for (String clearable : List.of("contactName", "contactDetails", "paymentNotes")) {
            assertThat(isNullable("UpdateSupplierRequest", clearable))
                    .as("%s can be cleared by sending null, so the schema must allow null; it says %s",
                            clearable, describe("UpdateSupplierRequest", clearable))
                    .isTrue();
        }
        for (String fixed : List.of("name", "country", "active")) {
            assertThat(isNullable("UpdateSupplierRequest", fixed))
                    .as("%s can be replaced but never emptied, so the schema must not allow null; it says %s",
                            fixed, describe("UpdateSupplierRequest", fixed))
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a supplier says its optional fields may be null, and its required ones may not")
    void supplierResponseSaysWhatMayBeNull() {
        for (String optional : List.of("contactName", "contactDetails", "paymentNotes")) {
            assertThat(isNullable("Supplier", optional))
                    .as("Supplier.%s may be null; the schema says %s", optional, describe("Supplier", optional))
                    .isTrue();
        }
        for (String always : List.of("name", "country", "active")) {
            assertThat(isNullable("Supplier", always)).as("Supplier.%s", always).isFalse();
        }
    }

    @Test
    @DisplayName("the list is a page of suppliers: content, and the totals a pager needs")
    void listIsAPageOfSuppliers() {
        String listed = String.valueOf((Object) at(operation(SUPPLIERS, "get") + ".responses['200']"));
        assertThat(listed).as("the list's 200 must name a schema").contains("$ref");

        // Follow the reference, whatever springdoc calls the page type.
        String ref = at(operation(SUPPLIERS, "get") + ".responses['200'].content['*/*'].schema['$ref']");
        if (ref == null) {
            ref = at(operation(SUPPLIERS, "get") + ".responses['200'].content['application/json'].schema['$ref']");
        }
        assertThat(ref).as("the list's 200 schema reference").isNotNull();
        String schema = ref.substring(ref.lastIndexOf('/') + 1);
        Map<String, Object> properties = at("$.components.schemas['" + schema + "'].properties");

        assertThat(properties).as("the page schema %s", schema).isNotNull();
        assertThat(properties.keySet()).contains("content", "totalElements", "totalPages", "number", "size");
        assertThat(String.valueOf(properties.get("content"))).contains("#/components/schemas/Supplier");
    }

    @Test
    @DisplayName("the list documents its filters and its bounds")
    void listDocumentsItsParameters() {
        List<Map<String, Object>> parameters = at(operation(SUPPLIERS, "get") + ".parameters");

        assertThat(parameters).isNotNull();
        assertThat(parameters).extracting(p -> p.get("name"))
                .contains("page", "size", "active", "q");
    }

    private String describe(String schema, String property) {
        return String.valueOf((Object) at("$.components.schemas['" + schema + "'].properties['" + property + "']"));
    }

    /** True when the schema property admits null, in OpenAPI 3.0 or 3.1 spelling. */
    private boolean isNullable(String schema, String property) {
        Map<String, Object> node = at("$.components.schemas['" + schema + "'].properties['" + property + "']");
        assertThat(node).as("%s.%s must be in the document", schema, property).isNotNull();
        if (Boolean.TRUE.equals(node.get("nullable"))) {
            return true;
        }
        if (node.get("type") instanceof List<?> types && types.contains("null")) {
            return true;
        }
        for (String combined : List.of("anyOf", "oneOf")) {
            if (node.get(combined) instanceof List<?> options) {
                for (Object option : options) {
                    if (option instanceof Map<?, ?> o && "null".equals(o.get("type"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
