package com.oriah.procurement.controller;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.jayway.jsonpath.JsonPath;
import com.oriah.events.EventJson;
import com.oriah.events.OutboxWriter;
import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;
import com.oriah.procurement.allocation.LandedCostAllocator;
import com.oriah.procurement.allocation.LandedCostAllocator.CostInput;
import com.oriah.procurement.allocation.LandedCostAllocator.LineInput;
import com.oriah.procurement.client.CatalogClient;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;
import com.oriah.procurement.model.CostBasis;
import com.oriah.procurement.model.CostTreatment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Receiving a consignment: the goods have landed, the unit costs become final, and the world is
 * told.
 *
 * <p>Receiving, in one transaction: the allocation is worked out and RECORDED (revision 1, with
 * every input kept so it can be reproduced), the consignment and each of its purchase orders
 * become RECEIVED, and one {@code consignment.received} per line is appended to the outbox, last.
 * Inventory (Phase 5) turns each event into lots, one per unit-cost slice. If any step fails,
 * nothing is left behind: no event for goods that were never received, no received consignment
 * without its event.
 *
 * <p>The worked example is MVP_PLAN Part 4: 7 rings at K10,500.00 and 200 boxes at K10,000.00
 * under one courier charge of K6,000.00.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ConsignmentReceiveHttpTest {

    static final String PATH = "/api/v1/admin/procurement/consignments";
    static final String ORDERS = "/api/v1/admin/procurement/purchase-orders";
    static final String SUPPLIERS = "/api/v1/admin/procurement/suppliers";

    private static final JsonMapper JSON = EventJson.mapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalog;

    @MockitoSpyBean
    private OutboxWriter outbox;

    /** The service's clock, so a test can make it step backwards. It tells the real time unless a test says otherwise. */
    @MockitoBean
    private Clock clock;

    private final Map<UUID, Entry> known = new HashMap<>();

    @BeforeEach
    void clean() {
        ProcurementTestData.wipe(jdbc);
        Mockito.reset(catalog, clock);
        when(clock.instant()).thenAnswer(call -> Instant.now());
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

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

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

    private String placeOrder(String supplierName, UUID variant, int quantity, String total) throws Exception {
        MvcResult supplier = mvc.perform(post(SUPPLIERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + supplierName + "\",\"country\":\"AE\"}"))
                .andExpect(status().isCreated()).andReturn();
        String supplierId = JsonPath.read(supplier.getResponse().getContentAsString(), "$.id");
        MvcResult result = mvc.perform(post(ORDERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + variant
                                + "\",\"quantity\":" + quantity + ",\"totalPaid\":" + total + "}]}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String orderRef(String order) throws Exception {
        MvcResult result = mvc.perform(get(ORDERS + "/" + order).with(maya())).andExpect(status().isOk()).andReturn();
        long version = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.version")).longValue();
        return "{\"id\":\"" + order + "\",\"version\":" + version + "}";
    }

    private String createdId(String body) throws Exception {
        MvcResult result = mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private record Shipment(String consignment, String ringOrder, String boxOrder, UUID ring, UUID box,
                            String ringSupplierName) {
    }

    /** 7 rings (K10,500.00) from one supplier and 200 boxes (K10,000.00) from another, courier K6,000.00 by units. */
    private Shipment shipment(String... extraCosts) throws Exception {
        UUID ring = sellable("RING-" + unique());
        UUID box = packaging("BOX-" + unique());
        String ringSupplier = "Dubai Gold " + unique();
        String ringOrder = placeOrder(ringSupplier, ring, 7, "10500.00");
        String boxOrder = placeOrder("Guangzhou Packaging " + unique(), box, 200, "10000.00");
        String id = createdId("{\"purchaseOrders\":[" + orderRef(ringOrder) + "," + orderRef(boxOrder) + "]}");
        List<String> costs = new ArrayList<>();
        costs.add("{\"costType\":\"COURIER_INBOUND\",\"amount\":6000.00}");
        costs.addAll(List.of(extraCosts));
        mvc.perform(put(PATH + "/" + id + "/cost-lines").with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"costLines\":[" + String.join(",", costs) + "]}"))
                .andExpect(status().isOk());
        return new Shipment(id, ringOrder, boxOrder, ring, box, ringSupplier);
    }

    private String json(String consignment) throws Exception {
        return mvc.perform(get(PATH + "/" + consignment).with(maya())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long version(String consignment) throws Exception {
        return ((Number) JsonPath.read(json(consignment), "$.version")).longValue();
    }

    private ResultActions receive(String consignment, long version) throws Exception {
        return mvc.perform(post(PATH + "/" + consignment + "/receive").with(maya())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"));
    }

    private ResultActions receive(String consignment) throws Exception {
        return receive(consignment, version(consignment));
    }

    private List<String> lineIds(String consignment) throws Exception {
        return JsonPath.read(json(consignment), "$.lines[*].id");
    }

    private record Event(long outboxId, String aggregateType, String aggregateId, long seq, String eventType,
                         int schemaVersion, String topic, OffsetDateTime occurredAt, String actor, String payload) {
    }

    /** The outbox rows about these lines, oldest first. The event log itself is never wiped between tests. */
    private List<Event> events(List<String> lineIds) {
        if (lineIds.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", lineIds.stream().map(id -> "'" + id + "'").toList());
        return jdbc.query("SELECT outbox_id, aggregate_type, aggregate_id, seq, event_type, schema_version, topic, "
                        + "occurred_at, actor, payload::text FROM outbox WHERE aggregate_id IN (" + in + ") ORDER BY outbox_id",
                (rs, n) -> new Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                        rs.getInt(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class), rs.getString(9),
                        rs.getString(10)));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    // -------------------------------------------------------------- receiving

    @Test
    @DisplayName("receiving makes the consignment RECEIVED with the time the goods landed, and every order in it RECEIVED too")
    void receivingFinishesEverything() throws Exception {
        Shipment shipment = shipment();
        Instant before = Instant.now();

        MvcResult result = receive(shipment.consignment())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedAt").isNotEmpty())
                .andExpect(jsonPath("$.purchaseOrders[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.purchaseOrders[1].status").value("RECEIVED"))
                .andExpect(jsonPath("$.version").value(2))
                .andReturn();

        Instant receivedAt = Instant.parse(JsonPath.read(result.getResponse().getContentAsString(), "$.receivedAt"));
        assertThat(receivedAt).as("the receipt time is the server's, taken now").isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        mvc.perform(get(ORDERS + "/" + shipment.ringOrder()).with(maya())).andExpect(jsonPath("$.status").value("RECEIVED"));
        mvc.perform(get(ORDERS + "/" + shipment.boxOrder()).with(maya())).andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    @DisplayName("the allocation is recorded as revision 1 and reads back exactly as the preview showed it: the ring at K1,528.99 and K1,528.98, the box at K78.99 and K78.98")
    void theAllocationIsRecorded() throws Exception {
        Shipment shipment = shipment();
        String previewed = mvc.perform(get(PATH + "/" + shipment.consignment() + "/allocation").with(maya()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        receive(shipment.consignment()).andExpect(status().isOk());

        MvcResult stored = mvc.perform(get(PATH + "/" + shipment.consignment() + "/allocation").with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.receivedAt").isNotEmpty())
                .andExpect(jsonPath("$.lines[0].slices[0].quantity").value(4))
                .andExpect(jsonPath("$.lines[0].slices[0].unitCost").value(1528.99))
                .andExpect(jsonPath("$.lines[0].slices[1].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].slices[1].unitCost").value(1528.98))
                .andExpect(jsonPath("$.lines[1].slices[0].quantity").value(110))
                .andExpect(jsonPath("$.lines[1].slices[0].unitCost").value(78.99))
                .andExpect(jsonPath("$.lines[1].slices[1].quantity").value(90))
                .andExpect(jsonPath("$.lines[1].slices[1].unitCost").value(78.98))
                .andExpect(jsonPath("$.landedTotal").value(26500.00))
                .andReturn();

        // What was stored is what was previewed: same lines, same costs, same totals.
        String storedBody = stored.getResponse().getContentAsString();
        assertThat(JSON.readTree(storedBody).get("lines")).isEqualTo(JSON.readTree(previewed).get("lines"));
        assertThat(JSON.readTree(storedBody).get("costs")).isEqualTo(JSON.readTree(previewed).get("costs"));
        assertThat(JSON.readTree(storedBody).get("landedTotal")).isEqualTo(JSON.readTree(previewed).get("landedTotal"));

        assertThat(count("allocation_revision")).isEqualTo(1);
        assertThat(count("allocation_line")).isEqualTo(2);
        assertThat(count("allocation_slice")).isEqualTo(4);
        assertThat(count("allocation_cost_share")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT total_landed FROM allocation_revision", BigDecimal.class))
                .isEqualByComparingTo("26500.00");
        assertThat(jdbc.queryForObject("SELECT created_by FROM allocation_revision", String.class)).isEqualTo("kc-maya");
    }

    @Test
    @DisplayName("the recorded inputs are enough to work the allocation out again, and it comes out the same to the ngwee")
    void theResultCanBeReproducedFromTheRecord() throws Exception {
        Shipment shipment = shipment("{\"costType\":\"DUTY\",\"amount\":0.00}");
        receive(shipment.consignment()).andExpect(status().isOk());

        assertTheRecordReproducesTheStoredSlices();
    }

    @Test
    @DisplayName("a typed split is received too: kept with who typed it, shown as stored, and reproducible from the record")
    void aTypedSplitIsReceivedAndRecorded() throws Exception {
        Shipment shipment = shipment();
        String ring = JsonPath.read(json(shipment.consignment()), "$.lines[0].id");
        String box = JsonPath.read(json(shipment.consignment()), "$.lines[1].id");
        mvc.perform(put(PATH + "/" + shipment.consignment() + "/cost-lines").with(maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"costLines\":[{\"costType\":\"COURIER_INBOUND\",\"amount\":6000.00},"
                                + "{\"costType\":\"DUTY\",\"amount\":210.00,\"shares\":["
                                + "{\"lineId\":\"" + ring + "\",\"amount\":200.00},{\"lineId\":\"" + box + "\",\"amount\":10.00}]}]}"))
                .andExpect(status().isOk());

        receive(shipment.consignment()).andExpect(status().isOk());

        // Ring: 10,500.00 + courier 202.90 + duty 200.00 = 10,902.90 over 7 = 5 x 1,557.56 + 2 x 1,557.55.
        // Box: 10,000.00 + courier 5,797.10 + duty 10.00 = 15,807.10 over 200 = 110 x 79.04 + 90 x 79.03.
        mvc.perform(get(PATH + "/" + shipment.consignment() + "/allocation").with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.costs[0].typed").value(false))
                .andExpect(jsonPath("$.costs[0].typedBy").doesNotExist())
                .andExpect(jsonPath("$.costs[1].typed").value(true))
                .andExpect(jsonPath("$.costs[1].typedBy").value("kc-maya"))
                .andExpect(jsonPath("$.costs[1].typedAt").isNotEmpty())
                .andExpect(jsonPath("$.lines[0].sharedCost").value(402.90))
                .andExpect(jsonPath("$.lines[0].slices[0].quantity").value(5))
                .andExpect(jsonPath("$.lines[0].slices[0].unitCost").value(1557.56))
                .andExpect(jsonPath("$.lines[0].slices[1].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].slices[1].unitCost").value(1557.55))
                .andExpect(jsonPath("$.lines[1].slices[0].quantity").value(110))
                .andExpect(jsonPath("$.lines[1].slices[0].unitCost").value(79.04))
                .andExpect(jsonPath("$.lines[1].slices[1].quantity").value(90))
                .andExpect(jsonPath("$.lines[1].slices[1].unitCost").value(79.03));

        // The record itself says who typed it, so a landed cost traces to the algorithm or to a person.
        String inputs = jdbc.queryForObject("SELECT inputs::text FROM allocation_revision", String.class);
        JsonNode typedCost = JSON.readTree(inputs).get("costs").get(1);
        assertThat(typedCost.get("typedBy").asText()).isEqualTo("kc-maya");
        assertThat(typedCost.get("typedAt").isNull()).isFalse();
        assertThat(JSON.readTree(inputs).get("costs").get(0).get("typedBy").isNull()).isTrue();

        assertTheRecordReproducesTheStoredSlices();
    }

    /** Works the allocation out again from nothing but the recorded inputs, and compares it with what was stored. */
    private void assertTheRecordReproducesTheStoredSlices() throws Exception {
        String inputs = jdbc.queryForObject("SELECT inputs::text FROM allocation_revision", String.class);
        JsonNode snapshot = JSON.readTree(inputs);
        List<LineInput> lines = new ArrayList<>();
        for (JsonNode line : snapshot.get("lines")) {
            lines.add(new LineInput(line.get("quantity").asInt(), line.get("goodsNgwee").asLong(),
                    line.get("weightGrams").isNull() ? null : line.get("weightGrams").asLong()));
        }
        List<CostInput> costs = new ArrayList<>();
        for (JsonNode cost : snapshot.get("costs")) {
            long[] typed = null;
            if (!cost.get("typedSharesNgwee").isNull()) {
                typed = new long[cost.get("typedSharesNgwee").size()];
                for (int i = 0; i < typed.length; i++) {
                    typed[i] = cost.get("typedSharesNgwee").get(i).asLong();
                }
            }
            costs.add(new CostInput(cost.get("amountNgwee").asLong(), CostBasis.valueOf(cost.get("basis").asText()),
                    CostTreatment.valueOf(cost.get("treatment").asText()), typed));
        }

        LandedCostAllocator.Result again = LandedCostAllocator.allocate(lines, costs);

        List<Map<String, Object>> stored = jdbc.queryForList("""
                SELECT cl.position AS line, s.position AS slice, s.quantity, (s.unit_cost * 100)::bigint AS ngwee
                FROM allocation_slice s
                JOIN allocation_line a ON a.id = s.allocation_line_id
                JOIN consignment_line cl ON cl.id = a.consignment_line_id
                ORDER BY cl.position, s.position""");
        List<Map<String, Object>> recomputed = new ArrayList<>();
        for (int i = 0; i < again.lines().size(); i++) {
            for (int s = 0; s < again.lines().get(i).slices().size(); s++) {
                LandedCostAllocator.Slice slice = again.lines().get(i).slices().get(s);
                recomputed.add(Map.of("line", i + 1, "slice", s + 1, "quantity", slice.quantity(), "ngwee", slice.unitNgwee()));
            }
        }
        assertThat(stored).hasSameSizeAs(recomputed);
        for (int i = 0; i < stored.size(); i++) {
            assertThat(((Number) stored.get(i).get("line")).intValue()).isEqualTo(recomputed.get(i).get("line"));
            assertThat(((Number) stored.get(i).get("quantity")).intValue()).isEqualTo(recomputed.get(i).get("quantity"));
            assertThat(((Number) stored.get(i).get("ngwee")).longValue()).isEqualTo(recomputed.get(i).get("ngwee"));
        }
    }

    // ----------------------------------------------------------------- events

    @Test
    @DisplayName("one consignment.received per line is appended to the outbox, as a full snapshot with the unit-cost slices, dearer first")
    void oneEventPerLine() throws Exception {
        Shipment shipment = shipment();
        List<String> lines = lineIds(shipment.consignment());
        String reference = JsonPath.read(json(shipment.consignment()), "$.reference");
        String ringSupplierId = JsonPath.read(json(shipment.consignment()), "$.lines[0].supplier.id");

        MvcResult result = receive(shipment.consignment()).andExpect(status().isOk()).andReturn();
        String receivedAt = JsonPath.read(result.getResponse().getContentAsString(), "$.receivedAt");

        List<Event> events = events(lines);
        assertThat(events).hasSize(2);
        Event ring = events.get(0);
        assertThat(ring.aggregateType()).isEqualTo("consignment_line");
        assertThat(ring.aggregateId()).isEqualTo(lines.get(0));
        assertThat(ring.seq()).as("the first event of this line's own history").isEqualTo(1);
        assertThat(ring.eventType()).isEqualTo("consignment.received");
        assertThat(ring.schemaVersion()).isEqualTo(1);
        assertThat(ring.topic()).isEqualTo("oriah.procurement");
        assertThat(ring.actor()).isEqualTo("user:kc-maya");
        assertThat(ring.occurredAt().toInstant()).as("when it happened is when the goods landed")
                .isEqualTo(Instant.parse(receivedAt));
        assertThat(events.get(1).aggregateId()).isEqualTo(lines.get(1));

        String p = ring.payload();
        // Where the line sits in its consignment (1-based, the order the lines were typed): inventory breaks a tie
        // between two lots of one receipt with it, so FIFO inside a receipt follows the consignment's line order.
        assertThat((Integer) JsonPath.read(p, "$.linePosition")).as("the ring is the first line").isEqualTo(1);
        assertThat((Integer) JsonPath.read(events.get(1).payload(), "$.linePosition")).as("the box is the second").isEqualTo(2);
        assertThat((String) JsonPath.read(p, "$.consignmentId")).isEqualTo(shipment.consignment());
        assertThat((String) JsonPath.read(p, "$.consignmentReference")).isEqualTo(reference);
        assertThat((String) JsonPath.read(p, "$.consignmentLineId")).isEqualTo(lines.get(0));
        assertThat((String) JsonPath.read(p, "$.variantId")).isEqualTo(shipment.ring().toString());
        assertThat((String) JsonPath.read(p, "$.sku")).startsWith("RING-");
        assertThat((String) JsonPath.read(p, "$.productTitle")).isEqualTo("Aurelia Ring");
        assertThat((String) JsonPath.read(p, "$.variantTitle")).startsWith("Aurelia Ring - RING-");
        assertThat((String) JsonPath.read(p, "$.kind")).isEqualTo("SELLABLE");
        assertThat((String) JsonPath.read(p, "$.supplierId")).isEqualTo(ringSupplierId);
        assertThat((String) JsonPath.read(p, "$.supplierName")).isEqualTo(shipment.ringSupplierName());
        assertThat(Instant.parse(JsonPath.read(p, "$.receivedAt"))).isEqualTo(Instant.parse(receivedAt));
        assertThat((Boolean) JsonPath.read(p, "$.isEstimated")).isFalse();
        assertThat((String) JsonPath.read(p, "$.currency")).isEqualTo("ZMW");
        assertThat((Integer) JsonPath.read(p, "$.quantity")).isEqualTo(7);
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(p, "$.goodsTotal")))).isEqualByComparingTo("10500.00");
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(p, "$.sharedCost")))).isEqualByComparingTo("202.90");
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(p, "$.landedTotal")))).isEqualByComparingTo("10702.90");
        assertThat((List<?>) JsonPath.read(p, "$.slices")).hasSize(2);
        assertThat((Integer) JsonPath.read(p, "$.slices[0].quantity")).isEqualTo(4);
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(p, "$.slices[0].unitCost")))).isEqualByComparingTo("1528.99");
        assertThat((Integer) JsonPath.read(p, "$.slices[1].quantity")).isEqualTo(3);
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(p, "$.slices[1].unitCost")))).isEqualByComparingTo("1528.98");
        assertThat((String) JsonPath.read(events.get(1).payload(), "$.kind")).isEqualTo("PACKAGING");
    }

    @Test
    @DisplayName("a shipment marked 'costs are estimates' says so on every event: isEstimated follows the flag, not the kind of consignment")
    void aShipmentMarkedEstimatedIsAnEstimateOnTheEvent() throws Exception {
        Shipment shipment = shipment();
        List<String> lines = lineIds(shipment.consignment());
        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"estimated\":true,\"note\":\"Courier bill not yet final\"}"))
                .andExpect(status().isOk());

        receive(shipment.consignment()).andExpect(status().isOk());

        List<Event> events = events(lines);
        assertThat(events).hasSize(2);
        for (Event event : events) {
            assertThat((Boolean) JsonPath.read(event.payload(), "$.isEstimated")).isTrue();
            assertThat((Object) JsonPath.read(event.payload(), "$.supplierId")).as("a shipment has suppliers").isNotNull();
        }
    }

    @Test
    @DisplayName("the wire format is pinned: field order, and an amount written plainly with its two decimals")
    void thePayloadWireFormat() throws Exception {
        Shipment shipment = shipment();
        List<String> lines = lineIds(shipment.consignment());
        receive(shipment.consignment()).andExpect(status().isOk());

        String payload = events(lines).get(0).payload();

        List<String> fields = new ArrayList<>();
        fields.addAll(JSON.readTree(payload).propertyNames());
        assertThat(fields).containsExactly("consignmentId", "consignmentReference", "consignmentLineId", "variantId",
                "sku", "productTitle", "variantTitle", "kind", "supplierId", "supplierName", "receivedAt", "isEstimated",
                "currency", "quantity", "goodsTotal", "sharedCost", "landedTotal", "slices", "linePosition");
        assertThat(payload).contains("\"landedTotal\":10702.90").contains("\"unitCost\":1528.99");
        List<String> sliceFields = new ArrayList<>();
        sliceFields.addAll(JSON.readTree(payload).get("slices").get(0).propertyNames());
        assertThat(sliceFields).containsExactly("quantity", "unitCost");
    }

    @Test
    @DisplayName("a cost that is a bill for the month is not an event: the lines are")
    void periodCostsEmitNothingExtra() throws Exception {
        Shipment shipment = shipment("{\"costType\":\"PORT_INCIDENTALS\",\"amount\":40.00,\"basis\":\"UNITS\","
                + "\"treatment\":\"PERIOD\"}");
        List<String> lines = lineIds(shipment.consignment());

        receive(shipment.consignment()).andExpect(status().isOk());

        assertThat(events(lines)).hasSize(2);
        // Not under any other aggregate either: nothing in the outbox mentions this consignment but its two lines.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE payload::text LIKE ?", Long.class,
                "%" + shipment.consignment() + "%")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT total_period FROM allocation_revision", BigDecimal.class))
                .isEqualByComparingTo("40.00");
        assertThat(jdbc.queryForObject("SELECT inputs::text FROM allocation_revision", String.class))
                .as("the month's bill is kept in the record, for the profit report to find").contains("PERIOD");
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("a consignment that cannot be costed yet is refused with what to fix, and nothing is written")
    void notReadyIsRefused() throws Exception {
        Shipment shipment = shipment("{\"costType\":\"FREIGHT\",\"amount\":10.00,\"basis\":\"WEIGHT\"}");
        List<String> lines = lineIds(shipment.consignment());

        receive(shipment.consignment())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("cannot be received yet")))
                .andExpect(jsonPath("$.detail").value(containsString("weight")));

        mvc.perform(get(PATH + "/" + shipment.consignment()).with(maya()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.receivedAt").doesNotExist());
        assertThat(events(lines)).isEmpty();
        assertThat(count("allocation_revision")).isZero();
        mvc.perform(get(ORDERS + "/" + shipment.ringOrder()).with(maya())).andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("receiving names the version it was decided from: missing is a 400, stale a 409 that receives nothing")
    void versions() throws Exception {
        Shipment shipment = shipment();
        List<String> lines = lineIds(shipment.consignment());

        mvc.perform(post(PATH + "/" + shipment.consignment() + "/receive").with(maya())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        receive(shipment.consignment(), 99).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/concurrent-update"));

        assertThat(events(lines)).isEmpty();
        mvc.perform(get(PATH + "/" + shipment.consignment()).with(maya())).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("a consignment is received once: the second time is a 409 and adds no second event")
    void receivedOnce() throws Exception {
        Shipment shipment = shipment();
        List<String> lines = lineIds(shipment.consignment());
        receive(shipment.consignment()).andExpect(status().isOk());

        receive(shipment.consignment()).andExpect(status().isConflict());

        assertThat(events(lines)).hasSize(2);
        assertThat(count("allocation_revision")).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown consignment is a 404, and the capability is required")
    void notFoundAndSecurity() throws Exception {
        Shipment shipment = shipment();

        receive(UUID.randomUUID().toString(), 0).andExpect(status().isNotFound());
        mvc.perform(post(PATH + "/" + shipment.consignment() + "/receive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}")).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/" + shipment.consignment() + "/receive")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("once received, nothing about it can be changed through the API either: every change is a 409")
    void receivedIsFrozenThroughTheApi() throws Exception {
        Shipment shipment = shipment();
        receive(shipment.consignment()).andExpect(status().isOk());
        long version = version(shipment.consignment());
        String line = lineIds(shipment.consignment()).get(0);

        mvc.perform(put(PATH + "/" + shipment.consignment()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + ",\"note\":\"late\"}")).andExpect(status().isConflict());
        mvc.perform(put(PATH + "/" + shipment.consignment() + "/cost-lines").with(maya())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + ",\"costLines\":[]}")).andExpect(status().isConflict());
        mvc.perform(put(PATH + "/" + shipment.consignment() + "/lines/" + line).with(maya())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + ",\"chargeableWeightGrams\":1}")).andExpect(status().isConflict());
        mvc.perform(delete(PATH + "/" + shipment.consignment() + "/purchase-orders/" + shipment.ringOrder())
                .param("version", "" + version).with(maya())).andExpect(status().isConflict());
        mvc.perform(delete(PATH + "/" + shipment.consignment()).param("version", "" + version).with(maya()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("history")));

        // And an order that was received with it cannot be cancelled or edited: with its REAL version, so
        // it is the status that refuses it, not a stale copy.
        MvcResult order = mvc.perform(get(ORDERS + "/" + shipment.ringOrder()).with(maya())).andReturn();
        long orderVersion = ((Number) JsonPath.read(order.getResponse().getContentAsString(), "$.version")).longValue();
        mvc.perform(post(ORDERS + "/" + shipment.ringOrder() + "/cancel").with(maya())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + orderVersion + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/conflict"))
                .andExpect(jsonPath("$.detail").value(containsString("RECEIVED")));
        mvc.perform(patch(ORDERS + "/" + shipment.ringOrder()).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + orderVersion + ",\"note\":\"too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/conflict"));
    }

    @Test
    @DisplayName("if anything fails while receiving, nothing is left behind: not received, orders still open, no allocation, no event")
    void rollbackLeavesNothing() throws Exception {
        Shipment shipment = shipment();
        List<String> lines = lineIds(shipment.consignment());
        long before = version(shipment.consignment());
        // The first line's event is written, then the outbox fails on the second.
        // The outbox bean is transactional (an append with no transaction is refused), so it is stubbed behind its proxy.
        OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
        doCallRealMethod().doThrow(new RuntimeException("the outbox failed")).when(target).append(any());

        receive(shipment.consignment(), before).andExpect(status().isInternalServerError());

        mvc.perform(get(PATH + "/" + shipment.consignment()).with(maya()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(before))
                .andExpect(jsonPath("$.receivedAt").doesNotExist());
        mvc.perform(get(ORDERS + "/" + shipment.ringOrder()).with(maya())).andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(get(ORDERS + "/" + shipment.boxOrder()).with(maya())).andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(count("allocation_revision")).isZero();
        assertThat(events(lines)).as("the first line's event rolled back with everything else").isEmpty();

        // And it can still be received afterwards.
        Mockito.reset(target);
        receive(shipment.consignment(), before).andExpect(status().isOk());
        assertThat(events(lines)).hasSize(2);
    }

    // ------------------------------------------------------------------- order

    @Test
    @DisplayName("each receipt is later than the last: the order goods landed in is the order of their receipt times")
    void receiptTimesFollowReceiptOrder() throws Exception {
        Shipment first = shipment();
        Shipment second = shipment();

        receive(first.consignment()).andExpect(status().isOk());
        receive(second.consignment()).andExpect(status().isOk());

        Instant a = Instant.parse(JsonPath.read(json(first.consignment()), "$.receivedAt"));
        Instant b = Instant.parse(JsonPath.read(json(second.consignment()), "$.receivedAt"));
        assertThat(b).isAfter(a);
        List<Event> events = events(lineIds(first.consignment()));
        events.addAll(events(lineIds(second.consignment())));
        assertThat(events).extracting(Event::outboxId).isSorted();
    }

    @Test
    @DisplayName("a receipt is never stamped earlier than the one before it, even if the server's clock steps backwards")
    void receiptTimeNeverGoesBackwards() throws Exception {
        Shipment first = shipment();
        receive(first.consignment()).andExpect(status().isOk());
        Instant firstAt = Instant.parse(JsonPath.read(json(first.consignment()), "$.receivedAt"));
        Shipment second = shipment();

        doReturn(firstAt.minusSeconds(3600)).when(clock).instant();
        receive(second.consignment()).andExpect(status().isOk());

        Instant secondAt = Instant.parse(JsonPath.read(json(second.consignment()), "$.receivedAt"));
        assertThat(secondAt).as("oldest-first selling depends on receipt order being time order")
                .isEqualTo(firstAt.plus(1, java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("two requests that receive the same consignment at once: exactly one wins, and there is one set of events")
    void receivedTwiceAtOnce() throws Exception {
        Shipment shipment = shipment();
        List<String> lines = lineIds(shipment.consignment());
        long version = version(shipment.consignment());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return receive(shipment.consignment(), version).andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(events(lines)).hasSize(2);
            assertThat(count("allocation_revision")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ----------------------------------------------------- the opening consignment

    private String openingConsignment(String sku) throws Exception {
        UUID ring = sellable(sku);
        UUID box = packaging("BOX-" + sku);
        return createdId("{\"opening\":true,\"note\":\"Day-one stock\",\"lines\":["
                + "{\"variantId\":\"" + ring + "\",\"quantity\":7,\"totalCost\":10000.00},"
                + "{\"variantId\":\"" + box + "\",\"quantity\":200,\"totalCost\":9000.00}]}");
    }

    @Test
    @DisplayName("the opening consignment is received like any other, and its events say they are estimates and name no supplier")
    void receivingTheOpeningStock() throws Exception {
        String opening = openingConsignment("RING-" + unique());
        List<String> lines = lineIds(opening);

        receive(opening).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.estimated").value(true));

        List<Event> events = events(lines);
        assertThat(events).hasSize(2);
        assertThat((Boolean) JsonPath.read(events.get(0).payload(), "$.isEstimated")).isTrue();
        assertThat((Object) JsonPath.read(events.get(0).payload(), "$.supplierId")).isNull();
        assertThat((Object) JsonPath.read(events.get(0).payload(), "$.supplierName")).isNull();
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(events.get(0).payload(), "$.landedTotal"))))
                .isEqualByComparingTo("10000.00");
        assertThat((Integer) JsonPath.read(events.get(0).payload(), "$.slices.length()")).isEqualTo(2);
    }

    @Test
    @DisplayName("opening stock must be received before any shipment: afterwards it would count as newer than what it predates, and oldest-first would sell it wrongly")
    void openingAfterAShipmentIsRefused() throws Exception {
        Shipment shipment = shipment();
        receive(shipment.consignment()).andExpect(status().isOk());
        String opening = openingConsignment("RING-" + unique());
        List<String> lines = lineIds(opening);

        receive(opening).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("opening stock must be received before")));

        assertThat(events(lines)).isEmpty();
        mvc.perform(get(PATH + "/" + opening).with(maya())).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("several opening consignments may be received, so long as no shipment has been")
    void severalOpeningConsignmentsBeforeAnyShipment() throws Exception {
        String first = openingConsignment("RING-" + unique());
        String second = openingConsignment("RING-" + unique());
        Shipment shipment = shipment();

        receive(first).andExpect(status().isOk());
        receive(second).andExpect(status().isOk());
        receive(shipment.consignment()).andExpect(status().isOk());

        Instant a = Instant.parse(JsonPath.read(json(first), "$.receivedAt"));
        Instant b = Instant.parse(JsonPath.read(json(second), "$.receivedAt"));
        Instant c = Instant.parse(JsonPath.read(json(shipment.consignment()), "$.receivedAt"));
        assertThat(a).isBefore(b);
        assertThat(b).isBefore(c);
    }

    @Test
    @DisplayName("an opening consignment and a shipment received at the same moment: whichever way it falls, the opening stock is never later than the shipment")
    void openingAndShipmentAtOnce() throws Exception {
        String opening = openingConsignment("RING-" + unique());
        Shipment shipment = shipment();
        long openingVersion = version(opening);
        long shipmentVersion = version(shipment.consignment());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Integer> openingResult = pool.submit(() -> {
                ready.countDown();
                go.await();
                return receive(opening, openingVersion).andReturn().getResponse().getStatus();
            });
            Future<Integer> shipmentResult = pool.submit(() -> {
                ready.countDown();
                go.await();
                return receive(shipment.consignment(), shipmentVersion).andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            int openingStatus = openingResult.get(60, TimeUnit.SECONDS);
            int shipmentStatus = shipmentResult.get(60, TimeUnit.SECONDS);

            assertThat(shipmentStatus).as("a shipment is never refused because of opening stock").isEqualTo(200);
            assertThat(openingStatus).isIn(200, 409);
            if (openingStatus == 200) {
                Instant a = Instant.parse(JsonPath.read(json(opening), "$.receivedAt"));
                Instant b = Instant.parse(JsonPath.read(json(shipment.consignment()), "$.receivedAt"));
                assertThat(a).as("the opening stock landed first").isBefore(b);
            } else {
                mvc.perform(get(PATH + "/" + opening).with(maya())).andExpect(jsonPath("$.status").value("DRAFT"));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the list shows which consignments have landed")
    void theListShowsReceived() throws Exception {
        Shipment received = shipment();
        shipment();
        receive(received.consignment()).andExpect(status().isOk());

        mvc.perform(get(PATH).param("status", "RECEIVED").with(maya()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(received.consignment()))
                .andExpect(jsonPath("$.content[0].receivedAt").isNotEmpty());
        mvc.perform(get(PATH).param("status", "DRAFT").with(maya()))
                .andExpect(jsonPath("$.content", hasSize(1)));
    }
}
