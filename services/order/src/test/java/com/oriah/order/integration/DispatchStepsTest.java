package com.oriah.order.integration;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Packing, sending and handing over a paid order (Phase 8, slice 8.4a): the dispatch record, which is the line for refunds (before it delivery is refunded,
 * from it goods only) and the source of the courier fare finance books.
 *
 * <p>Three steps, each recorded once with its true date: PACKED (the order becomes FULFILLED), DISPATCHED (SHIPPED: the courier is engaged and the parcel is
 * handed over, with the fare Oriah pays when it pays one) and DELIVERED. A self-collect order is packed and handed over (FULFILLED to DELIVERED), never
 * shipped. The delivery CHARGE is never touched (Q24): the fare is a separate fact about the dispatch.
 */
class DispatchStepsTest extends SaleTestBase {

    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static RequestPostProcessor packer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT")).jwt(j -> j.subject("kc-dave"));
    }

    private static RequestPostProcessor packerWhoSeesCost() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT"), new SimpleGrantedAuthority("ROLE_COST_VIEWER")).jwt(j -> j.subject("kc-maya"));
    }

    private static RequestPostProcessor packerWhoMayBackdate() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT"), new SimpleGrantedAuthority("ROLE_BACKDATE_SALE")).jwt(j -> j.subject("kc-maya"));
    }

    /** A paid order for one ring, delivered by {@code option} to TOWN with {@code payer} paying the courier, whose charge is {@code charge}. */
    private UUID paidDelivery(String option, String payer, String charge) throws Exception {
        SaleStubs.shippingDelivers(shipping, option, TOWN, payer, charge, "ZMW");
        org.mockito.Mockito.doReturn(List.of(new com.oriah.order.client.CustomerClient.Address(UUID.fromString(ADDRESS), "Plot 12 Great East Road", null, "Lusaka",
                "Lusaka Province", "10101", "ZM"))).when(customer).myAddresses();
        String digest = QuoteDigest.of(new Quote(List.of(new QuoteLine(RING, 1, new BigDecimal("4500.00"), new BigDecimal("0.00"), null)), "ZMW", option,
                new BigDecimal(charge), TOWN, payer));
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"" + option + "\",\"deliveryTownId\":\"" + TOWN
                + "\",\"payer\":\"" + payer + "\",\"recipientName\":\"Mulenga Chanda\",\"recipientPhone\":\"0971234567\",\"quoteDigest\":\"" + digest + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":1,\"unitPrice\":4500.00}]}";
        MvcResult placed = mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(201);
        UUID id = UUID.fromString(json.readTree(placed.getResponse().getContentAsString()).get("id").asText());
        confirmed(id, new BigDecimal("4500.00").add(new BigDecimal(charge)).toPlainString());
        assertThat(row(id).get("status")).isEqualTo("PAID");
        return id;
    }

    private UUID paidPickup() throws Exception {
        UUID id = heldOrder();
        confirmed(id, "4500.00");
        assertThat(row(id).get("status")).isEqualTo("PAID");
        return id;
    }

    private MvcResult step(RequestPostProcessor who, UUID id, String step, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/orders/" + id + "/dispatch/" + step).with(who).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult step(UUID id, String step, String body) throws Exception {
        return step(packer(), id, step, body);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String type(MvcResult result) throws Exception {
        return json(result).get("type").asText();
    }

    private List<Map<String, Object>> steps(UUID id) {
        return jdbc.queryForList("SELECT * FROM order_dispatch_step WHERE order_id = ? ORDER BY occurred_at, step", id);
    }

    private Map<String, Object> stepRow(UUID id, String step) {
        return jdbc.queryForMap("SELECT * FROM order_dispatch_step WHERE order_id = ? AND step = ?", id, step);
    }

    private static String iso(Instant at) {
        return at.truncatedTo(ChronoUnit.MICROS).toString();
    }

    // ------------------------------------------------------------------ a pickup: packed and handed over, never shipped

    @Test
    @DisplayName("a pickup is packed (FULFILLED) and handed over (DELIVERED): two steps, two events, no SHIPPED, and shipping it is refused")
    void aPickupIsPackedAndHandedOver() throws Exception {
        UUID id = paidPickup();

        MvcResult packed = step(id, "pack", "{}");
        assertThat(packed.getResponse().getStatus()).as(packed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(packed).get("status").asText()).isEqualTo("FULFILLED");
        assertThat(json(packed).get("stage").asText()).isEqualTo("PACKED");
        assertThat(json(packed).get("replay").asBoolean()).isFalse();

        MvcResult shipped = step(id, "ship", "{}");
        assertThat(shipped.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(shipped)).endsWith("/dispatch-step-not-allowed");

        MvcResult handedOver = step(id, "deliver", "{}");
        assertThat(handedOver.getResponse().getStatus()).as(handedOver.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(handedOver).get("status").asText()).isEqualTo("DELIVERED");
        assertThat(row(id).get("status")).isEqualTo("DELIVERED");

        assertThat(steps(id)).extracting(r -> r.get("step")).containsExactly("PACKED", "DELIVERED");
        assertThat(stepRow(id, "PACKED").get("recorded_by")).isEqualTo("user:kc-dave");
        assertThat(events(id, "order.fulfilled")).hasSize(1);
        assertThat(events(id, "order.shipped")).isEmpty();
        List<Map<String, Object>> delivered = events(id, "order.delivered");
        assertThat(delivered).hasSize(1);
        assertThat(payloadOf(delivered.get(0)).get("previousStatus").asText()).isEqualTo("FULFILLED");
        assertThat(delivered.get(0).get("actor")).isEqualTo("user:kc-dave");
    }

    // ------------------------------------------------------------------ a courier Oriah pays: the fare is recorded at dispatch

    @Test
    @DisplayName("a courier Oriah pays: shipping needs the fare (409 fare-required); with it the order is SHIPPED, the fare is kept on the step and is the only cost fact in order.shipped")
    void aCourierFareIsRecordedAtDispatch() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00");
        assertThat(step(id, "pack", "{}").getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> before = jdbc.queryForMap("SELECT shipping_amount, total, delivery_payer, delivery_option FROM orders WHERE id = ?", id);

        MvcResult missing = step(id, "ship", "{}");
        assertThat(missing.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(missing)).endsWith("/fare-required");
        assertThat(row(id).get("status")).as("nothing changed").isEqualTo("FULFILLED");

        MvcResult shipped = step(id, "ship", "{\"farePaid\":100,\"reference\":\"WB-4411/A\"}");
        assertThat(shipped.getResponse().getStatus()).as(shipped.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(shipped).get("status").asText()).isEqualTo("SHIPPED");
        assertThat(json(shipped).get("stage").asText()).isEqualTo("ON_THE_WAY");
        assertThat(json(shipped).get("reference").asText()).isEqualTo("WB-4411/A");
        assertThat(json(shipped).has("farePaid")).as("a packer who may not see cost does not read the fare back").isFalse();

        Map<String, Object> step = stepRow(id, "DISPATCHED");
        assertThat((BigDecimal) step.get("fare_paid")).isEqualByComparingTo("100.00");
        assertThat(step.get("reference")).isEqualTo("WB-4411/A");

        List<Map<String, Object>> events = events(id, "order.shipped");
        assertThat(events).hasSize(1);
        JsonNode dispatch = payloadOf(events.get(0)).get("dispatch");
        assertThat(dispatch.get("farePaid").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(dispatch.get("payer").asText()).isEqualTo("ORIAH_COLLECTS");
        assertThat(Instant.parse(dispatch.get("dispatchedAt").asText())).isEqualTo(at(step.get("occurred_at")));
        assertThat(events.get(0).get("payload").toString()).doesNotContain("WB-4411").doesNotContain("Mulenga").doesNotContain("971234567");
        assertThat(payloadOf(events(id, "order.fulfilled").get(0)).has("dispatch")).as("only order.shipped carries the dispatch").isFalse();

        assertThat(step(id, "deliver", "{}").getResponse().getStatus()).isEqualTo(200);
        assertThat(payloadOf(events(id, "order.delivered").get(0)).has("dispatch")).isFalse();
        assertThat(jdbc.queryForMap("SELECT shipping_amount, total, delivery_payer, delivery_option FROM orders WHERE id = ?", id))
                .as("the delivery charge is never touched by a dispatch step (Q24)").isEqualTo(before);
    }

    @Test
    @DisplayName("a fare of K0.00 is a real answer (the courier charged nothing) and is published as such")
    void aZeroFareIsRecorded() throws Exception {
        UUID id = paidDelivery("PLATINUM", "ORIAH_COLLECTS", "150.00");
        step(id, "pack", "{}");

        MvcResult shipped = step(id, "ship", "{\"farePaid\":0.00}");

        assertThat(shipped.getResponse().getStatus()).isEqualTo(200);
        assertThat((BigDecimal) stepRow(id, "DISPATCHED").get("fare_paid")).isEqualByComparingTo("0.00");
        assertThat(payloadOf(events(id, "order.shipped").get(0)).get("dispatch").get("farePaid").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a free delivery that Oriah still pays the courier for still has a fare: the rule reads the payer, never the charge")
    void aFreeDeliveryStillHasAFare() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "0.00");
        step(id, "pack", "{}");

        assertThat(type(step(id, "ship", "{}"))).endsWith("/fare-required");
        assertThat(step(id, "ship", "{\"farePaid\":90.00}").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a fare out of range is 400: negative, finer than an ngwee, or above the ceiling")
    void aFareOutOfRangeIsRefused() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00");
        step(id, "pack", "{}");

        for (String fare : new String[] {"-1", "10.005", "5000.01"}) {
            MvcResult refused = step(id, "ship", "{\"farePaid\":" + fare + "}");
            assertThat(refused.getResponse().getStatus()).as(fare).isEqualTo(400);
        }
        assertThat(row(id).get("status")).isEqualTo("FULFILLED");
    }

    // ------------------------------------------------------------------ no fare where none is due

    @Test
    @DisplayName("a courier the recipient pays has no fare: one typed is 409 no-fare-for-this-delivery, and shipping without it works and publishes no fare")
    void aRecipientPaidCourierHasNoFare() throws Exception {
        UUID id = paidDelivery("YANGO", "RECIPIENT_PAYS", "0.00");
        step(id, "pack", "{}");

        MvcResult typed = step(id, "ship", "{\"farePaid\":100}");
        assertThat(typed.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(typed)).endsWith("/no-fare-for-this-delivery");

        assertThat(step(id, "ship", "{}").getResponse().getStatus()).isEqualTo(200);
        assertThat(stepRow(id, "DISPATCHED").get("fare_paid")).isNull();
        assertThat(payloadOf(events(id, "order.shipped").get(0)).get("dispatch").has("farePaid")).isFalse();
    }

    @Test
    @DisplayName("Oriah's own delivery has no fare either: fuel and the driver's pay are expenses in finance")
    void ownDeliveryHasNoFare() throws Exception {
        UUID id = paidDelivery("OWN_DELIVERY", "ORIAH_COLLECTS", "125.00");
        step(id, "pack", "{}");

        assertThat(type(step(id, "ship", "{\"farePaid\":40.00}"))).endsWith("/no-fare-for-this-delivery");
        assertThat(step(id, "ship", "{}").getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ the order of things

    @Test
    @DisplayName("an order that is not paid is not packed (409 order-not-paid); a packed one is not packed again as new; a courier order is not delivered before it is shipped")
    void stepsHappenInOrder() throws Exception {
        UUID unpaid = heldOrder();
        UUID cancelled = heldOrder(2);
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelled);
        UUID paid = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00");

        for (UUID order : new UUID[] {unpaid, cancelled}) {
            MvcResult refused = step(order, "pack", "{}");
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(type(refused)).endsWith("/order-not-paid");
        }
        assertThat(type(step(paid, "ship", "{\"farePaid\":100}"))).as("not packed yet").endsWith("/dispatch-step-not-allowed");
        assertThat(type(step(paid, "deliver", "{}"))).as("not shipped yet").endsWith("/dispatch-step-not-allowed");
        step(paid, "pack", "{}");
        assertThat(type(step(paid, "deliver", "{}"))).as("packed, not shipped").endsWith("/dispatch-step-not-allowed");
        assertThat(steps(unpaid)).isEmpty();
        assertThat(step(UUID.randomUUID(), "pack", "{}").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("an order placed before Phase 8, with no recipient or address kept, cannot be packed: 409 delivery-details-missing")
    void anOrderWithNoDeliveryDetailsCannotBePacked() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00");
        jdbc.update("UPDATE orders SET recipient_name = NULL, recipient_phone = NULL WHERE id = ?", id);

        MvcResult refused = step(id, "pack", "{}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(refused)).endsWith("/delivery-details-missing");
        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    // ------------------------------------------------------------------ once, however often it is asked

    @Test
    @DisplayName("a step asked again with what was recorded is 200 replay and changes nothing; asked with a different fare, reference or date it is 409 and names what stands")
    void aRepeatIsAReplay() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00");
        MvcResult first = step(id, "pack", "{}");
        Instant packedAt = Instant.parse(json(first).get("packedAt").asText());

        MvcResult again = step(id, "pack", "{}");
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(again).get("replay").asBoolean()).isTrue();
        assertThat(Instant.parse(json(again).get("packedAt").asText())).isEqualTo(packedAt);
        MvcResult sameDate = step(id, "pack", "{\"occurredAt\":\"" + iso(packedAt) + "\"}");
        assertThat(json(sameDate).get("replay").asBoolean()).isTrue();
        MvcResult otherDate = step(id, "pack", "{\"occurredAt\":\"" + iso(packedAt.minusSeconds(60)) + "\"}");
        assertThat(otherDate.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(otherDate)).endsWith("/already-recorded");

        step(id, "ship", "{\"farePaid\":100,\"reference\":\"WB-1\"}");
        assertThat(json(step(id, "ship", "{\"farePaid\":100.00,\"reference\":\"WB-1\"}")).get("replay").asBoolean()).isTrue();
        assertThat(json(step(id, "ship", "{}")).get("replay").asBoolean()).as("what is not sent means any").isTrue();
        for (String other : new String[] {"{\"farePaid\":120}", "{\"reference\":\"WB-2\"}"}) {
            MvcResult refused = step(id, "ship", other);
            assertThat(refused.getResponse().getStatus()).as(other).isEqualTo(409);
            assertThat(type(refused)).endsWith("/already-dispatched");
        }
        assertThat(steps(id)).hasSize(2);
        assertThat(events(id, "order.shipped")).hasSize(1);
        assertThat((BigDecimal) stepRow(id, "DISPATCHED").get("fare_paid")).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("the steps are append-only: the database refuses to change or delete one, and a step is recorded once per order")
    void theStepsAreAppendOnly() throws Exception {
        UUID id = paidPickup();
        step(id, "pack", "{}");

        assertThatThrownBy(() -> jdbc.update("UPDATE order_dispatch_step SET recorded_by = 'someone else' WHERE order_id = ?", id)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM order_dispatch_step WHERE order_id = ?", id)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO order_dispatch_step (id, order_id, step, occurred_at, recorded_at, recorded_by) "
                + "VALUES (?, ?, 'PACKED', now(), now(), 'x')", UUID.randomUUID(), id)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------ the true date

    @Test
    @DisplayName("a step is recorded at the date it was keyed, and its event is dated to it, not to the day it was typed")
    void aStepKeepsItsTrueDate() throws Exception {
        UUID id = paidPickup();
        Instant when = Instant.now().minus(Duration.ofHours(5)).truncatedTo(ChronoUnit.MICROS);

        MvcResult packed = step(id, "pack", "{\"occurredAt\":\"" + when + "\"}");

        assertThat(packed.getResponse().getStatus()).as(packed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(at(stepRow(id, "PACKED").get("occurred_at"))).isEqualTo(when);
        assertThat(at(events(id, "order.fulfilled").get(0).get("occurred_at"))).isEqualTo(when);
    }

    @Test
    @DisplayName("a time in the future, or before the step it follows (the day the money arrived, for the first), is 400 invalid-occurred-at")
    void aWrongTimeIsRefused() throws Exception {
        UUID id = paidPickup();

        MvcResult future = step(id, "pack", "{\"occurredAt\":\"" + Instant.now().plus(Duration.ofHours(2)) + "\"}");
        MvcResult beforeThePayment = step(id, "pack", "{\"occurredAt\":\"2026-11-01T08:00:00Z\"}");

        for (MvcResult refused : new MvcResult[] {future, beforeThePayment}) {
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(type(refused)).endsWith("/invalid-occurred-at");
        }
        assertThat(steps(id)).isEmpty();
    }

    @Test
    @DisplayName("beyond 72 hours back only someone who may back-date a sale can key a time, with a reason, which is kept")
    void anOldTimeNeedsTheCapabilityAndAReason() throws Exception {
        UUID id = paidPickup();
        jdbc.update("UPDATE orders SET paid_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(30))), id);
        String old = "\"occurredAt\":\"" + Instant.now().minus(Duration.ofDays(10)) + "\"";

        assertThat(step(packer(), id, "pack", "{" + old + ",\"backdateReason\":\"the shop was shut for the holiday\"}").getResponse().getStatus()).isEqualTo(403);
        MvcResult noReason = step(packerWhoMayBackdate(), id, "pack", "{" + old + "}");
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(type(noReason)).endsWith("/backdate-reason-required");
        assertThat(steps(id)).isEmpty();

        MvcResult done = step(packerWhoMayBackdate(), id, "pack", "{" + old + ",\"backdateReason\":\"the shop was shut for the holiday\"}");

        assertThat(done.getResponse().getStatus()).as(done.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(stepRow(id, "PACKED").get("backdate_reason")).isEqualTo("the shop was shut for the holiday");
    }

    @Test
    @DisplayName("a step cannot be dated before the step it follows: shipping before it was packed, or delivering before it was shipped, is 400 invalid-occurred-at")
    void aStepCannotPrecedeTheOneBeforeIt() throws Exception {
        UUID id = paidDelivery("PLATINUM", "RECIPIENT_PAYS", "0.00");
        Instant packedAt = Instant.parse(json(step(id, "pack", "{}")).get("packedAt").asText());

        MvcResult shippedBeforePacked = step(id, "ship", "{\"occurredAt\":\"" + iso(packedAt.minusSeconds(60)) + "\"}");
        assertThat(shippedBeforePacked.getResponse().getStatus()).isEqualTo(400);
        assertThat(type(shippedBeforePacked)).endsWith("/invalid-occurred-at");
        Instant sentAt = Instant.parse(json(step(id, "ship", "{\"occurredAt\":\"" + iso(packedAt.plusSeconds(1)) + "\"}")).get("dispatchedAt").asText());

        MvcResult deliveredBeforeSent = step(id, "deliver", "{\"occurredAt\":\"" + iso(sentAt.minusSeconds(1)) + "\"}");
        assertThat(deliveredBeforeSent.getResponse().getStatus()).isEqualTo(400);
        assertThat(type(deliveredBeforeSent)).endsWith("/invalid-occurred-at");
        assertThat(step(id, "deliver", "{\"occurredAt\":\"" + iso(sentAt) + "\"}").getResponse().getStatus()).as("the same instant is fine").isEqualTo(200);
    }

    @Test
    @DisplayName("an order cannot be shipped while a box swap on it is with inventory: 409 box-swap-in-progress, and nothing is recorded")
    void aSwapInFlightHoldsBackShipping() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00");
        step(id, "pack", "{}");
        UUID line = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", UUID.class, id);
        jdbc.update("INSERT INTO order_box_swap_inflight (order_id, amendment_id, item_id, new_box_variant_id, reason, requested_by, started_at) "
                + "VALUES (?, ?, ?, ?, 'a swap that was never finished', 'user:kc-maya', now())", id, UUID.randomUUID(), line, UUID.randomUUID());

        MvcResult refused = step(id, "ship", "{\"farePaid\":100}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(refused)).endsWith("/box-swap-in-progress");
        assertThat(row(id).get("status")).isEqualTo("FULFILLED");
        assertThat(steps(id)).extracting(r -> r.get("step")).containsExactly("PACKED");
    }

    // ------------------------------------------------------------------ who

    @Test
    @DisplayName("only FULFILMENT_AGENT takes a step: a shopper, a sales person and an order administrator alone are 403")
    void whoMayTakeAStep() throws Exception {
        UUID id = paidPickup();

        assertThat(step(alice(), id, "pack", "{}").getResponse().getStatus()).isEqualTo(403);
        assertThat(step(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")), id, "pack", "{}").getResponse().getStatus()).isEqualTo(403);
        assertThat(step(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN")), id, "pack", "{}").getResponse().getStatus()).isEqualTo(403);
        assertThat(steps(id)).isEmpty();
        assertThat(step(packer(), id, "pack", "{}").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("the fare is read back only by someone who may see cost (COST_VIEWER)")
    void theFareIsForCostViewers() throws Exception {
        UUID id = paidDelivery("YANGO", "ORIAH_COLLECTS", "150.00");
        step(id, "pack", "{}");

        MvcResult shipped = step(packerWhoSeesCost(), id, "ship", "{\"farePaid\":100}");

        assertThat(json(shipped).get("farePaid").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(json(step(packer(), id, "ship", "{}")).has("farePaid")).as("the same replay, to a packer who sees no cost").isFalse();
    }

    // ------------------------------------------------------------------ the old door

    @Test
    @DisplayName("the staff door to FULFILLED, SHIPPED and DELIVERED is closed: 409 use-the-dispatch-steps, and it still cancels an unpaid order")
    void theGenericDoorOnlyCancels() throws Exception {
        UUID paid = paidPickup();
        UUID unpaid = heldOrder(2);
        RequestPostProcessor admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN")).jwt(j -> j.subject("kc-maya"));

        for (String target : new String[] {"FULFILLED", "SHIPPED", "DELIVERED"}) {
            MvcResult refused = mvc.perform(post("/api/v1/admin/orders/" + paid + "/transition").with(admin).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetStatus\":\"" + target + "\"}")).andReturn();
            assertThat(refused.getResponse().getStatus()).as(target).isEqualTo(409);
            assertThat(type(refused)).as(target).endsWith("/use-the-dispatch-steps");
        }
        assertThat(row(paid).get("status")).isEqualTo("PAID");
        MvcResult cancelled = mvc.perform(post("/api/v1/admin/orders/" + unpaid + "/transition").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"CANCELLED\"}")).andReturn();
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
    }
}
