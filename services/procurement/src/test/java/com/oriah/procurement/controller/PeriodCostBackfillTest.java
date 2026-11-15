package com.oriah.procurement.controller;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;
import com.oriah.procurement.client.CatalogClient;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;
import com.oriah.procurement.events.PeriodCostBackfill;
import com.oriah.procurement.events.ProcurementEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bills for the month keyed before {@code consignment.period_costs} existed have no event, and the outbox is never rewritten: the start-up back-fill announces each
 * revision that was recorded without one, exactly once, with the day it was recorded, so finance, replaying this outbox from the beginning, finds every bill.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class PeriodCostBackfillTest {

    static final String PATH = "/api/v1/admin/procurement/consignments";
    static final String ORDERS = "/api/v1/admin/procurement/purchase-orders";
    static final String SUPPLIERS = "/api/v1/admin/procurement/suppliers";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PeriodCostBackfill backfill;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalog;

    @MockitoSpyBean
    private ProcurementEvents events;

    private final Map<UUID, Entry> known = new HashMap<>();

    @BeforeEach
    void clean() {
        ProcurementTestData.wipe(jdbc);
        Mockito.reset(catalog);
        Mockito.reset(target());
        known.clear();
        when(catalog.lookup(any())).thenAnswer(call -> {
            CatalogClient.VariantLookupBody body = call.getArgument(0);
            return new VariantLookupResult(body.variantIds().stream()
                    .map(id -> known.getOrDefault(id, new Entry(id, false, null, null, null, null, null, null, null, null))).toList());
        });
    }

    private static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    /** What the code did before Phase 10: the receipt and the corrections are recorded and announce nothing about the month's bills. */
    private void beforePhaseTen() {
        // the bean is transactional: stub the object behind its proxy, or stubbing itself would ask for a transaction
        doReturn(false).when(target()).periodCosts(any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    private void afterPhaseTen() {
        Mockito.reset(target());
    }

    private ProcurementEvents target() {
        return AopTestUtils.getUltimateTargetObject(events);
    }

    private String receivedWithAFreightBill(String amount) throws Exception {
        UUID ring = UUID.randomUUID();
        known.put(ring, new Entry(ring, true, UUID.randomUUID(), "Aurelia Ring", "Aurelia Ring - X", "RING-" + UUID.randomUUID().toString().substring(0, 8), "SELLABLE", "DRAFT",
                true, false));
        MvcResult supplier = mvc.perform(post(SUPPLIERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Supplier " + UUID.randomUUID().toString().substring(0, 8) + "\",\"country\":\"AE\"}")).andExpect(status().isCreated()).andReturn();
        String supplierId = JsonPath.read(supplier.getResponse().getContentAsString(), "$.id");
        MvcResult order = mvc.perform(post(ORDERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + ring + "\",\"quantity\":10,\"totalPaid\":10000.00}]}"))
                .andExpect(status().isCreated()).andReturn();
        String orderId = JsonPath.read(order.getResponse().getContentAsString(), "$.id");
        long orderVersion = ((Number) JsonPath.read(mvc.perform(get(ORDERS + "/" + orderId).with(maya())).andReturn().getResponse().getContentAsString(), "$.version")).longValue();
        String id = JsonPath.read(mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrders\":[{\"id\":\"" + orderId + "\",\"version\":" + orderVersion + "}]}")).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(put(PATH + "/" + id + "/cost-lines").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"costLines\":[{\"costType\":\"FREIGHT\",\"amount\":" + amount + ",\"treatment\":\"PERIOD\"}]}")).andExpect(status().isOk());
        long version = ((Number) JsonPath.read(mvc.perform(get(PATH + "/" + id).with(maya())).andReturn().getResponse().getContentAsString(), "$.version")).longValue();
        mvc.perform(post(PATH + "/" + id + "/receive").with(maya()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}")).andExpect(status().isOk());
        return id;
    }

    private void amendFreight(String id, int from, String amount) throws Exception {
        mvc.perform(post(PATH + "/" + id + "/amendments").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromRevision\":" + from + ",\"reason\":\"the bill was different\",\"costLines\":[{\"costType\":\"FREIGHT\",\"amount\":" + amount
                        + ",\"treatment\":\"PERIOD\"}]}")).andExpect(status().isCreated());
    }

    private List<String> payloads(String consignment) {
        return jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_type = 'consignment' AND aggregate_id = ? AND event_type = 'consignment.period_costs' "
                + "ORDER BY outbox_id", String.class, consignment);
    }

    @Test
    @DisplayName("a bill recorded before the event existed is announced at start-up, one event per revision that changed it, with the day each revision was recorded; a second run announces nothing")
    void historyIsAnnouncedOnce() throws Exception {
        beforePhaseTen();
        String id = receivedWithAFreightBill("100.00");
        amendFreight(id, 1, "150.00");
        afterPhaseTen();
        assertThat(payloads(id)).as("nothing was announced when they were recorded").isEmpty();

        int first = backfill.backfill();
        int second = backfill.backfill();

        assertThat(first).isEqualTo(2);
        assertThat(second).as("each revision is looked at once").isZero();
        List<String> events = payloads(id);
        assertThat(events).hasSize(2);
        assertThat((Integer) JsonPath.read(events.get(0), "$.revision")).isEqualTo(1);
        assertThat(events.get(0)).contains("\"amount\":100.00");
        assertThat((Integer) JsonPath.read(events.get(1), "$.revision")).isEqualTo(2);
        assertThat(events.get(1)).contains("\"replaces\":[{\"position\":1,\"costType\":\"FREIGHT\",\"amount\":100.00").contains("\"costs\":[{\"position\":1,\"costType\":\"FREIGHT\",\"amount\":150.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM period_cost_event WHERE consignment_id = ?::uuid", Long.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT actor FROM outbox WHERE aggregate_id = ? AND event_type = 'consignment.period_costs' ORDER BY outbox_id LIMIT 1", String.class, id))
                .isEqualTo("system:procurement");
    }

    @Test
    @DisplayName("a revision the live path already announced is never announced again, whichever runs first")
    void liveAndBackfillNeverBothAnnounce() throws Exception {
        String id = receivedWithAFreightBill("100.00");
        amendFreight(id, 1, "150.00");
        assertThat(payloads(id)).as("announced live").hasSize(2);

        assertThat(backfill.backfill()).isZero();

        assertThat(payloads(id)).hasSize(2);
    }

    @Test
    @DisplayName("a consignment still being drawn up has nothing to announce, and a received one with no bill passes with its revisions marked")
    void draftsAndBillFreeConsignmentsPass() throws Exception {
        beforePhaseTen();
        String received = receivedWithAFreightBill("0.00");
        afterPhaseTen();

        assertThat(backfill.backfill()).isZero();

        assertThat(payloads(received)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM period_cost_event WHERE consignment_id = ?::uuid AND NOT emitted", Long.class, received)).isEqualTo(1);
    }
}
