package com.oriah.procurement.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;
import com.oriah.procurement.client.CatalogClient;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Placing, reading, changing and cancelling purchase orders over HTTP, with a real
 * PostgreSQL and the real security rules. Catalog is stood in for by a mock: its own
 * lookup endpoint is tested in catalog, and the call that carries the token is tested in
 * {@code CatalogClientWiringTest}.
 *
 * <p>A purchase order is what Oriah placed with one supplier (CLIENT_DECISIONS 4.1): one
 * line per item, each with how many and the all-in total that left the bank.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class PurchaseOrderHttpTest {

    static final String PATH = "/api/v1/admin/procurement/purchase-orders";
    static final String SUPPLIERS = "/api/v1/admin/procurement/suppliers";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalog;

    @BeforeEach
    void clean() {
        ProcurementTestData.wipe(jdbc);
        Mockito.reset(catalog);
    }

    static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    // ----------------------------------------------------------- fixtures

    private String supplier(String name) throws Exception {
        MvcResult created = mvc.perform(post(SUPPLIERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"country\":\"AE\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    /** Catalog knows these variants: a sellable ring (DRAFT, as it is before it is priced) and a packaging box. */
    private static Entry ring(UUID id, String sku) {
        return new Entry(id, true, UUID.randomUUID(), "Aurelia Ring", "Aurelia Ring - Ring Size M", sku,
                "SELLABLE", "DRAFT", true, false);
    }

    private static Entry box(UUID id, String sku) {
        return new Entry(id, true, UUID.randomUUID(), "Gift box", "Gift box", sku, "PACKAGING", "DRAFT", true, false);
    }

    private void catalogKnows(Entry... entries) {
        Map<UUID, Entry> byId = new java.util.HashMap<>();
        for (Entry e : entries) {
            byId.put(e.variantId(), e);
        }
        when(catalog.lookup(any())).thenAnswer(call -> {
            CatalogClient.VariantLookupBody body = call.getArgument(0);
            return new VariantLookupResult(body.variantIds().stream()
                    .map(id -> byId.getOrDefault(id, new Entry(id, false, null, null, null, null, null, null, null, null)))
                    .toList());
        });
    }

    static String order(String supplierId, String... lines) {
        return "{\"supplierId\":\"" + supplierId + "\",\"lines\":[" + String.join(",", lines) + "]}";
    }

    static String line(UUID variant, int quantity, String totalPaid) {
        return "{\"variantId\":\"" + variant + "\",\"quantity\":" + quantity + ",\"totalPaid\":" + totalPaid + "}";
    }

    private ResultActions place(String body) throws Exception {
        return mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String placed(String body) throws Exception {
        MvcResult result = place(body).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    // ------------------------------------------------------------- placing

    @Test
    @DisplayName("placing an order answers 201 with the order as stored: a reference, the supplier, the lines with their catalogue names, the total and who made it")
    void placeAnswers201() throws Exception {
        String supplier = supplier("Dubai Gold");
        UUID ringId = UUID.randomUUID();
        UUID boxId = UUID.randomUUID();
        catalogKnows(ring(ringId, "RING-M"), box(boxId, "BOX-1"));

        MvcResult result = place(order(supplier, line(ringId, 7, "10500.00"), line(boxId, 200, "10000.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(matchesPattern("PO-\\d{6}")))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.supplier.id").value(supplier))
                .andExpect(jsonPath("$.supplier.name").value("Dubai Gold"))
                .andExpect(jsonPath("$.supplier.country").value("AE"))
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.totalPaid").value(20500.00))
                .andExpect(jsonPath("$.consignmentId").doesNotExist())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdBy").value("kc-maya"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].position").value(1))
                .andExpect(jsonPath("$.lines[0].variantId").value(ringId.toString()))
                .andExpect(jsonPath("$.lines[0].sku").value("RING-M"))
                .andExpect(jsonPath("$.lines[0].variantTitle").value("Aurelia Ring - Ring Size M"))
                .andExpect(jsonPath("$.lines[0].kind").value("SELLABLE"))
                .andExpect(jsonPath("$.lines[0].quantity").value(7))
                .andExpect(jsonPath("$.lines[0].totalPaid").value(10500.00))
                .andExpect(jsonPath("$.lines[1].kind").value("PACKAGING"))
                .andReturn();

        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(PATH + "/" + id);
    }

    @Test
    @DisplayName("each order gets the next reference number")
    void referencesCountUp() throws Exception {
        String supplier = supplier("Counting Supplier");
        UUID v = UUID.randomUUID();
        catalogKnows(ring(v, "R1"));

        String first = JsonPath.read(place(order(supplier, line(v, 1, "1.00"))).andReturn().getResponse().getContentAsString(), "$.reference");
        String second = JsonPath.read(place(order(supplier, line(v, 1, "1.00"))).andReturn().getResponse().getContentAsString(), "$.reference");

        assertThat(Integer.parseInt(second.substring(3))).isGreaterThan(Integer.parseInt(first.substring(3)));
    }

    @Test
    @DisplayName("all the variants are checked with catalog in ONE call")
    void oneCatalogCall() throws Exception {
        String supplier = supplier("One Call Supplier");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        catalogKnows(ring(a, "A"), ring(b, "B"), box(c, "C"));

        place(order(supplier, line(a, 1, "1.00"), line(b, 1, "1.00"), line(c, 1, "1.00"))).andExpect(status().isCreated());

        verify(catalog, times(1)).lookup(any());
    }

    @Test
    @DisplayName("an order with no lines, too many lines, a bad quantity or an amount finer than a ngwee is a 400 naming the field")
    void bodyValidation() throws Exception {
        String supplier = supplier("Validation Supplier");
        UUID v = UUID.randomUUID();
        catalogKnows(ring(v, "V"));

        place(order(supplier)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("lines")));
        place(order(supplier, line(v, 0, "10.00"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("lines[0].quantity")));
        place(order(supplier, line(v, 1, "0.00"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("lines[0].totalPaid")));
        place(order(supplier, line(v, 1, "10.005"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("lines[0].totalPaid")));
        place("{\"lines\":[" + line(v, 1, "1.00") + "]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("supplierId")));

        List<String> hundredAndOne = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            hundredAndOne.add(line(UUID.randomUUID(), 1, "1.00"));
        }
        place(order(supplier, hundredAndOne.toArray(String[]::new))).andExpect(status().isBadRequest());
        verify(catalog, never()).lookup(any());
    }

    @Test
    @DisplayName("the same variant on two lines, or an order dated tomorrow, is a 400")
    void domainRules() throws Exception {
        String supplier = supplier("Rules Supplier");
        UUID v = UUID.randomUUID();
        catalogKnows(ring(v, "V"));

        place(order(supplier, line(v, 1, "1.00"), line(v, 2, "2.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("more than once")));
        place("{\"supplierId\":\"" + supplier + "\",\"placedOn\":\"" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2)
                + "\",\"lines\":[" + line(v, 1, "1.00") + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("future")));
    }

    // ------------------------------------------------------- the supplier

    @Test
    @DisplayName("a supplier that does not exist is a 404, and nothing is saved")
    void unknownSupplier() throws Exception {
        UUID v = UUID.randomUUID();
        catalogKnows(ring(v, "V"));

        place(order(UUID.randomUUID().toString(), line(v, 1, "1.00"))).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class)).isZero();
    }

    @Test
    @DisplayName("a supplier we have stopped using gets no new orders: 409, and the supplier can be started again")
    void inactiveSupplierGetsNoOrders() throws Exception {
        String supplier = supplier("Stopped Supplier");
        UUID v = UUID.randomUUID();
        catalogKnows(ring(v, "V"));
        mvc.perform(patch(SUPPLIERS + "/" + supplier).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isOk());

        place(order(supplier, line(v, 1, "1.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("stopped")));

        mvc.perform(patch(SUPPLIERS + "/" + supplier).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}")).andExpect(status().isOk());
        place(order(supplier, line(v, 1, "1.00"))).andExpect(status().isCreated());
    }

    // ---------------------------------------------------------- catalogue

    @Test
    @DisplayName("a variant catalog does not know is a 400 naming the line")
    void unknownVariant() throws Exception {
        String supplier = supplier("Unknown Variant Supplier");
        catalogKnows();

        place(order(supplier, line(UUID.randomUUID(), 1, "1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'lines[0].variantId')].message").value(hasItem(containsString("no such variant"))));
    }

    @Test
    @DisplayName("a switched-off variant, a deleted product and an archived product are refused; a DRAFT one and packaging are fine")
    void variantEligibility() throws Exception {
        String supplier = supplier("Eligibility Supplier");
        UUID off = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        UUID packaging = UUID.randomUUID();
        catalogKnows(
                new Entry(off, true, UUID.randomUUID(), "P", "P", "OFF", "SELLABLE", "DRAFT", false, false),
                new Entry(deleted, true, UUID.randomUUID(), "P", "P", "DEL", "SELLABLE", "ARCHIVED", true, true),
                new Entry(archived, true, UUID.randomUUID(), "P", "P", "ARC", "SELLABLE", "ARCHIVED", true, false),
                ring(draft, "DRAFT"), box(packaging, "PACK"));

        for (UUID bad : List.of(off, deleted, archived)) {
            place(order(supplier, line(bad, 1, "1.00"))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].variantId"));
        }
        place(order(supplier, line(draft, 1, "1.00"), line(packaging, 1, "1.00"))).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("when catalog cannot be reached the answer is 503 with Retry-After and nothing is saved: a guess would be worse")
    void catalogDown() throws Exception {
        String supplier = supplier("Offline Supplier");
        when(catalog.lookup(any())).thenThrow(FeignException.errorStatus("lookup",
                feign.Response.builder().status(503).reason("down").request(
                        Request.create(Request.HttpMethod.POST, "http://catalog", Map.of(), null, null, new RequestTemplate()))
                        .headers(Map.of()).build()));

        place(order(supplier, line(UUID.randomUUID(), 1, "1.00")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class)).isZero();
    }

    // -------------------------------------------------------------- reading

    @Test
    @DisplayName("an order is read by id, and an unknown id is a 404")
    void read() throws Exception {
        String supplier = supplier("Reading Supplier");
        UUID v = UUID.randomUUID();
        catalogKnows(ring(v, "R"));
        String id = placed(order(supplier, line(v, 2, "40.00")));

        mvc.perform(get(PATH + "/" + id).with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.lines[0].quantity").value(2));
        mvc.perform(get(PATH + "/" + UUID.randomUUID()).with(maya())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the list is newest first, filters by supplier and status, and pages are capped")
    void list() throws Exception {
        String one = supplier("List Supplier One");
        String two = supplier("List Supplier Two");
        UUID v = UUID.randomUUID();
        catalogKnows(ring(v, "R"));
        String first = placed(order(one, line(v, 1, "1.00")));
        String second = placed(order(two, line(v, 1, "1.00")));
        String third = placed(order(one, line(v, 1, "1.00")));
        mvc.perform(post(PATH + "/" + first + "/cancel").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());

        MvcResult all = mvc.perform(get(PATH).with(maya())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3)).andReturn();
        List<String> ids = JsonPath.read(all.getResponse().getContentAsString(), "$.content[*].id");
        assertThat(ids).containsExactly(third, second, first);

        mvc.perform(get(PATH).param("supplierId", one).with(maya()))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(PATH).param("status", "OPEN").with(maya()))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(PATH).param("status", "CANCELLED").param("supplierId", one).with(maya()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(PATH).param("status", "PENDING").with(maya())).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("size", "1000").with(maya())).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("sort", "id,desc").with(maya())).andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------- changing

    @Test
    @DisplayName("an open order's note, date and lines can be changed, with the version it was read at")
    void update() throws Exception {
        String supplier = supplier("Updating Supplier");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        catalogKnows(ring(a, "A"), ring(b, "B"));
        String id = placed(order(supplier, line(a, 1, "10.00")));

        mvc.perform(patch(PATH + "/" + id).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"note\":\"paid by bank transfer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("paid by bank transfer"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.version").value(1));
        verify(catalog, times(1)).lookup(any()); // the placement; a note change asks no one

        mvc.perform(patch(PATH + "/" + id).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"lines\":[" + line(b, 3, "30.00") + "," + line(a, 4, "40.00") + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].sku").value("B"))
                .andExpect(jsonPath("$.lines[1].position").value(2))
                .andExpect(jsonPath("$.totalPaid").value(70.00))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    @DisplayName("a change made from a stale copy is a 409 and changes nothing; the version is required")
    void staleChange() throws Exception {
        String supplier = supplier("Versioned Supplier");
        UUID v = UUID.randomUUID();
        catalogKnows(ring(v, "V"));
        String id = placed(order(supplier, line(v, 1, "10.00")));
        mvc.perform(patch(PATH + "/" + id).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"note\":\"first\"}")).andExpect(status().isOk());

        mvc.perform(patch(PATH + "/" + id).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"note\":\"second\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch(PATH + "/" + id).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"no version\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("version")));
        mvc.perform(get(PATH + "/" + id).with(maya())).andExpect(jsonPath("$.note").value("first"));
    }

    @Test
    @DisplayName("replacing the lines needs an active supplier; changing only the note does not")
    void inactiveSupplierMayNotAddToAnOrder() throws Exception {
        String supplier = supplier("Stops Midway");
        UUID v = UUID.randomUUID();
        catalogKnows(ring(v, "V"));
        String id = placed(order(supplier, line(v, 1, "10.00")));
        mvc.perform(patch(SUPPLIERS + "/" + supplier).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isOk());

        mvc.perform(patch(PATH + "/" + id).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"lines\":[" + line(v, 9, "90.00") + "]}"))
                .andExpect(status().isConflict());
        mvc.perform(patch(PATH + "/" + id).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"note\":\"still ours\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an open order can be cancelled, once, with its version")
    void cancel() throws Exception {
        String supplier = supplier("Cancelling Supplier");
        UUID v = UUID.randomUUID();
        catalogKnows(ring(v, "V"));
        String id = placed(order(supplier, line(v, 1, "10.00")));

        mvc.perform(post(PATH + "/" + id + "/cancel").with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":5}"))
                .andExpect(status().isConflict());
        mvc.perform(post(PATH + "/" + id + "/cancel").with(maya()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH + "/" + id + "/cancel").with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post(PATH + "/" + id + "/cancel").with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(patch(PATH + "/" + id).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"note\":\"too late\"}"))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------- security

    @Test
    @DisplayName("only PROCUREMENT_ADMIN may touch purchase orders: no token is 401, other roles are 403")
    void security() throws Exception {
        String body = order(UUID.randomUUID().toString(), line(UUID.randomUUID(), 1, "1.00"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String role : List.of("ROLE_USER", "ROLE_CATALOG_ADMIN", "ROLE_FINANCE_ADMIN", "ROLE_COST_VIEWER")) {
            RequestPostProcessor other = jwt().authorities(new SimpleGrantedAuthority(role));
            mvc.perform(post(PATH).with(other).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
            mvc.perform(get(PATH).with(other)).andExpect(status().isForbidden());
        }
        verify(catalog, never()).lookup(any()); // a refused caller costs catalog nothing
    }
}
