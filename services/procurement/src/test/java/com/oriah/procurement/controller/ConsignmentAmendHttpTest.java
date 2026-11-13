package com.oriah.procurement.controller;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.oriah.events.OutboxWriter;
import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;
import com.oriah.procurement.client.CatalogClient;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Correcting a received consignment's cost. The shipment is not edited (it never is): a correction is a new REVISION
 * of its allocation, with a reason and who made it, and inventory is told per line (consignment.amended) so it can
 * re-lay the new unit costs over the stock.
 *
 * <p>The worked example, MVP_PLAN Part 4: 7 rings at K10,500.00 and 200 boxes at K10,000.00 under a courier charge
 * keyed K6,000.00 and shared by units. The charge was really K600.00: the ring then lands at 6 x K1,502.90 and
 * 1 x K1,502.89, the boxes at 171 x K52.90 and 29 x K52.89, and the consignment at K21,100.00 in all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class ConsignmentAmendHttpTest {

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

    @MockitoSpyBean
    private OutboxWriter outbox;

    private final Map<UUID, Entry> known = new HashMap<>();

    @BeforeEach
    void clean() {
        ProcurementTestData.wipe(jdbc);
        Mockito.reset(catalog);
        known.clear();
        when(catalog.lookup(any())).thenAnswer(call -> {
            CatalogClient.VariantLookupBody body = call.getArgument(0);
            return new VariantLookupResult(body.variantIds().stream()
                    .map(id -> known.getOrDefault(id, new Entry(id, false, null, null, null, null, null, null, null, null))).toList());
        });
    }

    static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    static RequestPostProcessor joe() {
        return jwt().jwt(j -> j.subject("kc-joe")).authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    // ----------------------------------------------------------- fixtures

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID variant(String sku, String kind) {
        UUID id = UUID.randomUUID();
        known.put(id, new Entry(id, true, UUID.randomUUID(), "Aurelia Ring", "Aurelia Ring - " + sku, sku, kind, "DRAFT", true, false));
        return id;
    }

    private String placeOrder(UUID variant, int quantity, String total) throws Exception {
        MvcResult supplier = mvc.perform(post(SUPPLIERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Supplier " + unique() + "\",\"country\":\"AE\"}")).andExpect(status().isCreated()).andReturn();
        String supplierId = JsonPath.read(supplier.getResponse().getContentAsString(), "$.id");
        MvcResult result = mvc.perform(post(ORDERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + variant + "\",\"quantity\":" + quantity
                                + ",\"totalPaid\":" + total + "}]}")).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String orderRef(String order) throws Exception {
        MvcResult result = mvc.perform(get(ORDERS + "/" + order).with(maya())).andExpect(status().isOk()).andReturn();
        long version = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.version")).longValue();
        return "{\"id\":\"" + order + "\",\"version\":" + version + "}";
    }

    private record Shipment(String consignment, String ringOrder, UUID ring, UUID box) {
    }

    /** 7 rings (K10,500.00) and 200 boxes (K10,000.00), courier K6,000.00 by units; received. */
    private Shipment receivedShipment() throws Exception {
        UUID ring = variant("RING-" + unique(), "SELLABLE");
        UUID box = variant("BOX-" + unique(), "PACKAGING");
        String ringOrder = placeOrder(ring, 7, "10500.00");
        String boxOrder = placeOrder(box, 200, "10000.00");
        MvcResult created = mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchaseOrders\":[" + orderRef(ringOrder) + "," + orderRef(boxOrder) + "]}")).andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(put(PATH + "/" + id + "/cost-lines").with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"costLines\":[{\"costType\":\"COURIER_INBOUND\",\"amount\":6000.00}]}")).andExpect(status().isOk());
        long version = ((Number) JsonPath.read(json(id), "$.version")).longValue();
        mvc.perform(post(PATH + "/" + id + "/receive").with(maya()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isOk());
        return new Shipment(id, ringOrder, ring, box);
    }

    private String json(String consignment) throws Exception {
        return mvc.perform(get(PATH + "/" + consignment).with(maya())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<String> lineIds(String consignment) throws Exception {
        return JsonPath.read(json(consignment), "$.lines[*].id");
    }

    private ResultActions amend(String consignment, RequestPostProcessor as, String body) throws Exception {
        return mvc.perform(post(PATH + "/" + consignment + "/amendments").with(as).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String courier(String amount) {
        return "{\"costType\":\"COURIER_INBOUND\",\"amount\":" + amount + "}";
    }

    private static String amendment(int fromRevision, String reason, String costLines) {
        return "{\"fromRevision\":" + fromRevision + ",\"reason\":\"" + reason + "\"" + (costLines == null ? "" : ",\"costLines\":[" + costLines + "]") + "}";
    }

    private record Event(String aggregateId, long seq, String eventType, int schemaVersion, String topic, OffsetDateTime occurredAt, String actor, String payload) {
    }

    private List<Event> events(List<String> lineIds, String type) {
        String in = String.join(",", lineIds.stream().map(id -> "'" + id + "'").toList());
        return jdbc.query("SELECT aggregate_id, seq, event_type, schema_version, topic, occurred_at, actor, payload::text FROM outbox "
                        + "WHERE aggregate_id IN (" + in + ") AND event_type = ? ORDER BY outbox_id",
                (rs, n) -> new Event(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class),
                        rs.getString(7), rs.getString(8)), type);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // -------------------------------------------------------------- amending

    @Test
    @DisplayName("the courier's charge was K600, not K6,000: a new revision, the new unit costs to the ngwee, and an event per line")
    void aCorrectedCourierCharge() throws Exception {
        Shipment shipment = receivedShipment();
        List<String> lines = lineIds(shipment.consignment());

        MvcResult result = amend(shipment.consignment(), maya(), amendment(1, "the courier's bill was K600, not K6,000", courier("600.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.reason").value("the courier's bill was K600, not K6,000"))
                .andExpect(jsonPath("$.amendedBy").value("kc-maya"))
                .andExpect(jsonPath("$.estimated").value(false))
                .andExpect(jsonPath("$.allocation.ready").value(true))
                .andExpect(jsonPath("$.allocation.revision").value(2))
                .andExpect(jsonPath("$.allocation.landedTotal").value(21100.00))
                .andExpect(jsonPath("$.allocation.lines[0].sharedCost").value(20.29))
                .andExpect(jsonPath("$.allocation.lines[0].slices[0].quantity").value(6))
                .andExpect(jsonPath("$.allocation.lines[0].slices[0].unitCost").value(1502.90))
                .andExpect(jsonPath("$.allocation.lines[0].slices[1].quantity").value(1))
                .andExpect(jsonPath("$.allocation.lines[0].slices[1].unitCost").value(1502.89))
                .andExpect(jsonPath("$.allocation.lines[1].slices[0].quantity").value(171))
                .andExpect(jsonPath("$.allocation.lines[1].slices[0].unitCost").value(52.90))
                .andExpect(jsonPath("$.allocation.lines[1].slices[1].quantity").value(29))
                .andExpect(jsonPath("$.allocation.lines[1].slices[1].unitCost").value(52.89))
                .andExpect(jsonPath("$.recutLines", hasSize(2)))
                .andReturn();
        assertThat((String) JsonPath.read(result.getResponse().getHeader("Location"), "$")).contains("/allocation?revision=2");

        List<Event> amended = events(lines, "consignment.amended");
        assertThat(amended).hasSize(2);
        Event ring = amended.get(0);
        assertThat(ring.aggregateId()).isEqualTo(lines.get(0));
        assertThat(ring.seq()).as("the second event of the line's own history").isEqualTo(2);
        assertThat(ring.schemaVersion()).isEqualTo(1);
        assertThat(ring.topic()).isEqualTo("oriah.procurement");
        assertThat(ring.actor()).isEqualTo("user:kc-maya");
        String p = ring.payload();
        assertThat((Integer) JsonPath.read(p, "$.revision")).isEqualTo(2);
        assertThat((String) JsonPath.read(p, "$.reason")).isEqualTo("the courier's bill was K600, not K6,000");
        assertThat((String) JsonPath.read(p, "$.consignmentLineId")).isEqualTo(lines.get(0));
        assertThat((Integer) JsonPath.read(p, "$.linePosition")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(p, "$.quantity")).isEqualTo(7);
        assertThat(p).contains("\"landedTotal\":10520.29").contains("\"sharedCost\":20.29").contains("\"unitCost\":1502.90");
        assertThat((Boolean) JsonPath.read(p, "$.isEstimated")).isFalse();
        String receivedAt = JsonPath.read(p, "$.receivedAt");
        String amendedAt = JsonPath.read(p, "$.amendedAt");
        assertThat(Instant.parse(receivedAt)).as("the FIFO key stays the receipt's").isBefore(Instant.parse(amendedAt));
        assertThat(ring.occurredAt().toInstant()).as("when it happened is when it was amended").isEqualTo(Instant.parse(amendedAt));
        // The field order is the wire order: everything a receipt says, then the revision, the reason and when.
        List<String> fields = new ArrayList<>(tools.jackson.databind.json.JsonMapper.builder().build().readTree(p).propertyNames());
        assertThat(fields).containsExactly("consignmentId", "consignmentReference", "consignmentLineId", "variantId", "sku", "productTitle",
                "variantTitle", "kind", "supplierId", "supplierName", "receivedAt", "isEstimated", "currency", "quantity", "goodsTotal", "sharedCost",
                "landedTotal", "slices", "linePosition", "revision", "reason", "amendedAt");
    }

    @Test
    @DisplayName("a line a correction does not change gets no event: only the ring's duty moves the ring")
    void onlyChangedLinesGetEvents() throws Exception {
        Shipment shipment = receivedShipment();
        List<String> lines = lineIds(shipment.consignment());
        String duty = "{\"costType\":\"DUTY\",\"amount\":100.00,\"basis\":\"DIRECT\",\"shares\":[{\"lineId\":\"" + lines.get(0)
                + "\",\"amount\":100.00},{\"lineId\":\"" + lines.get(1) + "\",\"amount\":0.00}]}";

        amend(shipment.consignment(), maya(), amendment(1, "a duty assessment arrived late", courier("6000.00") + "," + duty))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recutLines", hasSize(1)))
                .andExpect(jsonPath("$.recutLines[0]").value(lines.get(0)))
                .andExpect(jsonPath("$.allocation.landedTotal").value(26600.00));

        assertThat(events(lines, "consignment.amended")).extracting(Event::aggregateId).containsExactly(lines.get(0));
    }

    @Test
    @DisplayName("a correction is made from the revision someone saw: a stale one is 409 concurrent-update and changes nothing")
    void aStaleRevision() throws Exception {
        Shipment shipment = receivedShipment();
        amend(shipment.consignment(), maya(), amendment(1, "first", courier("600.00"))).andExpect(status().isCreated());

        amend(shipment.consignment(), joe(), amendment(1, "second, from the old screen", courier("700.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/concurrent-update"));

        assertThat(count("SELECT count(*) FROM allocation_revision WHERE consignment_id = ?::uuid", shipment.consignment())).isEqualTo(2);
    }

    @Test
    @DisplayName("two corrections of the same revision at the same moment: one is recorded, the other is told to look again")
    void twoAtOnce() throws Exception {
        Shipment shipment = receivedShipment();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> { go.await(); return amend(shipment.consignment(), maya(), amendment(1, "a", courier("600.00"))).andReturn().getResponse().getStatus(); });
            Future<Integer> b = pool.submit(() -> { go.await(); return amend(shipment.consignment(), joe(), amendment(1, "b", courier("700.00"))).andReturn().getResponse().getStatus(); });
            go.countDown();
            assertThat(List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM allocation_revision WHERE consignment_id = ?::uuid", shipment.consignment())).isEqualTo(2);
        // The outbox is append-only, so earlier tests' events stay: count this consignment's own. The winner told
        // inventory about its two lines; the loser wrote nothing.
        assertThat(events(lineIds(shipment.consignment()), "consignment.amended")).hasSize(2);
    }

    @Test
    @DisplayName("a correction that changes nothing is refused, and so is one with no reason, and a draft cannot be corrected")
    void refusals() throws Exception {
        Shipment shipment = receivedShipment();

        amend(shipment.consignment(), maya(), amendment(1, "looked again, it was right", courier("6000.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("changes nothing")));
        amend(shipment.consignment(), maya(), "{\"fromRevision\":1,\"reason\":\" \",\"costLines\":[" + courier("600.00") + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        amend(shipment.consignment(), maya(), "{\"reason\":\"x\",\"costLines\":[" + courier("600.00") + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("fromRevision"));
        amend(shipment.consignment(), maya(), "{\"fromRevision\":1,\"reason\":\"x\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("changes nothing")));
        // An amount finer than a ngwee is refused at the edge, naming the field (nothing is rounded for the books).
        amend(shipment.consignment(), maya(), amendment(1, "x", courier("600.005")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("costLines[0].amount"));
        assertThat(count("SELECT count(*) FROM allocation_revision WHERE consignment_id = ?::uuid", shipment.consignment())).isEqualTo(1);

        UUID ring = variant("RING-" + unique(), "SELLABLE");
        MvcResult draft = mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrders\":[" + orderRef(placeOrder(ring, 1, "10.00")) + "]}")).andExpect(status().isCreated()).andReturn();
        String draftId = JsonPath.read(draft.getResponse().getContentAsString(), "$.id");
        amend(draftId, maya(), amendment(1, "x", courier("5.00"))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the cost rules are the draft's: a bank charge is refused, and a typed split must name every line and add up")
    void theSameRulesAsADraft() throws Exception {
        Shipment shipment = receivedShipment();
        List<String> lines = lineIds(shipment.consignment());

        amend(shipment.consignment(), maya(), amendment(1, "x", "{\"costType\":\"BANK_CHARGE\",\"amount\":10.00}"))
                .andExpect(status().isBadRequest());
        String partial = "{\"costType\":\"DUTY\",\"amount\":100.00,\"basis\":\"DIRECT\",\"shares\":[{\"lineId\":\"" + lines.get(0) + "\",\"amount\":100.00}]}";
        amend(shipment.consignment(), maya(), amendment(1, "x", courier("6000.00") + "," + partial)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("every line")));
        assertThat(count("SELECT count(*) FROM allocation_revision WHERE consignment_id = ?::uuid", shipment.consignment())).isEqualTo(1);
    }

    @Test
    @DisplayName("a supplier's total that was mistyped can be corrected: the order and its invoice keep what was typed, the cost follows")
    void aGoodsCorrection() throws Exception {
        Shipment shipment = receivedShipment();
        List<String> lines = lineIds(shipment.consignment());

        amend(shipment.consignment(), maya(), "{\"fromRevision\":1,\"reason\":\"the bank slip said K10,000, not K10,500\",\"goodsLines\":[{\"lineId\":\""
                + lines.get(0) + "\",\"goodsTotal\":10000.00}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocation.lines[0].goodsTotal").value(10000.00))
                .andExpect(jsonPath("$.allocation.goodsTotal").value(20000.00))
                .andExpect(jsonPath("$.allocation.landedTotal").value(26000.00))
                // The courier is shared by units, which did not move: only the ring, whose goods changed, is re-cut.
                .andExpect(jsonPath("$.recutLines", hasSize(1)))
                .andExpect(jsonPath("$.recutLines[0]").value(lines.get(0)));

        mvc.perform(get(ORDERS + "/" + shipment.ringOrder()).with(maya())).andExpect(jsonPath("$.lines[0].totalPaid").value(10500.00));
    }

    @Test
    @DisplayName("goods cannot be corrected to nothing, to a line that is not in the consignment, or twice for one line")
    void goodsCorrectionRefusals() throws Exception {
        Shipment shipment = receivedShipment();
        List<String> lines = lineIds(shipment.consignment());

        for (String body : List.of(
                "{\"lineId\":\"" + lines.get(0) + "\",\"goodsTotal\":0.00}",
                "{\"lineId\":\"" + UUID.randomUUID() + "\",\"goodsTotal\":5.00}",
                "{\"lineId\":\"" + lines.get(0) + "\",\"goodsTotal\":5.00},{\"lineId\":\"" + lines.get(0) + "\",\"goodsTotal\":6.00}",
                "{\"lineId\":\"" + lines.get(0) + "\",\"goodsTotal\":5.001}")) {
            amend(shipment.consignment(), maya(), "{\"fromRevision\":1,\"reason\":\"x\",\"goodsLines\":[" + body + "]}").andExpect(status().isBadRequest());
        }
        assertThat(count("SELECT count(*) FROM allocation_revision WHERE consignment_id = ?::uuid", shipment.consignment())).isEqualTo(1);
    }

    @Test
    @DisplayName("the opening stock's estimated costs can be corrected, but it stays an estimate; a shipment's provisional bill can be cleared")
    void estimates() throws Exception {
        // An opening consignment: day-one stock keyed from the old book.
        UUID variant = variant("RING-" + unique(), "SELLABLE");
        MvcResult opening = mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"opening\":true,\"lines\":[{\"variantId\":\"" + variant + "\",\"quantity\":4,\"totalCost\":400.00}]}"))
                .andExpect(status().isCreated()).andReturn();
        String openingId = JsonPath.read(opening.getResponse().getContentAsString(), "$.id");
        long version = ((Number) JsonPath.read(json(openingId), "$.version")).longValue();
        mvc.perform(post(PATH + "/" + openingId + "/receive").with(maya()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isOk());
        String line = lineIds(openingId).get(0);

        amend(openingId, maya(), "{\"fromRevision\":1,\"reason\":\"counted the book again\",\"estimated\":false,\"goodsLines\":[{\"lineId\":\"" + line
                + "\",\"goodsTotal\":440.00}]}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("estimate")));
        amend(openingId, maya(), "{\"fromRevision\":1,\"reason\":\"counted the book again\",\"goodsLines\":[{\"lineId\":\"" + line + "\",\"goodsTotal\":440.00}]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estimated").value(true)).andExpect(jsonPath("$.allocation.lines[0].goodsTotal").value(440.00));
        assertThat((Boolean) JsonPath.read(events(List.of(line), "consignment.amended").get(0).payload(), "$.isEstimated")).isTrue();
    }

    @Test
    @DisplayName("a shipment marked an estimate (its courier bill was provisional) is cleared by the final bill")
    void aShipmentClearsItsEstimate() throws Exception {
        UUID ring = variant("RING-" + unique(), "SELLABLE");
        String order = placeOrder(ring, 7, "10500.00");
        MvcResult created = mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrders\":[" + orderRef(order) + "]}")).andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(put(PATH + "/" + id + "/cost-lines").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"costLines\":[" + courier("100.00") + "]}")).andExpect(status().isOk());
        long v1 = ((Number) JsonPath.read(json(id), "$.version")).longValue();
        mvc.perform(put(PATH + "/" + id).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + v1 + ",\"estimated\":true}")).andExpect(status().isOk());
        long v2 = ((Number) JsonPath.read(json(id), "$.version")).longValue();
        mvc.perform(post(PATH + "/" + id + "/receive").with(maya()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + v2 + "}")).andExpect(status().isOk());
        String line = lineIds(id).get(0);

        amend(id, maya(), "{\"fromRevision\":1,\"reason\":\"the final bill\",\"estimated\":false,\"costLines\":[" + courier("100.00") + "]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estimated").value(false)).andExpect(jsonPath("$.recutLines[0]").value(line));
        assertThat((Boolean) JsonPath.read(events(List.of(line), "consignment.amended").get(0).payload(), "$.isEstimated")).as("the same costs, no longer an estimate: still an event").isFalse();
    }

    @Test
    @DisplayName("the reads follow the corrections: the consignment, its allocation, and the list of revisions with who and why")
    void theReads() throws Exception {
        Shipment shipment = receivedShipment();
        amend(shipment.consignment(), maya(), amendment(1, "the courier's bill was K600", courier("600.00"))).andExpect(status().isCreated());

        mvc.perform(get(PATH + "/" + shipment.consignment()).with(maya())).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.capitalizedCostTotal").value(600.00))
                .andExpect(jsonPath("$.costLines[0].amount").value(600.00))
                .andExpect(jsonPath("$.version").isNumber());
        mvc.perform(get(PATH + "/" + shipment.consignment() + "/allocation").with(maya()))
                .andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.landedTotal").value(21100.00))
                .andExpect(jsonPath("$.costs[0].amount").value(600.00));
        mvc.perform(get(PATH + "/" + shipment.consignment() + "/allocation").with(maya()).param("revision", "1"))
                .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.landedTotal").value(26500.00))
                .andExpect(jsonPath("$.lines[0].slices[0].unitCost").value(1528.99)).andExpect(jsonPath("$.costs[0].amount").value(6000.00));
        mvc.perform(get(PATH + "/" + shipment.consignment() + "/allocation").with(maya()).param("revision", "9")).andExpect(status().isNotFound());

        mvc.perform(get(PATH + "/" + shipment.consignment() + "/revisions").with(maya())).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].revision").value(1)).andExpect(jsonPath("$[0].reason").doesNotExist())
                .andExpect(jsonPath("$[0].landedTotal").value(26500.00)).andExpect(jsonPath("$[0].changedLines", hasSize(0)))
                .andExpect(jsonPath("$[1].revision").value(2)).andExpect(jsonPath("$[1].reason").value("the courier's bill was K600"))
                .andExpect(jsonPath("$[1].createdBy").value("kc-maya")).andExpect(jsonPath("$[1].landedTotal").value(21100.00))
                .andExpect(jsonPath("$[1].changedLines", hasSize(2))).andExpect(jsonPath("$[1].estimated").value(false));
    }

    @Test
    @DisplayName("every typed split records who typed it: kept from the revision before when unchanged, the amender's when new")
    void typistsAreRecorded() throws Exception {
        Shipment shipment = receivedShipment();
        List<String> lines = lineIds(shipment.consignment());
        String duty = "{\"costType\":\"DUTY\",\"amount\":100.00,\"basis\":\"DIRECT\",\"shares\":[{\"lineId\":\"" + lines.get(0)
                + "\",\"amount\":100.00},{\"lineId\":\"" + lines.get(1) + "\",\"amount\":0.00}]}";
        amend(shipment.consignment(), maya(), amendment(1, "a duty arrived", courier("6000.00") + "," + duty)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocation.costs[1].typedBy").value("kc-maya"));

        // Joe corrects the courier's charge and sends the duty's split again, unchanged: it stays Maya's. A new split would be his.
        String newSplit = "{\"costType\":\"INSURANCE\",\"amount\":50.00,\"basis\":\"DIRECT\",\"shares\":[{\"lineId\":\"" + lines.get(0)
                + "\",\"amount\":20.00},{\"lineId\":\"" + lines.get(1) + "\",\"amount\":30.00}]}";
        amend(shipment.consignment(), joe(), amendment(2, "courier re-billed", courier("600.00") + "," + duty + "," + newSplit))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocation.costs[1].typedBy").value("kc-maya"))
                .andExpect(jsonPath("$.allocation.costs[2].typedBy").value("kc-joe"));
    }

    @Test
    @DisplayName("a failure while the events are written leaves no revision behind: nothing is recorded for a correction nobody was told about")
    void aFailureLeavesNothing() throws Exception {
        Shipment shipment = receivedShipment();
        // The first line's event is written, then the outbox fails on the second. The outbox bean is transactional,
        // so it is stubbed behind its proxy.
        OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
        doCallRealMethod().doThrow(new RuntimeException("the outbox failed")).when(target).append(any());

        amend(shipment.consignment(), maya(), amendment(1, "x", courier("600.00"))).andExpect(status().isInternalServerError());

        assertThat(count("SELECT count(*) FROM allocation_revision WHERE consignment_id = ?::uuid", shipment.consignment())).isEqualTo(1);
        assertThat(events(lineIds(shipment.consignment()), "consignment.amended")).as("the first line's event rolled back with the revision").isEmpty();

        // And it can still be made afterwards.
        Mockito.reset(target);
        amend(shipment.consignment(), maya(), amendment(1, "x", courier("600.00"))).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("only PROCUREMENT_ADMIN may amend or read revisions: a customer is 403 and no token is 401")
    void whoMay() throws Exception {
        Shipment shipment = receivedShipment();
        RequestPostProcessor alice = jwt().jwt(j -> j.subject("kc-alice")).authorities(new SimpleGrantedAuthority("ROLE_USER"));

        amend(shipment.consignment(), alice, amendment(1, "x", courier("600.00"))).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/" + shipment.consignment() + "/revisions").with(alice)).andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/" + shipment.consignment() + "/amendments").contentType(MediaType.APPLICATION_JSON).content(amendment(1, "x", courier("600.00"))))
                .andExpect(status().isUnauthorized());
    }
}
