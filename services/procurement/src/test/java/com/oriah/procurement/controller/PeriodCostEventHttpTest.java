package com.oriah.procurement.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A bill for the month on a consignment ({@code PERIOD}: a courier charge, a port fee that is the month's cost, not the goods') is announced to finance as
 * {@code consignment.period_costs} (Phase 10), because it reaches profit as an EXPENSE of a month, not through the cost of goods.
 *
 * <p>One event per allocation revision that changes the month's bills. Revision 1 books each bill on the day it was incurred (the receipt day when no date was
 * keyed); a later revision is a correction and counts from the day it is made (CLIENT_DECISIONS: a re-tag counts from that day, and a month already reported keeps
 * its figures), so it takes back the previous revision's bills and books the corrected set, both on the day of the correction. A bill of K0.00 is no bill: it is
 * never in the event. Duty, surtax and import VAT are never a bill for the month (they are paid at clearance and reach profit through the cost of goods).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class PeriodCostEventHttpTest {

    static final String PATH = "/api/v1/admin/procurement/consignments";
    static final String ORDERS = "/api/v1/admin/procurement/purchase-orders";
    static final String SUPPLIERS = "/api/v1/admin/procurement/suppliers";
    private static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");

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
                    .map(id -> known.getOrDefault(id, new Entry(id, false, null, null, null, null, null, null, null, null))).toList());
        });
    }

    static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    // ----------------------------------------------------------- fixtures

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID ring() {
        UUID id = UUID.randomUUID();
        known.put(id, new Entry(id, true, UUID.randomUUID(), "Aurelia Ring", "Aurelia Ring - " + unique(), "RING-" + unique(), "SELLABLE", "DRAFT", true, false));
        return id;
    }

    private String orderOfOneRing() throws Exception {
        MvcResult supplier = mvc.perform(post(SUPPLIERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Supplier " + unique() + "\",\"country\":\"AE\"}")).andExpect(status().isCreated()).andReturn();
        String supplierId = JsonPath.read(supplier.getResponse().getContentAsString(), "$.id");
        MvcResult result = mvc.perform(post(ORDERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + ring() + "\",\"quantity\":10,\"totalPaid\":10000.00}]}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String body(String id) throws Exception {
        return mvc.perform(get(PATH + "/" + id).with(maya())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** A consignment of ten rings at K10,000.00 with these cost lines (a JSON list body), received. Returns its id. */
    private String received(String costLines) throws Exception {
        String order = orderOfOneRing();
        long orderVersion = ((Number) JsonPath.read(mvc.perform(get(ORDERS + "/" + order).with(maya())).andReturn().getResponse().getContentAsString(), "$.version")).longValue();
        MvcResult created = mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrders\":[{\"id\":\"" + order + "\",\"version\":" + orderVersion + "}]}")).andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(put(PATH + "/" + id + "/cost-lines").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"costLines\":" + costLines + "}")).andExpect(status().isOk());
        long version = ((Number) JsonPath.read(body(id), "$.version")).longValue();
        mvc.perform(post(PATH + "/" + id + "/receive").with(maya()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isOk());
        return id;
    }

    private ResultActions amend(String id, int fromRevision, String reason, String costLines) throws Exception {
        return mvc.perform(post(PATH + "/" + id + "/amendments").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromRevision\":" + fromRevision + ",\"reason\":\"" + reason + "\",\"costLines\":" + costLines + "}"));
    }

    private static String period(String type, String amount, String incurredOn) {
        return "{\"costType\":\"" + type + "\",\"amount\":" + amount + ",\"treatment\":\"PERIOD\""
                + (incurredOn == null ? "" : ",\"incurredOn\":\"" + incurredOn + "\"") + "}";
    }

    private static String capital(String type, String amount) {
        return "{\"costType\":\"" + type + "\",\"amount\":" + amount + "}";
    }

    private static String list(String... costs) {
        return "[" + String.join(",", costs) + "]";
    }

    private record Event(long seq, String type, int schemaVersion, String topic, OffsetDateTime occurredAt, String actor, String payload) {
    }

    private List<Event> events(String consignment, String type) {
        return jdbc.query("SELECT seq, event_type, schema_version, topic, occurred_at, actor, payload::text FROM outbox "
                        + "WHERE aggregate_type = 'consignment' AND aggregate_id = ? AND event_type = ? ORDER BY outbox_id",
                (rs, n) -> new Event(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class), rs.getString(6),
                        rs.getString(7)), consignment, type);
    }

    private List<Event> periodEvents(String consignment) {
        return events(consignment, "consignment.period_costs");
    }

    private long amendedEvents(String consignment) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type = 'consignment.amended' AND payload::text LIKE ?", Long.class,
                "%" + consignment + "%");
    }

    private static String lusakaDay(String instant) {
        return LocalDate.ofInstant(Instant.parse(instant), LUSAKA).toString();
    }

    // ------------------------------------------------------------------ receiving

    @Test
    @DisplayName("receiving a consignment with bills for the month announces them once: each on the day it was incurred (the receipt day when none was keyed), with the receipt's own time")
    void theMonthsBillsAreAnnouncedAtReceipt() throws Exception {
        String id = received(list(capital("AGENT_FEE", "100.00"), period("COURIER_INBOUND", "40.00", "2026-10-31"), period("FREIGHT", "25.00", null)));

        List<Event> events = periodEvents(id);

        assertThat(events).hasSize(1);
        Event event = events.get(0);
        assertThat(event.seq()).isEqualTo(1);
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.topic()).isEqualTo("oriah.procurement");
        assertThat(event.actor()).isEqualTo("user:kc-maya");
        String p = event.payload();
        List<String> fields = new ArrayList<>(tools.jackson.databind.json.JsonMapper.builder().build().readTree(p).propertyNames());
        assertThat(fields).containsExactly("consignmentId", "consignmentReference", "revision", "currency", "receivedAt", "amendedAt", "reason", "costs",
                "replaces", "recordedAt");
        assertThat((String) JsonPath.read(p, "$.consignmentId")).isEqualTo(id);
        assertThat((String) JsonPath.read(p, "$.consignmentReference")).matches("C-\\d{6}");
        assertThat((Integer) JsonPath.read(p, "$.revision")).isEqualTo(1);
        assertThat((String) JsonPath.read(p, "$.currency")).isEqualTo("ZMW");
        assertThat((Object) JsonPath.read(p, "$.amendedAt")).isNull();
        assertThat((Object) JsonPath.read(p, "$.reason")).isNull();
        String receivedAt = JsonPath.read(p, "$.receivedAt");
        assertThat(event.occurredAt().toInstant()).as("when it happened is the receipt").isEqualTo(Instant.parse(receivedAt));
        assertThat((List<?>) JsonPath.read(p, "$.replaces")).isEmpty();
        assertThat((List<?>) JsonPath.read(p, "$.costs")).hasSize(2);
        assertThat((Integer) JsonPath.read(p, "$.costs[0].position")).as("the cost line's place in the list").isEqualTo(2);
        assertThat((String) JsonPath.read(p, "$.costs[0].costType")).isEqualTo("COURIER_INBOUND");
        assertThat(p).contains("\"amount\":40.00").contains("\"amount\":25.00");
        assertThat((String) JsonPath.read(p, "$.costs[0].effectiveOn")).isEqualTo("2026-10-31");
        assertThat((Integer) JsonPath.read(p, "$.costs[1].position")).isEqualTo(3);
        assertThat((String) JsonPath.read(p, "$.costs[1].effectiveOn")).as("no date keyed: the Lusaka day of the receipt").isEqualTo(lusakaDay(receivedAt));
        Instant created = jdbc.queryForObject("SELECT created_at FROM allocation_revision WHERE consignment_id = ?::uuid AND revision = 1",
                java.sql.Timestamp.class, id).toInstant();
        assertThat(Instant.parse(JsonPath.read(p, "$.recordedAt"))).as("when the revision was recorded").isEqualTo(created);
    }

    @Test
    @DisplayName("no bill for the month, nothing to announce: only costs that ride on the goods, or a bill of K0.00; and a K0.00 bill is left out beside a real one")
    void noBillNoEvent() throws Exception {
        String riding = received(list(capital("AGENT_FEE", "100.00"), capital("COURIER_INBOUND", "50.00")));
        String zero = received(list(period("FREIGHT", "0.00", null)));
        String mixed = received(list(period("FREIGHT", "0.00", null), period("COURIER_INBOUND", "60.00", null)));

        assertThat(periodEvents(riding)).isEmpty();
        assertThat(periodEvents(zero)).isEmpty();
        List<Event> events = periodEvents(mixed);
        assertThat(events).hasSize(1);
        assertThat((List<?>) JsonPath.read(events.get(0).payload(), "$.costs")).hasSize(1);
        assertThat((Integer) JsonPath.read(events.get(0).payload(), "$.costs[0].position")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ correcting

    @Test
    @DisplayName("a correction of a bill takes back the old bill and books the new one, both on the day of the correction: the month it was first booked in keeps its figure")
    void aCorrectedBill() throws Exception {
        String id = received(list(capital("COURIER_INBOUND", "600.00"), period("FREIGHT", "100.00", "2026-10-31")));

        amend(id, 1, "the freight bill was K150", list(capital("COURIER_INBOUND", "600.00"), period("FREIGHT", "150.00", "2026-10-31"))).andExpect(status().isCreated());

        List<Event> events = periodEvents(id);
        assertThat(events).hasSize(2);
        Event correction = events.get(1);
        assertThat(correction.seq()).isEqualTo(2);
        String p = correction.payload();
        assertThat((Integer) JsonPath.read(p, "$.revision")).isEqualTo(2);
        assertThat((String) JsonPath.read(p, "$.reason")).isEqualTo("the freight bill was K150");
        String amendedAt = JsonPath.read(p, "$.amendedAt");
        assertThat(correction.occurredAt().toInstant()).isEqualTo(Instant.parse(amendedAt));
        assertThat((List<?>) JsonPath.read(p, "$.replaces")).hasSize(1);
        assertThat((String) JsonPath.read(p, "$.replaces[0].costType")).isEqualTo("FREIGHT");
        assertThat(p).contains("\"replaces\":[{\"position\":2,\"costType\":\"FREIGHT\",\"amount\":100.00,\"effectiveOn\":\"" + lusakaDay(amendedAt) + "\"}]");
        assertThat(p).contains("\"costs\":[{\"position\":2,\"costType\":\"FREIGHT\",\"amount\":150.00,\"effectiveOn\":\"" + lusakaDay(amendedAt) + "\"}]");
        assertThat(amendedEvents(id)).as("nothing that rides on the goods moved, so inventory is told nothing").isZero();
    }

    @Test
    @DisplayName("a correction that moves only what rides on the goods says nothing about the month's bills; one that changes only the date of a bill books nothing new")
    void aCapitalCorrectionAndADateChange() throws Exception {
        String id = received(list(capital("COURIER_INBOUND", "600.00"), period("FREIGHT", "100.00", "2026-10-31")));

        amend(id, 1, "the courier was K500", list(capital("COURIER_INBOUND", "500.00"), period("FREIGHT", "100.00", "2026-10-31"))).andExpect(status().isCreated());
        amend(id, 2, "the freight was in September", list(capital("COURIER_INBOUND", "500.00"), period("FREIGHT", "100.00", "2026-09-30"))).andExpect(status().isCreated());

        assertThat(periodEvents(id)).as("only the receipt announced a bill").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM allocation_revision WHERE consignment_id = ?::uuid", Long.class, id)).isEqualTo(3);
        assertThat(amendedEvents(id)).as("the courier's correction did move the goods").isGreaterThan(0);
    }

    @Test
    @DisplayName("a charge re-tagged to a bill for the month counts from the day it is made, and one re-tagged back takes the bill back that day; the goods are re-cut either way")
    void aRetag() throws Exception {
        String id = received(list(capital("FREIGHT", "100.00")));
        assertThat(periodEvents(id)).isEmpty();

        amend(id, 1, "freight is a bill of the month", list(period("FREIGHT", "100.00", null))).andExpect(status().isCreated());
        amend(id, 2, "no, it rides on the goods after all", list(capital("FREIGHT", "100.00"))).andExpect(status().isCreated());

        List<Event> events = periodEvents(id);
        assertThat(events).hasSize(2);
        String toPeriod = events.get(0).payload();
        String amendedAt = JsonPath.read(toPeriod, "$.amendedAt");
        assertThat((List<?>) JsonPath.read(toPeriod, "$.replaces")).isEmpty();
        assertThat(toPeriod).contains("\"costs\":[{\"position\":1,\"costType\":\"FREIGHT\",\"amount\":100.00,\"effectiveOn\":\"" + lusakaDay(amendedAt) + "\"}]");
        String back = events.get(1).payload();
        assertThat((List<?>) JsonPath.read(back, "$.costs")).as("no bills left in the month").isEmpty();
        assertThat((List<?>) JsonPath.read(back, "$.replaces")).hasSize(1);
        assertThat(amendedEvents(id)).as("the goods were re-cut both times").isGreaterThan(0);
    }

    // ------------------------------------------------------------------ what is never a bill for the month

    @Test
    @DisplayName("duty, surtax and import VAT are never a bill for the month: they are paid at clearance and reach profit through the cost of goods, so keying one as PERIOD is 400 (on a draft and on a correction)")
    void customsIsNeverABillForTheMonth() throws Exception {
        String order = orderOfOneRing();
        long orderVersion = ((Number) JsonPath.read(mvc.perform(get(ORDERS + "/" + order).with(maya())).andReturn().getResponse().getContentAsString(), "$.version")).longValue();
        String id = JsonPath.read(mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrders\":[{\"id\":\"" + order + "\",\"version\":" + orderVersion + "}]}")).andReturn().getResponse().getContentAsString(), "$.id");

        for (String type : new String[] {"DUTY", "SURTAX", "IMPORT_VAT"}) {
            mvc.perform(put(PATH + "/" + id + "/cost-lines").with(maya()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0,\"costLines\":[{\"costType\":\"" + type + "\",\"amount\":50.00,\"treatment\":\"PERIOD\"}]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("paid at clearance")));
        }

        String received = received(list(capital("COURIER_INBOUND", "600.00")));
        amend(received, 1, "duty arrived", list(capital("COURIER_INBOUND", "600.00"), period("DUTY", "80.00", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("paid at clearance")));
    }
}
