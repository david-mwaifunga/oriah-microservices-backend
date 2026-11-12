package com.oriah.procurement.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Building a consignment over HTTP, against a real PostgreSQL and the real security rules.
 *
 * <p>A consignment is one shipment: whole purchase orders brought in under one courier bill,
 * or the day-one stock typed in. This pins the draft half of it: creating, reading, changing
 * the header, the lines' weights, the cost lines, attaching and detaching orders, deleting a
 * draft, and the allocation preview. Catalog is stood in for by a mock (its lookup, and the
 * call that carries the token, are tested elsewhere).
 *
 * <p>The worked example throughout is MVP_PLAN Part 4: 7 rings at K10,500.00 and 200 boxes at
 * K10,000.00 under one courier charge of K6,000.00.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ConsignmentHttpTest {

    static final String PATH = "/api/v1/admin/procurement/consignments";
    static final String ORDERS = "/api/v1/admin/procurement/purchase-orders";
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

    private final Map<UUID, Entry> known = new HashMap<>();

    @BeforeEach
    void clean() {
        ProcurementTestData.wipe(jdbc);
        Mockito.reset(catalog);
        known.clear();
        when(catalog.lookup(any())).thenAnswer(call -> {
            CatalogClient.VariantLookupBody body = call.getArgument(0);
            return new VariantLookupResult(body.variantIds().stream()
                    .map(id -> known.getOrDefault(id, new Entry(id, false, null, null, null, null, null, null, null, null)))
                    .toList());
        });
    }

    static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    // ----------------------------------------------------------- fixtures

    private UUID sellable(String sku) {
        UUID id = UUID.randomUUID();
        known.put(id, new Entry(id, true, UUID.randomUUID(), "Aurelia Ring", "Aurelia Ring - " + sku, sku,
                "SELLABLE", "DRAFT", true, false));
        return id;
    }

    private UUID packaging(String sku) {
        UUID id = UUID.randomUUID();
        known.put(id, new Entry(id, true, UUID.randomUUID(), "Gift box", "Gift box " + sku, sku,
                "PACKAGING", "DRAFT", true, false));
        return id;
    }

    private String supplier(String name) throws Exception {
        MvcResult created = mvc.perform(post(SUPPLIERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"country\":\"AE\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    /** Places a one-line order with a new supplier and returns its id. */
    private String placeOrder(String supplierName, UUID variant, int quantity, String total) throws Exception {
        String supplier = supplier(supplierName);
        MvcResult result = mvc.perform(post(ORDERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplier + "\",\"lines\":[{\"variantId\":\"" + variant
                                + "\",\"quantity\":" + quantity + ",\"totalPaid\":" + total + "}]}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private long orderVersion(String order) throws Exception {
        MvcResult result = mvc.perform(get(ORDERS + "/" + order).with(maya())).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.version")).longValue();
    }

    private String orderRef(String order) throws Exception {
        return "{\"id\":\"" + order + "\",\"version\":" + orderVersion(order) + "}";
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String createdId(String body) throws Exception {
        MvcResult result = create(body).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /** Rings from one supplier, boxes from another: the shape every worked example uses. */
    private record RingsAndBoxes(String consignment, String ringOrder, String boxOrder, UUID ring, UUID box) {
    }

    private RingsAndBoxes ringsAndBoxes() throws Exception {
        UUID ring = sellable("RING-" + UUID.randomUUID().toString().substring(0, 8));
        UUID box = packaging("BOX-" + UUID.randomUUID().toString().substring(0, 8));
        String ringOrder = placeOrder("Dubai Gold " + UUID.randomUUID().toString().substring(0, 6), ring, 7, "10500.00");
        String boxOrder = placeOrder("Guangzhou Packaging " + UUID.randomUUID().toString().substring(0, 6), box, 200, "10000.00");
        String id = createdId("{\"purchaseOrders\":[" + orderRef(ringOrder) + "," + orderRef(boxOrder) + "]}");
        return new RingsAndBoxes(id, ringOrder, boxOrder, ring, box);
    }

    private long version(String consignment) throws Exception {
        MvcResult result = mvc.perform(get(PATH + "/" + consignment).with(maya())).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.version")).longValue();
    }

    private String json(String consignment) throws Exception {
        return mvc.perform(get(PATH + "/" + consignment).with(maya())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions putCosts(String consignment, long version, String... costLines) throws Exception {
        return mvc.perform(put(PATH + "/" + consignment + "/cost-lines").with(maya())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + ",\"costLines\":[" + String.join(",", costLines) + "]}"));
    }

    private static String courier(String amount) {
        return "{\"costType\":\"COURIER_INBOUND\",\"amount\":" + amount + "}";
    }

    // ------------------------------------------------------------- creating

    @Test
    @DisplayName("a consignment is made from whole purchase orders: 201 with Location, its reference, the orders and a line copied from each")
    void createFromOrders() throws Exception {
        UUID ring = sellable("RING-M");
        UUID box = packaging("BOX-1");
        String ringOrder = placeOrder("Dubai Gold", ring, 7, "10500.00");
        String boxOrder = placeOrder("Guangzhou Packaging", box, 200, "10000.00");

        MvcResult result = create("{\"note\":\"Fycargo, first box\",\"purchaseOrders\":[" + orderRef(ringOrder) + ","
                + orderRef(boxOrder) + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(matchesPattern("C-\\d{6}")))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.opening").value(false))
                .andExpect(jsonPath("$.estimated").value(false))
                .andExpect(jsonPath("$.note").value("Fycargo, first box"))
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdBy").value("kc-maya"))
                .andExpect(jsonPath("$.purchaseOrders", hasSize(2)))
                .andExpect(jsonPath("$.purchaseOrders[0].reference").value(matchesPattern("PO-\\d{6}")))
                .andExpect(jsonPath("$.purchaseOrders[0].supplier.name").value("Dubai Gold"))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].position").value(1))
                .andExpect(jsonPath("$.lines[0].sku").value("RING-M"))
                .andExpect(jsonPath("$.lines[0].kind").value("SELLABLE"))
                .andExpect(jsonPath("$.lines[0].quantity").value(7))
                .andExpect(jsonPath("$.lines[0].goodsTotal").value(10500.00))
                .andExpect(jsonPath("$.lines[0].supplier.name").value("Dubai Gold"))
                .andExpect(jsonPath("$.lines[0].purchaseOrder.id").value(ringOrder))
                .andExpect(jsonPath("$.lines[1].kind").value("PACKAGING"))
                .andExpect(jsonPath("$.lines[1].supplier.name").value("Guangzhou Packaging"))
                .andExpect(jsonPath("$.costLines", hasSize(0)))
                .andExpect(jsonPath("$.goodsTotal").value(20500.00))
                .andExpect(jsonPath("$.capitalizedCostTotal").value(0.0))
                .andReturn();

        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(PATH + "/" + id);
    }

    @Test
    @DisplayName("attaching freezes the orders: each says which consignment it is in, and can no longer be changed or cancelled")
    void attachingFreezesTheOrders() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();

        mvc.perform(get(ORDERS + "/" + shipment.ringOrder()).with(maya()))
                .andExpect(jsonPath("$.consignmentId").value(shipment.consignment()));
        mvc.perform(patch(ORDERS + "/" + shipment.ringOrder()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + orderVersion(shipment.ringOrder()) + ",\"note\":\"too late\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(ORDERS + "/" + shipment.ringOrder() + "/cancel").with(maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + orderVersion(shipment.ringOrder()) + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("each order is named with the version it was read at: a stale one is a 409 and nothing is attached")
    void staleOrderVersionIsRefused() throws Exception {
        UUID ring = sellable("RING-S");
        String order = placeOrder("Stale Supplier", ring, 1, "10.00");
        String stale = "{\"id\":\"" + order + "\",\"version\":99}";

        create("{\"purchaseOrders\":[" + stale + "]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/concurrent-update"));

        mvc.perform(get(ORDERS + "/" + order).with(maya())).andExpect(jsonPath("$.consignmentId").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consignment", Long.class)).isZero();
    }

    @Test
    @DisplayName("an order that does not exist is a 404, a cancelled one a 409, and one already in a consignment a 409 that leaves the first intact")
    void ordersThatCannotJoin() throws Exception {
        UUID ring = sellable("RING-J");
        String cancelled = placeOrder("Cancelled Supplier", ring, 1, "10.00");
        mvc.perform(post(ORDERS + "/" + cancelled + "/cancel").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());
        String taken = placeOrder("Taken Supplier", ring, 1, "10.00");
        String first = createdId("{\"purchaseOrders\":[" + orderRef(taken) + "]}");

        create("{\"purchaseOrders\":[{\"id\":\"" + UUID.randomUUID() + "\",\"version\":0}]}")
                .andExpect(status().isNotFound());
        create("{\"purchaseOrders\":[" + orderRef(cancelled) + "]}")
                .andExpect(status().isConflict());
        create("{\"purchaseOrders\":[" + orderRef(taken) + "]}")
                .andExpect(status().isConflict());

        mvc.perform(get(ORDERS + "/" + taken).with(maya()))
                .andExpect(jsonPath("$.consignmentId").value(first));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consignment", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("no orders, the same order twice, or more than 20 is a 400; an opening flag with orders is a 400")
    void createBodyValidation() throws Exception {
        UUID ring = sellable("RING-V");
        String order = placeOrder("Validation Supplier", ring, 1, "10.00");

        create("{}").andExpect(status().isBadRequest());
        create("{\"purchaseOrders\":[]}").andExpect(status().isBadRequest());
        create("{\"purchaseOrders\":[" + orderRef(order) + "," + orderRef(order) + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("more than once")));
        List<String> twentyOne = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            twentyOne.add("{\"id\":\"" + UUID.randomUUID() + "\",\"version\":0}");
        }
        create("{\"purchaseOrders\":[" + String.join(",", twentyOne) + "]}").andExpect(status().isBadRequest());
        create("{\"opening\":true,\"purchaseOrders\":[" + orderRef(order) + "]}").andExpect(status().isBadRequest());
        create("{\"purchaseOrders\":[{\"id\":\"" + order + "\"}]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("purchaseOrders[0].version")));
    }

    @Test
    @DisplayName("two requests that name the same order at once: exactly one consignment is made, the other is a 409")
    void theSameOrderTwiceAtOnce() throws Exception {
        UUID ring = sellable("RING-R");
        String order = placeOrder("Racing Supplier", ring, 1, "10.00");
        String body = "{\"purchaseOrders\":[" + orderRef(order) + "]}";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return create(body).andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM consignment", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM consignment_line", Long.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --------------------------------------------------- the opening consignment

    @Test
    @DisplayName("the opening consignment is typed in: no supplier, no order, an estimate, the names taken from the catalogue")
    void createOpening() throws Exception {
        UUID ring = sellable("RING-O");
        UUID box = packaging("BOX-O");

        create("{\"opening\":true,\"note\":\"Day-one stock, from the old book\",\"lines\":["
                + "{\"variantId\":\"" + ring + "\",\"quantity\":7,\"totalCost\":10000.00},"
                + "{\"variantId\":\"" + box + "\",\"quantity\":200,\"totalCost\":9000.00,\"originCountry\":\"cn\"}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.opening").value(true))
                .andExpect(jsonPath("$.estimated").value(true))
                .andExpect(jsonPath("$.purchaseOrders", hasSize(0)))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].supplier").doesNotExist())
                .andExpect(jsonPath("$.lines[0].purchaseOrder").doesNotExist())
                .andExpect(jsonPath("$.lines[0].sku").value("RING-O"))
                .andExpect(jsonPath("$.lines[0].goodsTotal").value(10000.00))
                .andExpect(jsonPath("$.lines[1].originCountry").value("CN"))
                .andExpect(jsonPath("$.goodsTotal").value(19000.00));
    }

    @Test
    @DisplayName("a K0 cost, an unknown variant, a fraction of a ngwee or no lines is refused, naming the field")
    void openingValidation() throws Exception {
        UUID ring = sellable("RING-X");

        create("{\"opening\":true,\"lines\":[{\"variantId\":\"" + ring + "\",\"quantity\":1,\"totalCost\":0.00}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("lines[0].totalCost")));
        create("{\"opening\":true,\"lines\":[{\"variantId\":\"" + ring + "\",\"quantity\":1,\"totalCost\":10.005}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("lines[0].totalCost")));
        create("{\"opening\":true,\"lines\":[{\"variantId\":\"" + UUID.randomUUID() + "\",\"quantity\":1,\"totalCost\":5.00}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("lines[0].variantId")));
        create("{\"opening\":true,\"lines\":[]}").andExpect(status().isBadRequest());
        create("{\"opening\":true}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consignment", Long.class)).isZero();
    }

    @Test
    @DisplayName("when the catalogue cannot be asked, nothing is made: 503 with Retry-After")
    void catalogDown() throws Exception {
        UUID ring = sellable("RING-D");
        // doThrow, not when(...): the default stub answers with its argument, which a when() call would pass as null.
        Mockito.doThrow(FeignException.errorStatus("lookup", feign.Response.builder().status(503)
                .reason("down").request(Request.create(Request.HttpMethod.POST, "http://catalog", Map.of(), null,
                        new RequestTemplate())).build())).when(catalog).lookup(any());

        create("{\"opening\":true,\"lines\":[{\"variantId\":\"" + ring + "\",\"quantity\":1,\"totalCost\":5.00}]}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consignment", Long.class)).isZero();
    }

    @Test
    @DisplayName("the opening lines are replaced as a set, and a normal consignment's lines cannot be typed")
    void replaceOpeningLines() throws Exception {
        UUID ring = sellable("RING-P");
        UUID box = packaging("BOX-P");
        String opening = createdId("{\"opening\":true,\"lines\":[{\"variantId\":\"" + ring
                + "\",\"quantity\":1,\"totalCost\":5.00}]}");

        mvc.perform(put(PATH + "/" + opening + "/opening-lines").with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"lines\":[{\"variantId\":\"" + box + "\",\"quantity\":9,\"totalCost\":18.00}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].sku").value("BOX-P"));

        RingsAndBoxes normal = ringsAndBoxes();
        mvc.perform(put(PATH + "/" + normal.consignment() + "/opening-lines").with(maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"lines\":[{\"variantId\":\"" + box + "\",\"quantity\":9,\"totalCost\":18.00}]}"))
                .andExpect(status().isConflict());
    }

    // ----------------------------------------------------------- reading

    @Test
    @DisplayName("a consignment is read by id; an unknown id is a 404 and a malformed one a 400")
    void readOne() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();

        mvc.perform(get(PATH + "/" + shipment.consignment()).with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(shipment.consignment()))
                .andExpect(jsonPath("$.lines", hasSize(2)));
        mvc.perform(get(PATH + "/" + UUID.randomUUID()).with(maya())).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/not-a-uuid").with(maya())).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the list is newest first, can be narrowed to drafts or to the opening consignment, and summarises each")
    void list() throws Exception {
        RingsAndBoxes first = ringsAndBoxes();
        UUID ring = sellable("RING-L");
        String opening = createdId("{\"opening\":true,\"lines\":[{\"variantId\":\"" + ring
                + "\",\"quantity\":1,\"totalCost\":5.00}]}");

        mvc.perform(get(PATH).with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(opening))
                .andExpect(jsonPath("$.content[0].opening").value(true))
                .andExpect(jsonPath("$.content[0].lineCount").value(1))
                .andExpect(jsonPath("$.content[1].id").value(first.consignment()))
                .andExpect(jsonPath("$.content[1].orderCount").value(2))
                .andExpect(jsonPath("$.content[1].lineCount").value(2))
                .andExpect(jsonPath("$.content[1].goodsTotal").value(20500.00))
                .andExpect(jsonPath("$.content[1].reference").value(matchesPattern("C-\\d{6}")));
        mvc.perform(get(PATH).param("opening", "true").with(maya()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(opening));
        mvc.perform(get(PATH).param("status", "RECEIVED").with(maya()))
                .andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get(PATH).param("status", "DRAFT").param("opening", "false").with(maya()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(first.consignment()));
        mvc.perform(get(PATH).param("size", "0").with(maya())).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("sort", "id").with(maya())).andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------- the header

    @Test
    @DisplayName("the header records the customs facts, replaces them as a whole, and moves the version")
    void changeHeader() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();

        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"note\":\"Cleared at Kasumbalesa\",\"chargesBasis\":\"FREIGHT_ONLY\","
                                + "\"declarant\":\"Fycargo Clearing\",\"clearanceForm\":\"CE6\","
                                + "\"zraAssessmentRef\":\"ASM-123\",\"licensedAgentUsed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.note").value("Cleared at Kasumbalesa"))
                .andExpect(jsonPath("$.chargesBasis").value("FREIGHT_ONLY"))
                .andExpect(jsonPath("$.declarant").value("Fycargo Clearing"))
                .andExpect(jsonPath("$.clearanceForm").value("CE6"))
                .andExpect(jsonPath("$.zraAssessmentRef").value("ASM-123"))
                .andExpect(jsonPath("$.licensedAgentUsed").value(true));

        // Sent again whole but with fewer fields: the rest are cleared. It is a replacement.
        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"note\":\"Cleared at Kasumbalesa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargesBasis").doesNotExist())
                .andExpect(jsonPath("$.declarant").doesNotExist())
                .andExpect(jsonPath("$.licensedAgentUsed").doesNotExist());
    }

    @Test
    @DisplayName("a header saved untouched writes nothing, so the version stays put")
    void untouchedHeaderKeepsTheVersion() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();

        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("every change names the version it was made from: missing is a 400, stale a 409 that leaves the other person's change standing")
    void versions() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"note\":\"first\"}")).andExpect(status().isOk());

        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"no version\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("version")));
        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"note\":\"second, from a stale copy\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/concurrent-update"));

        mvc.perform(get(PATH + "/" + shipment.consignment()).with(maya()))
                .andExpect(jsonPath("$.note").value("first"));
    }

    @Test
    @DisplayName("a declarant that is too long, or a clearance form that is not CE6 or CE20, is a 400")
    void headerValidation() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();

        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"declarant\":\"" + "x".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"clearanceForm\":\"CE7\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"chargesBasis\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the opening consignment cannot be saved as 'not an estimate'")
    void openingStaysAnEstimate() throws Exception {
        UUID ring = sellable("RING-E");
        String opening = createdId("{\"opening\":true,\"lines\":[{\"variantId\":\"" + ring
                + "\",\"quantity\":1,\"totalCost\":5.00}]}");

        mvc.perform(put(PATH + "/" + opening).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"estimated\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("estimate")));
        // Left out, it is kept as it is.
        mvc.perform(put(PATH + "/" + opening).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"note\":\"fine\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimated").value(true));
    }

    // -------------------------------------------------------- a line's details

    @Test
    @DisplayName("a line's chargeable weight, origin country and HS code are keyed per line, tidied, and clearable")
    void lineDetails() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        String line = JsonPath.read(json(shipment.consignment()), "$.lines[0].id");

        mvc.perform(put(PATH + "/" + shipment.consignment() + "/lines/" + line).with(maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"chargeableWeightGrams\":700,\"originCountry\":\"ae\",\"hsCode\":\"7113.19.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.lines[0].chargeableWeightGrams").value(700))
                .andExpect(jsonPath("$.lines[0].originCountry").value("AE"))
                .andExpect(jsonPath("$.lines[0].hsCode").value("7113.19.00"));

        mvc.perform(put(PATH + "/" + shipment.consignment() + "/lines/" + line).with(maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].chargeableWeightGrams").doesNotExist())
                .andExpect(jsonPath("$.lines[0].originCountry").doesNotExist());
    }

    @Test
    @DisplayName("a line that is not in this consignment is a 404; a negative weight, a country that is not two letters or an HS code that is not digits is a 400")
    void lineDetailsValidation() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        String line = JsonPath.read(json(shipment.consignment()), "$.lines[0].id");

        mvc.perform(put(PATH + "/" + shipment.consignment() + "/lines/" + UUID.randomUUID()).with(maya())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"chargeableWeightGrams\":1}"))
                .andExpect(status().isNotFound());
        for (String bad : List.of("{\"version\":0,\"chargeableWeightGrams\":-1}", "{\"version\":0,\"originCountry\":\"CHN\"}",
                "{\"version\":0,\"hsCode\":\"gold\"}")) {
            mvc.perform(put(PATH + "/" + shipment.consignment() + "/lines/" + line).with(maya())
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
    }

    // --------------------------------------------------------------- cost lines

    @Test
    @DisplayName("the courier's one charge is keyed as billed: capitalized, shared by units by default, and the version moves")
    void courierCharge() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();

        putCosts(shipment.consignment(), 0, courier("6000.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.costLines", hasSize(1)))
                .andExpect(jsonPath("$.costLines[0].position").value(1))
                .andExpect(jsonPath("$.costLines[0].costType").value("COURIER_INBOUND"))
                .andExpect(jsonPath("$.costLines[0].amount").value(6000.00))
                .andExpect(jsonPath("$.costLines[0].basis").value("UNITS"))
                .andExpect(jsonPath("$.costLines[0].treatment").value("CAPITALIZE"))
                .andExpect(jsonPath("$.capitalizedCostTotal").value(6000.00))
                .andExpect(jsonPath("$.periodCostTotal").value(0.0));
    }

    @Test
    @DisplayName("replacing the cost lines replaces them all, and an empty list clears them")
    void costLinesAreReplacedAsASet() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        putCosts(shipment.consignment(), 0, courier("6000.00"),
                "{\"costType\":\"AGENT_FEE\",\"amount\":100.00}").andExpect(status().isOk())
                .andExpect(jsonPath("$.costLines", hasSize(2)))
                .andExpect(jsonPath("$.costLines[1].basis").value("VALUE"));

        putCosts(shipment.consignment(), 1, courier("5000.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.costLines", hasSize(1)))
                .andExpect(jsonPath("$.costLines[0].amount").value(5000.00));
        putCosts(shipment.consignment(), 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.costLines", hasSize(0)));
    }

    @Test
    @DisplayName("a bank or transfer charge is refused with the reason, naming the field: the supplier price already is the total that left the bank")
    void bankChargesAreRefused() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();

        for (String name : List.of("BANK_CHARGE", "TRANSFER_FEE", "bank fee")) {
            putCosts(shipment.consignment(), 0, "{\"costType\":\"" + name + "\",\"amount\":25.00}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("costLines[0].costType")))
                    .andExpect(jsonPath("$.fieldErrors[*].message", hasItem(containsString("already is the total that left the bank"))));
        }
        mvc.perform(get(PATH + "/" + shipment.consignment()).with(maya()))
                .andExpect(jsonPath("$.costLines", hasSize(0)))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("an unknown cost type, a negative amount, a fraction of a ngwee and a basis the type does not allow are each a 400")
    void costLineValidation() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();

        putCosts(shipment.consignment(), 0, "{\"costType\":\"SNACKS\",\"amount\":1.00}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("costLines[0].costType")));
        putCosts(shipment.consignment(), 0, "{\"costType\":\"FREIGHT\",\"amount\":-1.00}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("costLines[0].amount")));
        putCosts(shipment.consignment(), 0, "{\"costType\":\"FREIGHT\",\"amount\":1.005}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("costLines[0].amount")));
        putCosts(shipment.consignment(), 0, "{\"costType\":\"DUTY\",\"amount\":10.00,\"basis\":\"VALUE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("DIRECT")));
        putCosts(shipment.consignment(), 0, "{\"costType\":\"INSURANCE\",\"amount\":10.00}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("basis")));
    }

    @Test
    @DisplayName("a split a person typed names every line, adds up exactly, and comes back as keyed")
    void typedShares() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        String ring = JsonPath.read(json(shipment.consignment()), "$.lines[0].id");
        String box = JsonPath.read(json(shipment.consignment()), "$.lines[1].id");

        putCosts(shipment.consignment(), 0, "{\"costType\":\"DUTY\",\"amount\":210.00,\"shares\":["
                + "{\"lineId\":\"" + ring + "\",\"amount\":210.00},{\"lineId\":\"" + box + "\",\"amount\":0.00}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costLines[0].basis").value("DIRECT"))
                .andExpect(jsonPath("$.costLines[0].shares", hasSize(2)))
                .andExpect(jsonPath("$.costLines[0].shares[?(@.lineId=='" + ring + "')].amount").value(210.00));

        putCosts(shipment.consignment(), 1, "{\"costType\":\"DUTY\",\"amount\":210.00,\"shares\":["
                + "{\"lineId\":\"" + ring + "\",\"amount\":200.00},{\"lineId\":\"" + box + "\",\"amount\":0.00}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("10.00")));
        putCosts(shipment.consignment(), 1, "{\"costType\":\"DUTY\",\"amount\":210.00,\"shares\":["
                + "{\"lineId\":\"" + ring + "\",\"amount\":210.00}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("every line")));
        putCosts(shipment.consignment(), 1, "{\"costType\":\"DUTY\",\"amount\":210.00,\"shares\":["
                + "{\"lineId\":\"" + UUID.randomUUID() + "\",\"amount\":210.00}]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a PERIOD cost is shown on the consignment and totalled apart")
    void periodCost() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();

        putCosts(shipment.consignment(), 0, courier("6000.00"),
                "{\"costType\":\"PORT_INCIDENTALS\",\"amount\":40.00,\"basis\":\"UNITS\",\"treatment\":\"PERIOD\","
                        + "\"incurredOn\":\"2026-11-10\",\"note\":\"Storage\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capitalizedCostTotal").value(6000.00))
                .andExpect(jsonPath("$.periodCostTotal").value(40.00))
                .andExpect(jsonPath("$.costLines[1].treatment").value("PERIOD"))
                .andExpect(jsonPath("$.costLines[1].incurredOn").value("2026-11-10"))
                .andExpect(jsonPath("$.costLines[1].note").value("Storage"));
    }

    @Test
    @DisplayName("DUTY_PAID says the courier's price includes duty, so a duty line above zero is refused, and the other way round")
    void dutyPaid() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        String ring = JsonPath.read(json(shipment.consignment()), "$.lines[0].id");
        String box = JsonPath.read(json(shipment.consignment()), "$.lines[1].id");
        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"chargesBasis\":\"DUTY_PAID\"}"))
                .andExpect(status().isOk());

        putCosts(shipment.consignment(), 1, "{\"costType\":\"IMPORT_VAT\",\"amount\":50.00,\"shares\":["
                + "{\"lineId\":\"" + ring + "\",\"amount\":50.00},{\"lineId\":\"" + box + "\",\"amount\":0.00}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("DUTY_PAID")));
        putCosts(shipment.consignment(), 1, courier("6000.00"), "{\"costType\":\"DUTY\",\"amount\":0.00}")
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------- attach and detach

    @Test
    @DisplayName("another order is attached with the consignment's version and the order's: its lines are appended and it is frozen")
    void attachAnotherOrder() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        UUID chain = sellable("CHAIN-1");
        String chainOrder = placeOrder("Chain Supplier", chain, 3, "300.00");

        mvc.perform(post(PATH + "/" + shipment.consignment() + "/purchase-orders").with(maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"purchaseOrderId\":\"" + chainOrder + "\",\"purchaseOrderVersion\":"
                                + orderVersion(chainOrder) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.purchaseOrders", hasSize(3)))
                .andExpect(jsonPath("$.lines", hasSize(3)))
                .andExpect(jsonPath("$.lines[2].position").value(3))
                .andExpect(jsonPath("$.lines[2].sku").value("CHAIN-1"))
                .andExpect(jsonPath("$.goodsTotal").value(20800.00));
        mvc.perform(get(ORDERS + "/" + chainOrder).with(maya()))
                .andExpect(jsonPath("$.consignmentId").value(shipment.consignment()));
    }

    @Test
    @DisplayName("attaching a stale order version, an order that is already in a consignment, or anything to the opening consignment is refused")
    void attachRefusals() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        RingsAndBoxes other = ringsAndBoxes();
        UUID chain = sellable("CHAIN-2");
        String chainOrder = placeOrder("Chain Supplier 2", chain, 3, "300.00");
        UUID ring = sellable("RING-OP");
        String opening = createdId("{\"opening\":true,\"lines\":[{\"variantId\":\"" + ring
                + "\",\"quantity\":1,\"totalCost\":5.00}]}");

        // A stale order version.
        mvc.perform(post(PATH + "/" + shipment.consignment() + "/purchase-orders").with(maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"purchaseOrderId\":\"" + chainOrder + "\",\"purchaseOrderVersion\":42}"))
                .andExpect(status().isConflict());
        // An order that is in another consignment.
        mvc.perform(post(PATH + "/" + shipment.consignment() + "/purchase-orders").with(maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"purchaseOrderId\":\"" + other.ringOrder() + "\",\"purchaseOrderVersion\":"
                                + orderVersion(other.ringOrder()) + "}"))
                .andExpect(status().isConflict());
        // The opening consignment takes no orders.
        mvc.perform(post(PATH + "/" + opening + "/purchase-orders").with(maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"purchaseOrderId\":\"" + chainOrder + "\",\"purchaseOrderVersion\":"
                                + orderVersion(chainOrder) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("opening")));
        // An order that does not exist.
        mvc.perform(post(PATH + "/" + shipment.consignment() + "/purchase-orders").with(maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"purchaseOrderId\":\"" + UUID.randomUUID() + "\",\"purchaseOrderVersion\":0}"))
                .andExpect(status().isNotFound());

        mvc.perform(get(PATH + "/" + shipment.consignment()).with(maya()))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("detaching takes an order and its lines out, renumbers the rest, and sets the order free to be changed again")
    void detach() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();

        mvc.perform(delete(PATH + "/" + shipment.consignment() + "/purchase-orders/" + shipment.ringOrder())
                        .param("version", "0").with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.purchaseOrders", hasSize(1)))
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].position").value(1))
                .andExpect(jsonPath("$.lines[0].kind").value("PACKAGING"));

        mvc.perform(get(ORDERS + "/" + shipment.ringOrder()).with(maya()))
                .andExpect(jsonPath("$.consignmentId").doesNotExist());
        mvc.perform(patch(ORDERS + "/" + shipment.ringOrder()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + orderVersion(shipment.ringOrder()) + ",\"note\":\"editable again\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an order that is not in this consignment cannot be detached from it, and the version is required")
    void detachRefusals() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        UUID chain = sellable("CHAIN-3");
        String stranger = placeOrder("Stranger Supplier", chain, 1, "10.00");

        mvc.perform(delete(PATH + "/" + shipment.consignment() + "/purchase-orders/" + stranger)
                        .param("version", "0").with(maya()))
                .andExpect(status().isConflict());
        mvc.perform(delete(PATH + "/" + shipment.consignment() + "/purchase-orders/" + shipment.ringOrder()).with(maya()))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(PATH + "/" + shipment.consignment() + "/purchase-orders/" + shipment.ringOrder())
                        .param("version", "7").with(maya()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("what was typed for a line goes when its order is detached, and the preview says the split no longer adds up")
    void detachingDropsTypedShares() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        String ring = JsonPath.read(json(shipment.consignment()), "$.lines[0].id");
        String box = JsonPath.read(json(shipment.consignment()), "$.lines[1].id");
        putCosts(shipment.consignment(), 0, "{\"costType\":\"DUTY\",\"amount\":10.00,\"shares\":["
                + "{\"lineId\":\"" + ring + "\",\"amount\":6.00},{\"lineId\":\"" + box + "\",\"amount\":4.00}]}")
                .andExpect(status().isOk());

        mvc.perform(delete(PATH + "/" + shipment.consignment() + "/purchase-orders/" + shipment.ringOrder())
                        .param("version", "1").with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costLines[0].shares", hasSize(1)))
                .andExpect(jsonPath("$.costLines[0].shares[0].lineId").value(box));
        mvc.perform(get(PATH + "/" + shipment.consignment() + "/allocation").with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.problems[0]").value(containsString("Cost line 1")));
    }

    // ----------------------------------------------------------------- deleting

    @Test
    @DisplayName("a draft is deleted with its lines and costs, and its orders are set free")
    void deleteDraft() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        putCosts(shipment.consignment(), 0, courier("6000.00")).andExpect(status().isOk());

        mvc.perform(delete(PATH + "/" + shipment.consignment()).param("version", "1").with(maya()))
                .andExpect(status().isNoContent());

        mvc.perform(get(PATH + "/" + shipment.consignment()).with(maya())).andExpect(status().isNotFound());
        mvc.perform(get(ORDERS + "/" + shipment.ringOrder()).with(maya()))
                .andExpect(jsonPath("$.consignmentId").doesNotExist());
        mvc.perform(get(ORDERS + "/" + shipment.boxOrder()).with(maya()))
                .andExpect(jsonPath("$.consignmentId").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consignment_line", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_line", Long.class)).isZero();
        // The freed orders can go into a new consignment.
        createdId("{\"purchaseOrders\":[" + orderRef(shipment.ringOrder()) + "]}");
    }

    @Test
    @DisplayName("deleting needs the version: missing is a 400, stale a 409 that deletes nothing")
    void deleteNeedsTheVersion() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();

        mvc.perform(delete(PATH + "/" + shipment.consignment()).with(maya())).andExpect(status().isBadRequest());
        mvc.perform(delete(PATH + "/" + shipment.consignment()).param("version", "5").with(maya()))
                .andExpect(status().isConflict());
        mvc.perform(get(PATH + "/" + shipment.consignment()).with(maya())).andExpect(status().isOk());
    }

    // ----------------------------------------------------------------- preview

    @Test
    @DisplayName("the allocation preview is the worked example to the ngwee: the ring lands at K1,528.99 and K1,528.98, the box at K78.99 and K78.98")
    void previewByUnits() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        putCosts(shipment.consignment(), 0, courier("6000.00")).andExpect(status().isOk());
        String ring = JsonPath.read(json(shipment.consignment()), "$.lines[0].id");

        mvc.perform(get(PATH + "/" + shipment.consignment() + "/allocation").with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.lines[0].lineId").value(ring))
                .andExpect(jsonPath("$.lines[0].goodsTotal").value(10500.00))
                .andExpect(jsonPath("$.lines[0].sharedCost").value(202.90))
                .andExpect(jsonPath("$.lines[0].landedTotal").value(10702.90))
                .andExpect(jsonPath("$.lines[0].slices", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].slices[0].quantity").value(4))
                .andExpect(jsonPath("$.lines[0].slices[0].unitCost").value(1528.99))
                .andExpect(jsonPath("$.lines[0].slices[1].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].slices[1].unitCost").value(1528.98))
                .andExpect(jsonPath("$.lines[1].slices[0].quantity").value(110))
                .andExpect(jsonPath("$.lines[1].slices[0].unitCost").value(78.99))
                .andExpect(jsonPath("$.lines[1].slices[1].quantity").value(90))
                .andExpect(jsonPath("$.lines[1].slices[1].unitCost").value(78.98))
                .andExpect(jsonPath("$.costs[0].costType").value("COURIER_INBOUND"))
                .andExpect(jsonPath("$.costs[0].perLine", hasSize(2)))
                .andExpect(jsonPath("$.goodsTotal").value(20500.00))
                .andExpect(jsonPath("$.capitalizedCostTotal").value(6000.00))
                .andExpect(jsonPath("$.landedTotal").value(26500.00));
    }

    @Test
    @DisplayName("sharing by value instead moves the ring to K1,939.03 and K1,939.02: the basis is per cost line and the choice matters")
    void previewByValue() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        putCosts(shipment.consignment(), 0,
                "{\"costType\":\"COURIER_INBOUND\",\"amount\":6000.00,\"basis\":\"VALUE\"}").andExpect(status().isOk());

        mvc.perform(get(PATH + "/" + shipment.consignment() + "/allocation").with(maya()))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.lines[0].slices[0].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].slices[0].unitCost").value(1939.03))
                .andExpect(jsonPath("$.lines[0].slices[1].unitCost").value(1939.02));
    }

    @Test
    @DisplayName("a cost shared by weight says which line has no weight, and the preview is not ready")
    void previewProblems() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        putCosts(shipment.consignment(), 0,
                "{\"costType\":\"COURIER_INBOUND\",\"amount\":6000.00,\"basis\":\"WEIGHT\"}").andExpect(status().isOk());

        mvc.perform(get(PATH + "/" + shipment.consignment() + "/allocation").with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.problems", hasSize(1)))
                .andExpect(jsonPath("$.problems[0]").value(containsString("weight")))
                .andExpect(jsonPath("$.lines", hasSize(0)))
                .andExpect(jsonPath("$.costs", hasSize(1)));
    }

    @Test
    @DisplayName("an unknown consignment has no preview: 404")
    void previewOfNothing() throws Exception {
        mvc.perform(get(PATH + "/" + UUID.randomUUID() + "/allocation").with(maya())).andExpect(status().isNotFound());
    }

    // ----------------------------------------------------------------- security

    @Test
    @DisplayName("only the procurement capability may touch consignments: no token is a 401, another capability a 403")
    void security() throws Exception {
        RingsAndBoxes shipment = ringsAndBoxes();
        RequestPostProcessor catalogAdmin = jwt().jwt(j -> j.subject("kc-other"))
                .authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"));

        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(catalogAdmin)).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/" + shipment.consignment() + "/allocation").with(catalogAdmin)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(catalogAdmin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(PATH + "/" + shipment.consignment()).param("version", "0").with(catalogAdmin))
                .andExpect(status().isForbidden());
    }
}
