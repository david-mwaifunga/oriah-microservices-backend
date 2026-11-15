package com.oriah.order.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.CustomerClient;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What the packer reads (Phase 8, slice 8.4b): the work list and the packing slip. The packer needs who the parcel is for, where it goes and what is in it,
 * and must read NO MONEY (Q29): not the charge, the total, a discount, a unit price, the waiver, the shopper's login, nor who did what. The courier fare is
 * shown only to someone who may see cost. A slip is served only for an order that is paid and not finished, and the delivery block (a person's name, phone
 * and address) is withheld once the order is delivered, except to an order administrator.
 */
class DispatchReadTest extends SaleTestBase {

    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final List<String> MONEY_AND_LOGINS = List.of("total", "subtotal", "shippingAmount", "discountAmount", "unitPrice", "deliveryWaived", "keycloakId",
            "listAmount", "user:", "kc-alice", "kc-dave", "kc-maya", "paymentIntentId", "deliveryWaiver");

    private static RequestPostProcessor packer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT")).jwt(j -> j.subject("kc-dave"));
    }

    private static RequestPostProcessor packerWhoSeesCost() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT"), new SimpleGrantedAuthority("ROLE_COST_VIEWER")).jwt(j -> j.subject("kc-maya"));
    }

    private static RequestPostProcessor orderAdminPacker() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT"), new SimpleGrantedAuthority("ROLE_ORDER_ADMIN")).jwt(j -> j.subject("kc-maya"));
    }

    private UUID paidDelivery(String option, String payer, String charge, int quantity) throws Exception {
        SaleStubs.shippingDelivers(shipping, option, TOWN, payer, charge, "ZMW");
        org.mockito.Mockito.doReturn(List.of(new CustomerClient.Address(UUID.fromString(ADDRESS), "Plot 12 Great East Road", "Rhodes Park", "Lusaka",
                "Lusaka Province", "10101", "ZM"))).when(customer).myAddresses();
        String digest = QuoteDigest.of(new Quote(List.of(new QuoteLine(RING, quantity, new BigDecimal("4500.00"), new BigDecimal("0.00"), null)), "ZMW", option,
                new BigDecimal(charge), TOWN, payer));
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"" + option + "\",\"deliveryTownId\":\"" + TOWN
                + "\",\"payer\":\"" + payer + "\",\"recipientName\":\"Mulenga Chanda\",\"recipientPhone\":\"0971234567\",\"quoteDigest\":\"" + digest + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":" + quantity + ",\"unitPrice\":4500.00}]}";
        MvcResult placed = mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(201);
        UUID id = UUID.fromString(json.readTree(placed.getResponse().getContentAsString()).get("id").asText());
        confirmed(id, new BigDecimal("4500.00").multiply(BigDecimal.valueOf(quantity)).add(new BigDecimal(charge)).toPlainString());
        return id;
    }

    private UUID paidPickup() throws Exception {
        SaleStubs.shippingSelfCollect(shipping);
        UUID id = heldOrder();
        confirmed(id, "4500.00");
        return id;
    }

    private MvcResult read(RequestPostProcessor who, String path) throws Exception {
        return mvc.perform(get(path).with(who)).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void step(UUID id, String step, String body) throws Exception {
        MvcResult done = mvc.perform(post("/api/v1/admin/orders/" + id + "/dispatch/" + step).with(packerWhoSeesCost()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
        assertThat(done.getResponse().getStatus()).as(done.getResponse().getContentAsString()).isEqualTo(200);
    }

    private static void noMoneyOrLogins(String json) {
        for (String forbidden : MONEY_AND_LOGINS) {
            assertThat(json).as("the packer must not read '" + forbidden + "'").doesNotContain(forbidden);
        }
    }

    // ------------------------------------------------------------------ the work list

    @Test
    @DisplayName("the work list shows what waits to be packed, oldest payment first, with who it is for and where it goes -- and no money, no login, no actor")
    void theWorkList() throws Exception {
        UUID second = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00", 2);
        UUID first = paidDelivery("OWN_DELIVERY", "ORIAH_COLLECTS", "125.00", 1);
        UUID pickup = paidPickup();
        jdbc.update("UPDATE orders SET paid_at = '2026-11-01T08:00:00Z' WHERE id = ?", first);
        jdbc.update("UPDATE orders SET paid_at = '2026-11-02T08:00:00Z' WHERE id = ?", second);
        jdbc.update("UPDATE orders SET paid_at = '2026-11-03T08:00:00Z' WHERE id = ?", pickup);

        MvcResult result = read(packer(), "/api/v1/admin/orders/dispatch/queue");

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode queue = body(result);
        assertThat(queue.get("stage").asText()).isEqualTo("TO_PACK");
        assertThat(queue.get("count").asInt()).isEqualTo(3);
        List<String> ids = new java.util.ArrayList<>();
        queue.get("orders").forEach(o -> ids.add(o.get("orderId").asText()));
        assertThat(ids).containsExactly(first.toString(), second.toString(), pickup.toString());
        JsonNode row = queue.get("orders").get(0);
        assertThat(row.get("receiptNumber").asText()).matches("OR-\\d{6}");
        assertThat(row.get("status").asText()).isEqualTo("PAID");
        assertThat(row.get("stage").asText()).isEqualTo("TO_PACK");
        assertThat(row.get("optionName").asText()).isEqualTo("Oriah delivery");
        assertThat(row.get("payer").asText()).isEqualTo("ORIAH_COLLECTS");
        assertThat(row.get("townName").asText()).isEqualTo("Kabwe");
        assertThat(row.get("recipientName").asText()).isEqualTo("Mulenga Chanda");
        assertThat(row.get("pieces").asInt()).isEqualTo(1);
        assertThat(queue.get("orders").get(1).get("pieces").asInt()).isEqualTo(2);
        assertThat(row.get("paidAt").asText()).isEqualTo("2026-11-01T08:00:00Z");
        assertThat(row.get("packable").asBoolean()).isTrue();
        assertThat(queue.get("orders").get(2).has("recipientName")).as("a pickup has no recipient").isFalse();
        noMoneyOrLogins(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("the stages: PACKED lists what is packed, ON_THE_WAY what is shipped; delivered and unpaid orders are on no list; a stage that is not one is 400")
    void theStages() throws Exception {
        UUID waiting = paidDelivery("PLATINUM", "RECIPIENT_PAYS", "0.00", 1);
        UUID packed = paidDelivery("PLATINUM", "RECIPIENT_PAYS", "0.00", 2);
        UUID onItsWay = paidDelivery("PLATINUM", "RECIPIENT_PAYS", "0.00", 3);
        UUID arrived = paidDelivery("PLATINUM", "RECIPIENT_PAYS", "0.00", 4);
        UUID unpaid = heldOrder(5);
        step(packed, "pack", "{}");
        step(onItsWay, "pack", "{}");
        step(onItsWay, "ship", "{}");
        step(arrived, "pack", "{}");
        step(arrived, "ship", "{}");
        step(arrived, "deliver", "{}");

        assertThat(idsOf(read(packer(), "/api/v1/admin/orders/dispatch/queue"))).containsExactly(waiting);
        assertThat(idsOf(read(packer(), "/api/v1/admin/orders/dispatch/queue?stage=TO_PACK"))).containsExactly(waiting);
        assertThat(idsOf(read(packer(), "/api/v1/admin/orders/dispatch/queue?stage=PACKED"))).containsExactly(packed);
        assertThat(idsOf(read(packer(), "/api/v1/admin/orders/dispatch/queue?stage=ON_THE_WAY"))).containsExactly(onItsWay);
        assertThat(read(packer(), "/api/v1/admin/orders/dispatch/queue?stage=EVERYTHING").getResponse().getStatus()).isEqualTo(400);
        assertThat(read(packer(), "/api/v1/admin/orders/dispatch/queue?stage=PACKED").getResponse().getContentAsString()).doesNotContain(unpaid.toString());
    }

    private List<UUID> idsOf(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        List<UUID> ids = new java.util.ArrayList<>();
        body(result).get("orders").forEach(o -> ids.add(UUID.fromString(o.get("orderId").asText())));
        return ids;
    }

    @Test
    @DisplayName("an order placed before Phase 8, with no recipient or address kept, is on the list but marked not packable")
    void aLegacyOrderIsNotPackable() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00", 1);
        jdbc.update("UPDATE orders SET recipient_name = NULL, recipient_phone = NULL WHERE id = ?", id);

        JsonNode row = body(read(packer(), "/api/v1/admin/orders/dispatch/queue")).get("orders").get(0);

        assertThat(row.get("orderId").asText()).isEqualTo(id.toString());
        assertThat(row.get("packable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("only FULFILMENT_AGENT reads the list and the slip: a shopper, a sales person and an order administrator alone are 403")
    void whoMayRead() throws Exception {
        UUID id = paidPickup();
        for (String path : new String[] {"/api/v1/admin/orders/dispatch/queue", "/api/v1/admin/orders/" + id + "/dispatch"}) {
            assertThat(read(alice(), path).getResponse().getStatus()).as(path).isEqualTo(403);
            assertThat(read(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")), path).getResponse().getStatus()).as(path).isEqualTo(403);
            assertThat(read(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN")), path).getResponse().getStatus()).as(path).isEqualTo(403);
            assertThat(read(packer(), path).getResponse().getStatus()).as(path).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------------ the slip

    @Test
    @DisplayName("the slip of a courier order: who, where, by what, what is in the parcel and whether a fare is due -- and no money, no login, no actor")
    void theSlip() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00", 2);

        MvcResult result = read(packer(), "/api/v1/admin/orders/" + id + "/dispatch");

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode slip = body(result);
        assertThat(slip.get("orderId").asText()).isEqualTo(id.toString());
        assertThat(slip.get("receiptNumber").asText()).matches("OR-\\d{6}");
        assertThat(slip.get("status").asText()).isEqualTo("PAID");
        assertThat(slip.get("stage").asText()).isEqualTo("TO_PACK");
        assertThat(slip.get("selfCollect").asBoolean()).isFalse();
        assertThat(slip.get("fareDue").asBoolean()).isTrue();
        assertThat(slip.get("boxSwapInProgress").asBoolean()).isFalse();
        JsonNode delivery = slip.get("delivery");
        assertThat(delivery.get("optionCode").asText()).isEqualTo("YANGO");
        assertThat(delivery.get("optionName").asText()).isEqualTo("Oriah delivery");
        assertThat(delivery.get("payer").asText()).isEqualTo("ORIAH_COLLECTS");
        assertThat(delivery.get("townName").asText()).isEqualTo("Kabwe");
        assertThat(delivery.get("recipientName").asText()).isEqualTo("Mulenga Chanda");
        assertThat(delivery.get("recipientPhone").asText()).isEqualTo("+260971234567");
        assertThat(delivery.get("address").get("line1").asText()).isEqualTo("Plot 12 Great East Road");
        assertThat(delivery.get("address").get("line2").asText()).isEqualTo("Rhodes Park");
        assertThat(delivery.get("address").get("city").asText()).isEqualTo("Lusaka");
        assertThat(delivery.get("address").get("country").asText()).isEqualTo("ZM");
        assertThat(slip.get("lines")).hasSize(1);
        JsonNode line = slip.get("lines").get(0);
        assertThat(line.get("quantity").asInt()).isEqualTo(2);
        assertThat(line.get("title").asText()).isNotBlank();
        assertThat(line.get("lineId").asText()).isNotBlank();
        assertThat(line.get("box").get("variantId").asText()).isEqualTo(SaleStubs.BOX_VARIANT.toString());
        assertThat(slip.has("farePaid")).isFalse();
        noMoneyOrLogins(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("the slip of a pickup has no delivery block, says it is collected, and has no fare due")
    void thePickupSlip() throws Exception {
        UUID id = paidPickup();

        JsonNode slip = body(read(packer(), "/api/v1/admin/orders/" + id + "/dispatch"));

        assertThat(slip.get("selfCollect").asBoolean()).isTrue();
        assertThat(slip.get("fareDue").asBoolean()).isFalse();
        assertThat(slip.has("delivery")).isFalse();
    }

    @Test
    @DisplayName("after packing and dispatch the slip shows when and the courier's reference; the fare only to someone who may see cost")
    void theSlipAfterTheSteps() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00", 1);
        step(id, "pack", "{}");
        step(id, "ship", "{\"farePaid\":100,\"reference\":\"WB-4411\"}");

        JsonNode asPacker = body(read(packer(), "/api/v1/admin/orders/" + id + "/dispatch"));
        JsonNode asCost = body(read(packerWhoSeesCost(), "/api/v1/admin/orders/" + id + "/dispatch"));

        assertThat(asPacker.get("stage").asText()).isEqualTo("ON_THE_WAY");
        assertThat(asPacker.get("packedAt").asText()).isNotBlank();
        assertThat(asPacker.get("dispatchedAt").asText()).isNotBlank();
        assertThat(asPacker.get("reference").asText()).isEqualTo("WB-4411");
        assertThat(asPacker.has("farePaid")).isFalse();
        assertThat(asCost.get("farePaid").decimalValue()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a slip is refused before anything personal is read for an order that is not paid or was cancelled (409 order-not-paid); an unknown order is 404")
    void aSlipNeedsAPaidOrder() throws Exception {
        UUID unpaid = heldOrder();
        UUID cancelled = heldOrder(2);
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelled);

        for (UUID id : new UUID[] {unpaid, cancelled}) {
            MvcResult refused = read(packer(), "/api/v1/admin/orders/" + id + "/dispatch");
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused).get("type").asText()).endsWith("/order-not-paid");
        }
        assertThat(read(packer(), "/api/v1/admin/orders/" + UUID.randomUUID() + "/dispatch").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("once an order is delivered its slip no longer shows the person's name, phone and address, except to an order administrator")
    void aDeliveredSlipWithholdsThePerson() throws Exception {
        UUID id = paidDelivery("PLATINUM", "RECIPIENT_PAYS", "0.00", 1);
        step(id, "pack", "{}");
        step(id, "ship", "{}");

        assertThat(body(read(packer(), "/api/v1/admin/orders/" + id + "/dispatch")).has("delivery")).as("still on its way").isTrue();
        step(id, "deliver", "{}");

        MvcResult asPacker = read(packer(), "/api/v1/admin/orders/" + id + "/dispatch");
        assertThat(asPacker.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(asPacker).has("delivery")).isFalse();
        assertThat(body(asPacker).get("deliveredAt").asText()).isNotBlank();
        assertThat(asPacker.getResponse().getContentAsString()).doesNotContain("Mulenga").doesNotContain("971234567").doesNotContain("Great East Road");
        assertThat(body(read(orderAdminPacker(), "/api/v1/admin/orders/" + id + "/dispatch")).get("delivery").get("recipientName").asText()).isEqualTo("Mulenga Chanda");
    }

    @Test
    @DisplayName("a slip says when a box swap is still with inventory, so the person knows the box may change")
    void theSlipSaysASwapIsInFlight() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00", 1);
        UUID line = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", UUID.class, id);
        jdbc.update("INSERT INTO order_box_swap_inflight (order_id, amendment_id, item_id, new_box_variant_id, reason, requested_by, started_at) "
                + "VALUES (?, ?, ?, ?, 'a swap that was never finished', 'user:kc-maya', now())", id, UUID.randomUUID(), line, UUID.randomUUID());

        assertThat(body(read(packer(), "/api/v1/admin/orders/" + id + "/dispatch")).get("boxSwapInProgress").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ what the shopper and staff read of an order

    @Test
    @DisplayName("an order's own answer carries its receipt number, the day it was paid, who it is for and where it goes, and the dispatch dates and reference -- never the fare")
    void anOrderReadsItsDelivery() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00", 1);
        step(id, "pack", "{}");
        step(id, "ship", "{\"farePaid\":100,\"reference\":\"WB-4411\"}");

        MvcResult mine = mvc.perform(get("/api/v1/orders/" + id).with(alice())).andReturn();
        MvcResult staff = mvc.perform(get("/api/v1/admin/orders/" + id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN"))
                .jwt(j -> j.subject("kc-maya")))).andReturn();

        for (MvcResult result : new MvcResult[] {mine, staff}) {
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
            JsonNode order = body(result);
            assertThat(order.get("receiptNumber").asText()).matches("OR-\\d{6}");
            assertThat(Instant.parse(order.get("paidAt").asText())).isEqualTo(Instant.parse(ARRIVED));
            assertThat(order.get("delivery").get("recipientName").asText()).isEqualTo("Mulenga Chanda");
            assertThat(order.get("delivery").get("recipientPhone").asText()).isEqualTo("+260971234567");
            assertThat(order.get("delivery").get("address").get("line1").asText()).isEqualTo("Plot 12 Great East Road");
            assertThat(order.get("delivery").get("optionName").asText()).isEqualTo("Oriah delivery");
            assertThat(order.get("delivery").get("townName").asText()).isEqualTo("Kabwe");
            assertThat(order.get("dispatch").get("packedAt").asText()).isNotBlank();
            assertThat(order.get("dispatch").get("dispatchedAt").asText()).isNotBlank();
            assertThat(order.get("dispatch").get("reference").asText()).isEqualTo("WB-4411");
            assertThat(order.get("dispatch").has("deliveredAt")).isFalse();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("farePaid").doesNotContain("fare");
        }
    }

    @Test
    @DisplayName("an order that is not delivered or not dispatched has no delivery or dispatch block to show")
    void aPickupHasNoDeliveryBlock() throws Exception {
        UUID id = paidPickup();

        JsonNode order = body(mvc.perform(get("/api/v1/orders/" + id).with(alice())).andReturn());

        assertThat(order.has("delivery")).isFalse();
        assertThat(order.has("dispatch")).isFalse();
        assertThat(order.get("receiptNumber").asText()).matches("OR-\\d{6}");
    }
}
