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
                // 400: the id in the path is not a UUID. 409: a purchase order names the supplier.
                "delete " + SUPPLIER, List.of("400", "401", "403", "404", "409", "500"));

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
    @DisplayName("a failure is not documented where it cannot happen: a list never answers 404 or 409, nor does a single read answer 409")
    void impossibleFailuresAreNotClaimed() {
        assertThat(responses(SUPPLIERS, "get")).doesNotContainKey("404").doesNotContainKey("409");
        assertThat(responses(SUPPLIER, "get")).doesNotContainKey("409");
    }

    @Test
    @DisplayName("ProblemDetail is a published schema")
    void problemDetailIsPublished() {
        assertThat((Object) at("$.components.schemas.ProblemDetail")).isNotNull();
    }

    @Test
    @DisplayName("the edit's request lists its six fields and the version it was made from, none of them required")
    void updateRequestHasSixOptionalFields() {
        Map<String, Object> properties = at("$.components.schemas.UpdateSupplierRequest.properties");

        assertThat(properties).as("UpdateSupplierRequest must be published with its fields").isNotNull();
        assertThat(properties.keySet())
                .containsExactlyInAnyOrder("name", "country", "contactName", "contactDetails", "paymentNotes",
                        "active", "version");
        // The version is a whole number in the contract, though the setter takes the raw JSON value.
        assertThat((String) at("$.components.schemas.UpdateSupplierRequest.properties.version.type"))
                .as("the edit's version is documented as an integer").isEqualTo("integer");
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
        for (String fixed : List.of("name", "country", "active", "version")) {
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
    @DisplayName("the list documents its filters, and the bounds a client form needs: size 1 to 100 (default 50), page from 0")
    void listDocumentsItsParameters() {
        List<Map<String, Object>> parameters = at(operation(SUPPLIERS, "get") + ".parameters");

        assertThat(parameters).isNotNull();
        assertThat(parameters).extracting(p -> p.get("name"))
                .contains("page", "size", "active", "q");

        Map<String, Object> size = schemaOf(parameters, "size");
        assertThat(((Number) size.get("minimum")).intValue()).as("size minimum").isEqualTo(1);
        assertThat(((Number) size.get("maximum")).intValue()).as("size maximum").isEqualTo(100);
        assertThat(((Number) size.get("default")).intValue()).as("size default").isEqualTo(50);
        Map<String, Object> page = schemaOf(parameters, "page");
        assertThat(((Number) page.get("minimum")).intValue()).as("page minimum").isEqualTo(0);
        assertThat(((Number) page.get("default")).intValue()).as("page default").isEqualTo(0);
    }

    // ------------------------------------------------------------ purchase orders

    private static final String ORDERS = "/api/v1/admin/procurement/purchase-orders";
    private static final String ORDER = ORDERS + "/{id}";
    private static final String ORDER_CANCEL = ORDER + "/cancel";

    @Test
    @DisplayName("purchase orders: every operation has the id the frontend calls it by, and success is documented as what it is")
    void purchaseOrderOperationsArePinned() {
        assertThat((String) at(operation(ORDERS, "post") + ".operationId")).isEqualTo("createPurchaseOrder");
        assertThat((String) at(operation(ORDERS, "get") + ".operationId")).isEqualTo("listPurchaseOrders");
        assertThat((String) at(operation(ORDER, "get") + ".operationId")).isEqualTo("getPurchaseOrder");
        assertThat((String) at(operation(ORDER, "patch") + ".operationId")).isEqualTo("updatePurchaseOrder");
        assertThat((String) at(operation(ORDER_CANCEL, "post") + ".operationId")).isEqualTo("cancelPurchaseOrder");

        assertThat(responses(ORDERS, "post")).containsKey("201").doesNotContainKey("200");
        assertThat(responses(ORDERS, "get")).containsKey("200");
        assertThat(responses(ORDER, "get")).containsKey("200");
        assertThat(responses(ORDER, "patch")).containsKey("200");
        assertThat(responses(ORDER_CANCEL, "post")).containsKey("200").doesNotContainKey("201");
    }

    @Test
    @DisplayName("purchase orders: every failure an operation can answer is documented with ProblemDetail, and none that cannot happen")
    void purchaseOrderFailuresAreDocumented() {
        Map<String, List<String>> expected = Map.of(
                // 404 no such supplier; 409 the supplier is stopped; 503 catalog could not be asked.
                "post " + ORDERS, List.of("400", "401", "403", "404", "409", "500", "503"),
                "get " + ORDERS, List.of("400", "401", "403", "500"),
                "get " + ORDER, List.of("400", "401", "403", "404", "500"),
                "patch " + ORDER, List.of("400", "401", "403", "404", "409", "500", "503"),
                "post " + ORDER_CANCEL, List.of("400", "401", "403", "404", "409", "500"));

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
        // A list and a read never answer 409; cancelling never asks catalog.
        assertThat(responses(ORDERS, "get")).doesNotContainKey("404").doesNotContainKey("409")
                .doesNotContainKey("503");
        assertThat(responses(ORDER, "get")).doesNotContainKey("409").doesNotContainKey("503");
        assertThat(responses(ORDER_CANCEL, "post")).doesNotContainKey("503");
    }

    @Test
    @DisplayName("purchase orders: a change and a cancel must say which version they were made from; a new order must have a supplier and lines")
    void purchaseOrderRequestsSaySoWhatIsRequired() {
        assertThat((List<String>) at("$.components.schemas.CreatePurchaseOrderRequest.required"))
                .contains("supplierId", "lines");
        assertThat((List<String>) at("$.components.schemas.UpdatePurchaseOrderRequest.required"))
                .contains("version").doesNotContain("lines", "placedOn", "note");
        assertThat((List<String>) at("$.components.schemas.VersionRequest.required")).contains("version");
        assertThat((List<String>) at("$.components.schemas.PurchaseOrderLineRequest.required"))
                .contains("variantId", "quantity", "totalPaid");
    }

    @Test
    @DisplayName("purchase orders: a reply says which of its fields may be null (the note, the consignment, a line's product title)")
    void purchaseOrderResponseSaysWhatMayBeNull() {
        for (String optional : List.of("note", "consignmentId")) {
            assertThat(isNullable("PurchaseOrder", optional))
                    .as("PurchaseOrder.%s may be null; the schema says %s", optional, describe("PurchaseOrder", optional))
                    .isTrue();
        }
        assertThat(isNullable("PurchaseOrderLine", "productTitle")).isTrue();
        for (String always : List.of("reference", "status", "totalPaid", "currency", "version")) {
            assertThat(isNullable("PurchaseOrder", always)).as("PurchaseOrder.%s", always).isFalse();
        }
        assertThat(isNullable("PurchaseOrderLine", "variantTitle")).isFalse();
    }

    @Test
    @DisplayName("purchase orders: the list documents its filters and the same page bounds as every list: size 1 to 100, page from 0")
    void purchaseOrderListDocumentsItsParameters() {
        List<Map<String, Object>> parameters = at(operation(ORDERS, "get") + ".parameters");

        assertThat(parameters).isNotNull();
        assertThat(parameters).extracting(p -> p.get("name")).contains("page", "size", "supplierId", "status");
        Map<String, Object> size = schemaOf(parameters, "size");
        assertThat(((Number) size.get("minimum")).intValue()).isEqualTo(1);
        assertThat(((Number) size.get("maximum")).intValue()).isEqualTo(100);
        assertThat(((Number) schemaOf(parameters, "page").get("minimum")).intValue()).isEqualTo(0);
    }

    // ------------------------------------------------------------- consignments

    private static final String CONSIGNMENTS = "/api/v1/admin/procurement/consignments";
    private static final String CONSIGNMENT = CONSIGNMENTS + "/{id}";
    private static final String C_LINE = CONSIGNMENT + "/lines/{lineId}";
    private static final String C_OPENING = CONSIGNMENT + "/opening-lines";
    private static final String C_COSTS = CONSIGNMENT + "/cost-lines";
    private static final String C_ORDERS = CONSIGNMENT + "/purchase-orders";
    private static final String C_ORDER = C_ORDERS + "/{orderId}";
    private static final String C_ALLOCATION = CONSIGNMENT + "/allocation";

    @Test
    @DisplayName("consignments: every operation has the id the frontend calls it by, and success is documented as what it is")
    void consignmentOperationsArePinned() {
        assertThat((String) at(operation(CONSIGNMENTS, "post") + ".operationId")).isEqualTo("createConsignment");
        assertThat((String) at(operation(CONSIGNMENTS, "get") + ".operationId")).isEqualTo("listConsignments");
        assertThat((String) at(operation(CONSIGNMENT, "get") + ".operationId")).isEqualTo("getConsignment");
        assertThat((String) at(operation(CONSIGNMENT, "put") + ".operationId")).isEqualTo("updateConsignmentHeader");
        assertThat((String) at(operation(CONSIGNMENT, "delete") + ".operationId")).isEqualTo("deleteConsignment");
        assertThat((String) at(operation(C_LINE, "put") + ".operationId")).isEqualTo("updateConsignmentLine");
        assertThat((String) at(operation(C_OPENING, "put") + ".operationId")).isEqualTo("replaceOpeningLines");
        assertThat((String) at(operation(C_COSTS, "put") + ".operationId")).isEqualTo("replaceCostLines");
        assertThat((String) at(operation(C_ORDERS, "post") + ".operationId")).isEqualTo("attachPurchaseOrder");
        assertThat((String) at(operation(C_ORDER, "delete") + ".operationId")).isEqualTo("detachPurchaseOrder");
        assertThat((String) at(operation(C_ALLOCATION, "get") + ".operationId")).isEqualTo("previewAllocation");

        assertThat(responses(CONSIGNMENTS, "post")).containsKey("201").doesNotContainKey("200");
        assertThat(responses(CONSIGNMENT, "delete")).containsKey("204").doesNotContainKey("200");
        assertThat(responses(C_ORDERS, "post")).containsKey("200").doesNotContainKey("201");
        assertThat(responses(C_ORDER, "delete")).containsKey("200").doesNotContainKey("204");
        assertThat(responses(C_COSTS, "put")).containsKey("200");
        assertThat(responses(C_ALLOCATION, "get")).containsKey("200");
    }

    @Test
    @DisplayName("consignments: every failure an operation can answer is documented with ProblemDetail, and none that cannot happen")
    void consignmentFailuresAreDocumented() {
        Map<String, List<String>> expected = new java.util.HashMap<>();
        // 404: an order does not exist. 409: an order has changed or is taken. 503: opening stock asks catalog.
        expected.put("post " + CONSIGNMENTS, List.of("400", "401", "403", "404", "409", "500", "503"));
        expected.put("get " + CONSIGNMENTS, List.of("400", "401", "403", "500"));
        expected.put("get " + CONSIGNMENT, List.of("400", "401", "403", "404", "500"));
        expected.put("put " + CONSIGNMENT, List.of("400", "401", "403", "404", "409", "500"));
        expected.put("delete " + CONSIGNMENT, List.of("400", "401", "403", "404", "409", "500"));
        expected.put("put " + C_LINE, List.of("400", "401", "403", "404", "409", "500"));
        expected.put("put " + C_OPENING, List.of("400", "401", "403", "404", "409", "500", "503"));
        expected.put("put " + C_COSTS, List.of("400", "401", "403", "404", "409", "500"));
        expected.put("post " + C_ORDERS, List.of("400", "401", "403", "404", "409", "500"));
        expected.put("delete " + C_ORDER, List.of("400", "401", "403", "404", "409", "500"));
        expected.put("get " + C_ALLOCATION, List.of("400", "401", "403", "404", "500"));

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
        assertThat(responses(CONSIGNMENTS, "get")).doesNotContainKey("404").doesNotContainKey("409")
                .doesNotContainKey("503");
        assertThat(responses(CONSIGNMENT, "get")).doesNotContainKey("409").doesNotContainKey("503");
        assertThat(responses(C_ALLOCATION, "get")).doesNotContainKey("409");
        // Only typing the opening stock asks the catalogue.
        assertThat(responses(C_COSTS, "put")).doesNotContainKey("503");
        assertThat(responses(C_ORDERS, "post")).doesNotContainKey("503");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("consignments: what each request must carry is said, so a generated form knows")
    void consignmentRequestsSayWhatIsRequired() {
        assertThat((List<String>) at("$.components.schemas.UpdateConsignmentRequest.required")).contains("version");
        assertThat((List<String>) at("$.components.schemas.LineDetailsRequest.required")).contains("version");
        assertThat((List<String>) at("$.components.schemas.OpeningLinesRequest.required"))
                .contains("version", "lines");
        assertThat((List<String>) at("$.components.schemas.CostLinesRequest.required"))
                .contains("version", "costLines");
        assertThat((List<String>) at("$.components.schemas.AttachOrderRequest.required"))
                .contains("version", "purchaseOrderId", "purchaseOrderVersion");
        assertThat((List<String>) at("$.components.schemas.CostLineRequest.required")).contains("costType", "amount")
                .doesNotContain("basis", "treatment", "shares", "note", "incurredOn");
        assertThat((List<String>) at("$.components.schemas.OpeningLineRequest.required"))
                .contains("variantId", "quantity", "totalCost");
        assertThat((List<String>) at("$.components.schemas.CostShareRequest.required")).contains("lineId", "amount");
        assertThat((List<String>) at("$.components.schemas.ConsignmentOrderRef.required")).contains("id", "version");
        assertThat((List<String>) at("$.components.schemas.CreateConsignmentRequest.required"))
                .as("either orders or opening lines, so neither is required on its own").isNullOrEmpty();
    }

    @Test
    @DisplayName("consignments: a reply says which of its fields may be null")
    void consignmentResponseSaysWhatMayBeNull() {
        for (String optional : List.of("note", "chargesBasis", "declarant", "clearanceForm", "zraAssessmentRef",
                "licensedAgentUsed", "receivedAt")) {
            assertThat(isNullable("Consignment", optional))
                    .as("Consignment.%s may be null; the schema says %s", optional, describe("Consignment", optional))
                    .isTrue();
        }
        for (String always : List.of("reference", "status", "opening", "estimated", "currency", "goodsTotal", "version")) {
            assertThat(isNullable("Consignment", always)).as("Consignment.%s", always).isFalse();
        }
        for (String optional : List.of("supplier", "purchaseOrder", "productTitle", "chargeableWeightGrams",
                "originCountry", "hsCode")) {
            assertThat(isNullable("ConsignmentLine", optional))
                    .as("ConsignmentLine.%s may be null; the schema says %s", optional, describe("ConsignmentLine", optional))
                    .isTrue();
        }
        assertThat(isNullable("ConsignmentLine", "sku")).isFalse();
    }

    @Test
    @DisplayName("consignments: the list documents its filters and the same page bounds as every list")
    void consignmentListDocumentsItsParameters() {
        List<Map<String, Object>> parameters = at(operation(CONSIGNMENTS, "get") + ".parameters");

        assertThat(parameters).extracting(p -> p.get("name")).contains("page", "size", "status", "opening");
        Map<String, Object> size = schemaOf(parameters, "size");
        assertThat(((Number) size.get("minimum")).intValue()).isEqualTo(1);
        assertThat(((Number) size.get("maximum")).intValue()).isEqualTo(100);
        assertThat(((Number) schemaOf(parameters, "page").get("minimum")).intValue()).isEqualTo(0);
    }

    @Test
    @DisplayName("consignments: the version a delete or a detach was decided from is a required query parameter")
    void versionIsRequiredOnDeleteAndDetach() {
        for (String path : List.of(CONSIGNMENT, C_ORDER)) {
            List<Map<String, Object>> parameters = at(operation(path, "delete") + ".parameters");
            Map<String, Object> version = parameters.stream().filter(p -> "version".equals(p.get("name"))).findFirst()
                    .orElseThrow(() -> new AssertionError("no version parameter on delete " + path));
            assertThat(version.get("required")).as("delete %s: version", path).isEqualTo(true);
            assertThat(version.get("in")).isEqualTo("query");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaOf(List<Map<String, Object>> parameters, String name) {
        Map<String, Object> parameter = parameters.stream().filter(p -> name.equals(p.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("no parameter " + name));
        Map<String, Object> schema = (Map<String, Object>) parameter.get("schema");
        assertThat(schema).as("the schema of parameter %s", name).isNotNull();
        return schema;
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
