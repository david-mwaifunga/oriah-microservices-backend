package com.oriah.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.QuoteFixtures;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.client.InventoryClient;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What the tests of a return share (Phase 12): orders that have been handed over or sent, and inventory's answers to a reversal. The plan's example
 * (5.4) is the order most of them use: two rings at K100.00, K10.00 off each by a promotion, collected and paid in cash: K180.00.
 */
abstract class ReturnTestBase extends SaleTestBase {

    static final UUID TOWN = UUID.fromString("5e6f7a8b-1c2d-4e3f-8a9b-0c1d2e3f4a5b");
    static final UUID PROMOTION = UUID.fromString("4b6d8f0a-2e4a-4b6d-8f0a-ac2e4a6c8da3");

    static RequestPostProcessor manager() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN")).jwt(j -> j.subject("kc-maya").tokenValue("maya-token"));
    }

    /** The owner: an order administrator who may also back-date, write stock off and manage the catalogue. */
    static RequestPostProcessor owner() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN"), new SimpleGrantedAuthority("ROLE_BACKDATE_SALE"),
                new SimpleGrantedAuthority("ROLE_STOCK_WRITE_OFF"), new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN")).jwt(j -> j.subject("kc-olive").tokenValue("olive-token"));
    }

    static RequestPostProcessor packer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT"), new SimpleGrantedAuthority("ROLE_BACKDATE_SALE")).jwt(j -> j.subject("kc-dave"));
    }

    /**
     * A correction is a record: it is never deleted, so one that an earlier test left in flight is still there. Each test starts with none in flight
     * (they are ended the way a proven refusal ends them), so a pass of the job touches only what the test itself made.
     */
    @BeforeEach
    void nothingInFlight() {
        jdbc.update("UPDATE order_correction SET state = 'REFUSED', refused_code = 'left-by-an-earlier-test', next_attempt_at = NULL WHERE state = 'REQUESTED'");
        jdbc.update("""
                UPDATE order_return_piece SET state = 'AWAITING', outcome = NULL, markdown_variant_id = NULL, note = NULL, asked_by = NULL, asked_at = NULL,
                    attempts = 0, next_attempt_at = NULL WHERE state = 'IN_PROGRESS'""");
    }

    // ------------------------------------------------------------------ orders

    MvcResult step(UUID id, String step, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/orders/" + id + "/dispatch/" + step).with(packer()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    void stepped(UUID id, String step, String body) throws Exception {
        MvcResult result = step(id, step, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
    }

    /** {@code quantity} rings at K100.00, K10.00 off each by a promotion, to be collected: paid in full (K90.00 a ring). */
    UUID paidRings(int quantity) throws Exception {
        SaleStubs.shippingSelfCollect(shipping);
        doReturn(new CatalogPricingClient.PricingResponse(List.of(new CatalogPricingClient.Entry(RING, "PRICED", PRODUCT, "Aurelia 18k",
                "Aurelia 18k / Ring Size M", new BigDecimal("100.00"), "ZMW", new BigDecimal("10.00"), PROMOTION, new BigDecimal("90.00"))))).when(catalog).price(any());
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\","
                + "\"quoteDigest\":\"" + QuoteFixtures.selfCollect("ZMW", QuoteFixtures.line(RING, quantity, "100.00", "10.00", PROMOTION)) + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":" + quantity + ",\"unitPrice\":100.00}]}";
        MvcResult placed = mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(201);
        UUID id = UUID.fromString(json.readTree(placed.getResponse().getContentAsString()).get("id").asText());
        confirmed(id, new BigDecimal("90.00").multiply(BigDecimal.valueOf(quantity)).toPlainString());
        assertThat(row(id).get("status")).isEqualTo("PAID");
        return id;
    }

    /** The plan's example: {@code quantity} discounted rings, paid, packed and handed over to the shopper just now. */
    UUID collectedRings(int quantity) throws Exception {
        UUID id = paidRings(quantity);
        stepped(id, "pack", "{}");
        stepped(id, "deliver", "{}");
        return id;
    }

    /** One ring at K4,500.00 sent by Oriah's own delivery for K45.00, paid: packed and dispatched just now (SHIPPED). */
    UUID shippedRing() throws Exception {
        SaleStubs.shippingDelivers(shipping, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS", "45.00", "ZMW");
        doReturn(List.of(new com.oriah.order.client.CustomerClient.Address(UUID.fromString(ADDRESS), "Plot 12 Great East Road", null, "Lusaka",
                "Lusaka Province", "10101", "ZM"))).when(customer).myAddresses();
        String digest = QuoteDigest.of(new Quote(List.of(new QuoteLine(RING, 1, new BigDecimal("4500.00"), new BigDecimal("0.00"), null)), "ZMW", "OWN_DELIVERY",
                new BigDecimal("45.00"), TOWN, "ORIAH_COLLECTS"));
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"OWN_DELIVERY\",\"deliveryTownId\":\"" + TOWN
                + "\",\"payer\":\"ORIAH_COLLECTS\",\"recipientName\":\"Mulenga Chanda\",\"recipientPhone\":\"0971234567\",\"quoteDigest\":\"" + digest + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":1,\"unitPrice\":4500.00}]}";
        MvcResult placed = mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(201);
        UUID id = UUID.fromString(json.readTree(placed.getResponse().getContentAsString()).get("id").asText());
        confirmed(id, "4545.00");
        stepped(id, "pack", "{}");
        stepped(id, "ship", "{}");
        assertThat(row(id).get("status")).isEqualTo("SHIPPED");
        SaleStubs.shippingSelfCollect(shipping);
        return id;
    }

    UUID lineOf(UUID orderId) {
        return jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", UUID.class, orderId);
    }

    // ------------------------------------------------------------------ returns

    static String lines(UUID line, int quantity) {
        return "\"lines\":[{\"lineId\":\"" + line + "\",\"quantity\":" + quantity + "}]";
    }

    MvcResult recordReturn(RequestPostProcessor who, UUID orderId, String key, String body) throws Exception {
        var request = post("/api/v1/admin/orders/" + orderId + "/returns").with(who).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    /** A return of {@code quantity} pieces of the order's one line, because the customer changed their mind: recorded and done. */
    JsonNode returned(UUID orderId, int quantity) throws Exception {
        return body(recordReturn(manager(), orderId, "k-" + UUID.randomUUID(),
                "{\"reasonCode\":\"CHANGED_MIND\",\"confirmDuplicate\":true," + lines(lineOf(orderId), quantity) + "}"), 201);
    }

    JsonNode body(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsString());
    }

    String type(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("type").asText();
    }

    List<Map<String, Object>> corrections(UUID orderId) {
        return jdbc.queryForList("SELECT * FROM order_correction WHERE order_id = ? ORDER BY recorded_at", orderId);
    }

    List<Map<String, Object>> pieces(UUID correctionId) {
        return jdbc.queryForList("SELECT * FROM order_return_piece WHERE correction_id = ? ORDER BY sale_line_id, piece_no", correctionId);
    }

    void due(UUID correctionId) {
        jdbc.update("UPDATE order_correction SET next_attempt_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), correctionId);
    }

    // ------------------------------------------------------------------ inventory

    /** Inventory putting back whatever it is asked to. */
    void inventoryReverses() {
        doAnswer(inv -> {
            InventoryClient.ReversalAsk ask = inv.getArgument(0);
            return new InventoryClient.Reversal(ask.reversalId(), ask.kind(), ask.orderId(), ask.occurredAt(), Instant.now(), false);
        }).when(inventory).reverseSale(any());
    }

    static String problem(int status, String type) {
        return type == null ? null
                : "{\"type\":\"https://oriah.dev/problems/" + type + "\",\"title\":\"x\",\"status\":" + status + ",\"detail\":\"inventory said " + type + "\"}";
    }

    void inventoryAnswersTheReversal(int status, String type) {
        doThrow(SaleStubs.feign(status, problem(status, type))).when(inventory).reverseSale(any());
    }

    void inventoryHasTheReversal() {
        doAnswer(inv -> new InventoryClient.Reversal(inv.getArgument(0), "RETURN", UUID.randomUUID(), Instant.now(), Instant.now(), false))
                .when(inventory).reversal(any());
    }
}
