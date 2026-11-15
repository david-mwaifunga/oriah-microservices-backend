package com.oriah.order.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Free delivery for one order, for a chosen customer (CLIENT_DECISIONS Q2, Phase 7's design 3.4): a member of staff holding {@code DELIVERY_WAIVER} sets the
 * delivery of an order that is still waiting for a payment reference to K0, with a reason, and the order's total falls by exactly that. It records who, why,
 * when and how much was given up (delivery revenue, never goods revenue), changes nothing about the hold, and is refused once a payment is claimed, being
 * applied or made: a total the shopper may already have paid cannot move.
 */
class DeliveryWaiverTest extends SaleTestBase {

    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String REASON = "a regular customer, free delivery as a thank-you";

    private static RequestPostProcessor waiver() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_DELIVERY_WAIVER")).jwt(j -> j.subject("kc-maya"));
    }

    private static RequestPostProcessor justSales() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"), new SimpleGrantedAuthority("ROLE_ORDER_ADMIN"))
                .jwt(j -> j.subject("kc-sam"));
    }

    /** One ring delivered to TOWN by Oriah's own driver at K125.00, held: K4,625.00. */
    private UUID deliveredOrder() throws Exception {
        return deliveredOrder(1);
    }

    /** {@code quantity} rings delivered at K125.00, held: K4,500.00 a ring plus the delivery. A different quantity is a different bag, so a different order. */
    private UUID deliveredOrder(int quantity) throws Exception {
        SaleStubs.shippingDelivers(shipping, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS", "125.00", "ZMW");
        String digest = QuoteDigest.of(new Quote(List.of(new QuoteLine(RING, quantity, new BigDecimal("4500.00"), new BigDecimal("0.00"), null)), "ZMW",
                "OWN_DELIVERY", new BigDecimal("125.00"), TOWN, "ORIAH_COLLECTS"));
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"OWN_DELIVERY\",\"deliveryTownId\":\"" + TOWN
                + "\",\"payer\":\"ORIAH_COLLECTS\",\"recipientName\":\"Mulenga Chanda\",\"recipientPhone\":\"+260971234567\",\"quoteDigest\":\"" + digest + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":" + quantity + ",\"unitPrice\":4500.00}]}";
        MvcResult placed = mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(201);
        UUID id = UUID.fromString(json.readTree(placed.getResponse().getContentAsString()).get("id").asText());
        assertThat(row(id).get("total")).isEqualTo(new BigDecimal(quantity * 4500 + 125 + ".00"));
        return id;
    }

    private MvcResult waive(RequestPostProcessor who, UUID orderId, String reason) throws Exception {
        return mvc.perform(post("/api/v1/admin/orders/" + orderId + "/delivery-waiver").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}")).andReturn();
    }

    private String problemType(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("type").asText();
    }

    // ------------------------------------------------------------------ the waiver

    @Test
    @DisplayName("a member of staff with DELIVERY_WAIVER makes delivery free on a waiting order: delivery K0, the total falls by exactly it, and who, why, when and how much are kept")
    void deliveryIsWaived() throws Exception {
        UUID id = deliveredOrder();

        MvcResult result = waive(waiver(), id, REASON);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("shippingAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(body.get("total").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(body.get("deliveryWaivedAmount").decimalValue()).as("delivery revenue given up").isEqualByComparingTo("125.00");
        assertThat(body.get("deliveryWaivedBy").asText()).isEqualTo("user:kc-maya");
        assertThat(body.get("deliveryWaiverReason").asText()).isEqualTo(REASON);
        assertThat(body.get("deliveryWaivedAt").asText()).isNotBlank();
        Map<String, Object> row = row(id);
        assertThat(row.get("shipping_amount")).isEqualTo(new BigDecimal("0.00"));
        assertThat(row.get("total")).isEqualTo(new BigDecimal("4500.00"));
        assertThat(row.get("delivery_waived_amount")).isEqualTo(new BigDecimal("125.00"));
        assertThat(row.get("status")).as("still waiting for money, hold untouched").isEqualTo("CREATED");
        assertThat(row.get("hold_state")).isEqualTo("HELD");
    }

    @Test
    @DisplayName("the lower total is what staff must now confirm: K4,500.00 pays the order, and the old K4,625.00 is a wrong amount held for the customer")
    void theNewTotalIsWhatPaysTheOrder() throws Exception {
        UUID id = deliveredOrder();
        waive(waiver(), id, REASON);

        UUID old = confirmed(id, "4625.00");
        assertThat(settlement(old).get("reason")).isEqualTo("WRONG_AMOUNT");
        assertThat(row(id).get("status")).isEqualTo("CREATED");

        confirmed(id, "4500.00");
        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("the shopper's own order shows the lower total and the amount saved but never who waived delivery or why (the claim's answer, the order, the list); staff's reads show all of it")
    void whoAndWhyAreForStaffOnly() throws Exception {
        UUID id = deliveredOrder();
        waive(waiver(), id, REASON);

        String order = mvc.perform(get("/api/v1/orders/" + id).with(alice())).andReturn().getResponse().getContentAsString();
        String mine = mvc.perform(get("/api/v1/orders/me").with(alice())).andReturn().getResponse().getContentAsString();
        for (String shopperSees : new String[] {order, mine}) {
            assertThat(shopperSees).doesNotContain(REASON).doesNotContain("kc-maya").doesNotContain("deliveryWaivedAt\":\"");
        }
        JsonNode theOrder = json.readTree(order);
        assertThat(theOrder.get("total").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(theOrder.get("deliveryWaivedAmount").decimalValue()).as("what the shopper saved").isEqualByComparingTo("125.00");
        assertThat(theOrder.path("deliveryWaivedBy").isNull()).isTrue();
        assertThat(theOrder.path("deliveryWaiverReason").isNull()).isTrue();
        assertThat(theOrder.path("deliveryWaivedAt").isNull()).isTrue();

        MvcResult claimed = mvc.perform(post("/api/v1/orders/" + id + "/payment-claim").with(alice()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"MTN\",\"reference\":\"MP261114\"}")).andReturn();
        assertThat(claimed.getResponse().getStatus()).isEqualTo(200);
        assertThat(claimed.getResponse().getContentAsString()).doesNotContain(REASON).doesNotContain("kc-maya");

        JsonNode staff = json.readTree(mvc.perform(get("/api/v1/admin/orders/" + id).with(justSales())).andReturn().getResponse().getContentAsString());
        assertThat(staff.get("deliveryWaivedBy").asText()).isEqualTo("user:kc-maya");
        assertThat(staff.get("deliveryWaiverReason").asText()).isEqualTo(REASON);
        assertThat(staff.get("deliveryWaivedAt").asText()).isNotBlank();
        assertThat(mvc.perform(get("/api/v1/admin/orders").with(justSales())).andReturn().getResponse().getContentAsString())
                .as("the staff list").contains(REASON);
    }

    // ------------------------------------------------------------------ who may

    @Test
    @DisplayName("only DELIVERY_WAIVER may: a shopper and staff without it (sales, order administration) are refused (403) and nothing changes")
    void whoMayWaive() throws Exception {
        UUID id = deliveredOrder();

        assertThat(waive(alice(), id, REASON).getResponse().getStatus()).isEqualTo(403);
        assertThat(waive(justSales(), id, REASON).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post("/api/v1/admin/orders/" + id + "/delivery-waiver").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + REASON + "\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(row(id).get("total")).isEqualTo(new BigDecimal("4625.00"));
        assertThat(row(id).get("delivery_waived_amount")).isNull();
    }

    // ------------------------------------------------------------------ when

    @Test
    @DisplayName("a payment already claimed, being applied, or made cannot have its total moved: 409 delivery-waiver-not-allowed / payment-being-applied")
    void onlyAWaitingOrder() throws Exception {
        UUID claimed = claimedDeliveredOrder();
        MvcResult afterClaim = waive(waiver(), claimed, REASON);
        assertThat(afterClaim.getResponse().getStatus()).isEqualTo(409);
        assertThat(problemType(afterClaim)).endsWith("/delivery-waiver-not-allowed");
        assertThat(row(claimed).get("total")).isEqualTo(new BigDecimal("4625.00"));

        UUID paid = deliveredOrder(2);
        confirmed(paid, "9125.00");
        assertThat(row(paid).get("status")).isEqualTo("PAID");
        MvcResult afterPaid = waive(waiver(), paid, REASON);
        assertThat(afterPaid.getResponse().getStatus()).isEqualTo(409);
        assertThat(problemType(afterPaid)).endsWith("/delivery-waiver-not-allowed");

        UUID settling = deliveredOrder(3);
        jdbc.update("UPDATE orders SET settling_confirmation_id = gen_random_uuid() WHERE id = ?", settling);
        MvcResult whileSettling = waive(waiver(), settling, REASON);
        assertThat(whileSettling.getResponse().getStatus()).isEqualTo(409);
        assertThat(problemType(whileSettling)).endsWith("/payment-being-applied");
    }

    @Test
    @DisplayName("an order that is still being placed (PENDING) has nothing the shopper was told: its delivery cannot be waived yet")
    void notWhileBeingPlaced() throws Exception {
        UUID id = deliveredOrder();
        jdbc.update("UPDATE orders SET hold_state = 'PENDING' WHERE id = ?", id);

        MvcResult result = waive(waiver(), id, REASON);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(problemType(result)).endsWith("/delivery-waiver-not-allowed");
    }

    // ------------------------------------------------------------------ what there is to waive

    @Test
    @DisplayName("an order with no delivery to waive (a pickup) is 409 no-delivery-to-waive, and a second waiver is 409 delivery-already-waived: one waiver, once")
    void oneWaiverOfWhatThereIs() throws Exception {
        UUID pickup = heldOrder();
        MvcResult nothing = waive(waiver(), pickup, REASON);
        assertThat(nothing.getResponse().getStatus()).isEqualTo(409);
        assertThat(problemType(nothing)).endsWith("/no-delivery-to-waive");

        UUID delivered = deliveredOrder();
        assertThat(waive(waiver(), delivered, REASON).getResponse().getStatus()).isEqualTo(200);
        MvcResult again = waive(waiver(), delivered, REASON);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(problemType(again)).endsWith("/delivery-already-waived");
        assertThat(row(delivered).get("delivery_waived_amount")).isEqualTo(new BigDecimal("125.00"));
    }

    @Test
    @DisplayName("an unknown order is 404; a reason is required, 5 to 200 characters after trimming: a short or padded one is 400 and nothing changes")
    void theReasonAndTheOrder() throws Exception {
        assertThat(waive(waiver(), UUID.randomUUID(), REASON).getResponse().getStatus()).isEqualTo(404);

        UUID id = deliveredOrder();
        assertThat(waive(waiver(), id, "no").getResponse().getStatus()).isEqualTo(400);
        assertThat(waive(waiver(), id, "ok" + " ".repeat(10)).getResponse().getStatus()).as("padding does not make a short reason long enough").isEqualTo(400);
        assertThat(waive(waiver(), id, "x".repeat(201)).getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post("/api/v1/admin/orders/" + id + "/delivery-waiver").with(waiver()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(row(id).get("total")).isEqualTo(new BigDecimal("4625.00"));
    }

    @Test
    @DisplayName("the database holds the record whole: a waiver amount without who, why and when (or the other way round) is refused")
    void theRecordIsWhole() throws Exception {
        UUID id = deliveredOrder();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("UPDATE orders SET delivery_waived_amount = 1.00 WHERE id = ?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private UUID claimedDeliveredOrder() throws Exception {
        UUID id = deliveredOrder();
        MvcResult claimed = mvc.perform(post("/api/v1/orders/" + id + "/payment-claim").with(alice()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"MTN\",\"reference\":\"MP261113\"}")).andReturn();
        assertThat(claimed.getResponse().getStatus()).as(claimed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(row(id).get("status")).isEqualTo("PAYMENT_SUBMITTED");
        return id;
    }
}
