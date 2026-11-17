package com.oriah.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.CatalogAsOfClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * An exchange, and what the order's own answers say about a corrected sale (Phase 12, plan 4.9 and 4.10; FINAL AM7). An exchange is a return and a new
 * order, linked: no money is netted between them (the refund is paid, the new order is paid like any sale). The order's answers gain what was corrected
 * and what is owed, as trailing fields: the shopper and a salesperson see no note, no actor and no cost.
 */
class ExchangeAndReadsTest extends ReturnTestBase {

    private MvcResult exchange(RequestPostProcessor who, UUID returnId, UUID newOrder) throws Exception {
        return mvc.perform(post("/api/v1/admin/orders/returns/" + returnId + "/exchange").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"newOrderId\":\"" + newOrder + "\"}")).andReturn();
    }

    private UUID returnOf(UUID order, int quantity) throws Exception {
        return UUID.fromString(returned(order, quantity).get("returnId").asText());
    }

    // ------------------------------------------------------------------ the exchange

    @Test
    @DisplayName("an exchange links a received return to the new order the customer took instead: a link only, shown on the return and on both orders")
    void linksAReturnToANewOrder() throws Exception {
        inventoryReverses();
        UUID returnedOrder = collectedRings(2);
        UUID returnId = returnOf(returnedOrder, 1);
        UUID newOrder = paidRings(1);

        JsonNode view = body(exchange(manager(), returnId, newOrder), 201);

        assertThat(view.get("returnId").asText()).isEqualTo(returnId.toString());
        assertThat(view.get("returnedOrderId").asText()).isEqualTo(returnedOrder.toString());
        assertThat(view.get("newOrderId").asText()).isEqualTo(newOrder.toString());
        assertThat(view.get("replay").asBoolean()).isFalse();
        Map<String, Object> link = jdbc.queryForMap("SELECT * FROM order_exchange WHERE return_id = ?", returnId);
        assertThat(link.get("new_order_id")).isEqualTo(newOrder);
        assertThat(link.get("linked_by")).isEqualTo("user:kc-maya");
        assertThat(view.get("exchangeId").asText()).isEqualTo(link.get("id").toString());

        JsonNode read = body(mvc.perform(get("/api/v1/admin/orders/returns/" + returnId).with(manager())).andReturn(), 200);
        assertThat(read.get("exchange").get("newOrderId").asText()).isEqualTo(newOrder.toString());
        JsonNode old = body(mvc.perform(get("/api/v1/admin/orders/" + returnedOrder).with(manager())).andReturn(), 200);
        assertThat(old.get("corrections").get(0).get("id").asText()).isEqualTo(returnId.toString());
        assertThat(old.get("corrections").get(0).get("exchangedForOrderId").asText()).isEqualTo(newOrder.toString());
        assertThat(old.has("exchange")).as("the order that was returned is not itself an exchange").isFalse();
        JsonNode fresh = body(mvc.perform(get("/api/v1/admin/orders/" + newOrder).with(manager())).andReturn(), 200);
        assertThat(fresh.get("exchange").get("returnId").asText()).isEqualTo(returnId.toString());
        assertThat(fresh.get("exchange").get("returnedOrderId").asText()).isEqualTo(returnedOrder.toString());
        assertThat(fresh.has("corrections")).isFalse();
    }

    @Test
    @DisplayName("no money is netted: linking announces nothing, changes neither order and leaves what is owed for the return exactly as it was")
    void noMoneyIsNetted() throws Exception {
        inventoryReverses();
        UUID returnedOrder = collectedRings(2);
        UUID returnId = returnOf(returnedOrder, 1);
        UUID newOrder = paidRings(1);
        Map<String, Object> oldBefore = row(returnedOrder);
        Map<String, Object> newBefore = row(newOrder);
        Map<String, Object> returnBefore = corrections(returnedOrder).get(0);
        long eventsBefore = jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class);

        body(exchange(manager(), returnId, newOrder), 201);

        assertThat(row(returnedOrder)).isEqualTo(oldBefore);
        assertThat(row(newOrder)).isEqualTo(newBefore);
        assertThat(corrections(returnedOrder).get(0)).isEqualTo(returnBefore);
        assertThat(returnBefore.get("owed_gross").toString()).isEqualTo("90.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class)).isEqualTo(eventsBefore);
    }

    @Test
    @DisplayName("one link per return and one per new order: the same pair again is the same link (200, replay), any other is 409 exchange-already-recorded")
    void oncePerReturnAndPerOrder() throws Exception {
        inventoryReverses();
        UUID returnedOrder = collectedRings(2);
        UUID first = returnOf(returnedOrder, 1);
        UUID second = returnOf(returnedOrder, 1);
        UUID newOrder = paidRings(1);
        UUID anotherNewOrder = paidRings(3);
        JsonNode made = body(exchange(manager(), first, newOrder), 201);

        JsonNode again = body(exchange(manager(), first, newOrder), 200);
        assertThat(again.get("exchangeId").asText()).isEqualTo(made.get("exchangeId").asText());
        assertThat(again.get("replay").asBoolean()).isTrue();

        MvcResult sameReturn = exchange(manager(), first, anotherNewOrder);
        assertThat(sameReturn.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(sameReturn)).endsWith("/exchange-already-recorded");
        MvcResult sameOrder = exchange(manager(), second, newOrder);
        assertThat(sameOrder.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(sameOrder)).endsWith("/exchange-already-recorded");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_exchange WHERE return_id IN (?, ?)", Long.class, first, second)).isEqualTo(1);
    }

    @Test
    @DisplayName("the new order is another order that still stands, and the return has been received: the same order is 409 exchange-needs-another-order, a cancelled or lapsed one 409 not-an-exchange-order, a return still in flight 409 return-not-received-yet, an unknown order or return 404")
    void notTheSameOrder() throws Exception {
        inventoryReverses();
        UUID cancelled = heldOrder(3);
        mvc.perform(post("/api/v1/orders/" + cancelled + "/cancel").with(alice())).andReturn();
        assertThat(row(cancelled).get("status")).isEqualTo("CANCELLED");
        UUID returnedOrder = collectedRings(2);
        UUID returnId = returnOf(returnedOrder, 1);

        MvcResult same = exchange(manager(), returnId, returnedOrder);
        assertThat(same.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(same)).endsWith("/exchange-needs-another-order");
        MvcResult gone = exchange(manager(), returnId, cancelled);
        assertThat(gone.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(gone)).endsWith("/not-an-exchange-order");
        MvcResult noOrder = exchange(manager(), returnId, UUID.randomUUID());
        assertThat(noOrder.getResponse().getStatus()).isEqualTo(404);
        assertThat(type(noOrder)).endsWith("/order-not-found");
        assertThat(exchange(manager(), UUID.randomUUID(), returnedOrder).getResponse().getStatus()).isEqualTo(404);

        inventoryAnswersTheReversal(503, null);
        UUID other = collectedRings(1);
        UUID inFlight = UUID.fromString(body(recordReturn(manager(), other, "k-" + UUID.randomUUID(), "{\"reasonCode\":\"FAULTY\"," + lines(lineOf(other), 1) + "}"), 202)
                .get("returnId").asText());
        MvcResult early = exchange(manager(), inFlight, returnedOrder);
        assertThat(early.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(early)).endsWith("/return-not-received-yet");

        for (String role : new String[] {"ROLE_SALES_AGENT", "ROLE_FULFILMENT_AGENT", "ROLE_USER"}) {
            assertThat(exchange(jwt().authorities(new SimpleGrantedAuthority(role)).jwt(j -> j.subject("kc-someone")), returnId, other).getResponse().getStatus())
                    .as(role).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_exchange WHERE return_id = ?", Long.class, returnId)).isZero();
    }

    // ------------------------------------------------------------------ the order's own answers

    @Test
    @DisplayName("a corrected sale shows what was corrected and what is owed on the order's answers: staff also see why and who; the shopper sees no reason, no note and no actor, and never an attempt that was refused")
    void aCorrectedSaleShowsWhatIsOwed() throws Exception {
        UUID order = collectedRings(2);
        inventoryAnswersTheReversal(409, "not-a-sale");
        recordReturn(manager(), order, "k-" + UUID.randomUUID(), "{\"reasonCode\":\"FAULTY\"," + lines(lineOf(order), 1) + "}");
        inventoryReverses();
        UUID returnId = UUID.fromString(body(recordReturn(manager(), order, "k-" + UUID.randomUUID(),
                "{\"reasonCode\":\"OTHER\",\"note\":\"Mrs Banda did not like the colour\"," + lines(lineOf(order), 1) + "}"), 201).get("returnId").asText());

        MvcResult staffRead = mvc.perform(get("/api/v1/admin/orders/" + order).with(manager())).andReturn();
        JsonNode staff = body(staffRead, 200);
        assertThat(staff.get("corrections")).hasSize(2);
        JsonNode refused = staff.get("corrections").get(0);
        assertThat(refused.get("state").asText()).isEqualTo("REFUSED");
        JsonNode done = staff.get("corrections").get(1);
        assertThat(done.get("id").asText()).isEqualTo(returnId.toString());
        assertThat(done.get("kind").asText()).isEqualTo("RETURN");
        assertThat(done.get("state").asText()).isEqualTo("DONE");
        assertThat(done.get("pieces").asInt()).isEqualTo(1);
        assertThat(done.get("owedGross").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(done.get("reasonCode").asText()).isEqualTo("OTHER");
        assertThat(done.get("recordedBy").asText()).isEqualTo("user:kc-maya");
        assertThat(done.has("occurredAt")).isTrue();
        assertThat(staffRead.getResponse().getContentAsString()).as("the note is for the return's own record, not the order's answer").doesNotContain("Banda");

        MvcResult shopperRead = mvc.perform(get("/api/v1/orders/" + order).with(alice())).andReturn();
        JsonNode shopper = body(shopperRead, 200);
        assertThat(shopper.get("corrections")).hasSize(1);
        assertThat(shopper.get("corrections").get(0).get("owedGross").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(shopper.get("corrections").get(0).get("state").asText()).isEqualTo("DONE");
        assertThat(shopper.get("corrections").get(0).has("reasonCode")).isFalse();
        assertThat(shopper.get("corrections").get(0).has("recordedBy")).isFalse();
        assertThat(shopperRead.getResponse().getContentAsString()).doesNotContain("Banda").doesNotContain("kc-maya").doesNotContain("OTHER").doesNotContain("REFUSED");
        assertThat(shopper.get("status").asText()).as("a return does not change the status").isEqualTo("DELIVERED");

        // The shopper's list carries it too, and an order with nothing corrected carries no such field.
        JsonNode mine = body(mvc.perform(get("/api/v1/orders/me").with(alice())).andReturn(), 200);
        assertThat(mine.findValues("owedGross")).isNotEmpty();
        UUID untouched = paidRings(1);
        assertThat(body(mvc.perform(get("/api/v1/orders/" + untouched).with(alice())).andReturn(), 200).has("corrections")).isFalse();
    }

    @Test
    @DisplayName("a cancelled paid order says so on its answer: CANCELLED, with the cancellation and everything that was paid owed")
    void aCancelledPaidOrderShowsItsCancellation() throws Exception {
        inventoryReverses();
        UUID order = paidRings(2);
        body(mvc.perform(post("/api/v1/admin/orders/" + order + "/cancellation").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"CUSTOMER_ASKED\"}")).andReturn(), 201);

        JsonNode shopper = body(mvc.perform(get("/api/v1/orders/" + order).with(alice())).andReturn(), 200);

        assertThat(shopper.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(shopper.get("corrections")).hasSize(1);
        assertThat(shopper.get("corrections").get(0).get("kind").asText()).isEqualTo("CANCELLATION");
        assertThat(shopper.get("corrections").get(0).get("pieces").asInt()).isEqualTo(2);
        assertThat(shopper.get("corrections").get(0).get("owedGross").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(shopper.get("receiptNumber").asText()).as("the sale itself is not rewritten").startsWith("OR-");
    }

    // ------------------------------------------------------------------ a phone order

    /** A live phone sale of one ring at K4,500.00, collected by the caller: paid, packed and handed over. */
    private UUID collectedPhoneOrder() throws Exception {
        doReturn(new CatalogAsOfClient.AsOfResponse(Instant.now(), List.of(new CatalogAsOfClient.AsOfEntry(RING, "PRICED", PRODUCT, "Aurelia 18k",
                "Aurelia 18k / Ring Size M", new BigDecimal("4500.00"), "ZMW", UUID.randomUUID(), new BigDecimal("0.00"), null, new BigDecimal("4500.00")))))
                .when(catalogAsOf).priceAsOf(any());
        SaleStubs.shippingPlaces(shipping, TOWN);
        SaleStubs.shippingNotOffered(shipping);
        MvcResult recorded = mvc.perform(post("/api/v1/admin/orders/recorded")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")).jwt(j -> j.subject("kc-sam").tokenValue("sam-token")))
                .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"LIVE_PHONE\",\"caller\":{\"name\":\"Mulenga Chanda\",\"phone\":\"097 123 4567\"},\"lines\":[{\"variantId\":\"" + RING
                        + "\",\"quantity\":1}],\"delivery\":{\"mode\":\"PICKUP\"},\"expectedTotal\":4500.00}")).andReturn();
        UUID id = UUID.fromString(body(recorded, 201).get("id").asText());
        confirmed(id, "4500.00");
        stepped(id, "pack", "{}");
        stepped(id, "deliver", "{}");
        SaleStubs.shippingSelfCollect(shipping);
        return id;
    }

    @Test
    @DisplayName("a salesperson sees that a phone sale's pieces came back and what is owed, with no reason, actor or cost; the caller's phone stays masked for them once the order is finished")
    void theSalespersonSeesTheReturnAndNoCost() throws Exception {
        inventoryReverses();
        UUID order = collectedPhoneOrder();
        returned(order, 1);
        RequestPostProcessor sales = jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")).jwt(j -> j.subject("kc-sam"));

        MvcResult read = mvc.perform(get("/api/v1/admin/orders/recorded/" + order).with(sales)).andReturn();

        JsonNode view = body(read, 200);
        assertThat(view.get("corrections")).hasSize(1);
        assertThat(view.get("corrections").get(0).get("kind").asText()).isEqualTo("RETURN");
        assertThat(view.get("corrections").get(0).get("owedGross").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(view.get("corrections").get(0).has("reasonCode")).isFalse();
        assertThat(view.get("corrections").get(0).has("recordedBy")).isFalse();
        assertThat(view.get("caller").get("phone").asText()).isEqualTo("+2609*****567");
        assertThat(read.getResponse().getContentAsString().toLowerCase()).doesNotContain("cost").doesNotContain("margin").doesNotContain("kc-maya");
    }

    @Test
    @DisplayName("the owner, who pays refunds, reads the caller's phone in full while a correction of a phone sale owes money, even after hand-over: the refund has nowhere else to go")
    void theOwnerSeesTheCallerWhileMoneyIsOwed() throws Exception {
        inventoryReverses();
        UUID order = collectedPhoneOrder();
        RequestPostProcessor owner = jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"), new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"))
                .jwt(j -> j.subject("kc-olive"));

        // Nothing owed yet: masked for everyone once the order is finished.
        assertThat(body(mvc.perform(get("/api/v1/admin/orders/recorded/" + order).with(owner)).andReturn(), 200).get("caller").get("phone").asText())
                .isEqualTo("+2609*****567");

        UUID debt = UUID.fromString(returned(order, 1).get("returnId").asText());

        assertThat(callerPhone(order, owner)).isEqualTo("+260971234567");
        assertThat(callerPhone(order, manager())).as("someone who does not pay refunds").isEqualTo("+2609*****567");

        // Payment says the refund was paid: nothing is owed any more, so the phone is withheld again.
        UUID payout = UUID.randomUUID();
        paymentEvents.accept(message("money_out", payout, "payment.paid_out", 1, paidOut(payout, "REFUND", debt, order), "payment"));
        assertThat(callerPhone(order, owner)).isEqualTo("+2609*****567");
        // The payout was keyed by mistake and cancelled: it is owed again.
        paymentEvents.accept(message("money_out", payout, "payment.payout_voided", 1, payoutVoided(payout, debt, order), "payment"));
        assertThat(callerPhone(order, owner)).isEqualTo("+260971234567");
        // A payout of held money for the same order is not this refund.
        UUID heldPayout = UUID.randomUUID();
        paymentEvents.accept(message("money_out", heldPayout, "payment.paid_out", 1, paidOut(heldPayout, "HELD_MONEY", UUID.randomUUID(), order), "payment"));
        assertThat(callerPhone(order, owner)).isEqualTo("+260971234567");
        // The owner says the money never arrived: the debt is closed, nothing is owed.
        paymentEvents.accept(message("refund_due", debt, "payment.refund_closed", 1, "{\"dueId\":\"" + debt + "\",\"orderId\":\"" + order
                + "\",\"confirmationId\":\"" + UUID.randomUUID() + "\",\"owedGross\":4500.00,\"currency\":\"ZMW\",\"outcome\":\"MONEY_NEVER_ARRIVED\","
                + "\"closedAt\":\"2026-11-17T08:30:00Z\",\"recordedAt\":\"2026-11-17T08:30:00.750Z\"}", "payment"));
        assertThat(callerPhone(order, owner)).isEqualTo("+2609*****567");
    }

    private String callerPhone(UUID order, RequestPostProcessor reader) throws Exception {
        RequestPostProcessor who = reader;
        MvcResult read = mvc.perform(get("/api/v1/admin/orders/recorded/" + order).with(who)).andReturn();
        if (read.getResponse().getStatus() == 403) {
            // The recorded door is the sales staff's: an order administrator reads the same order as a salesperson would.
            read = mvc.perform(get("/api/v1/admin/orders/recorded/" + order)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")).jwt(j -> j.subject("kc-sam")))).andReturn();
        }
        return body(read, 200).get("caller").get("phone").asText();
    }

    private static String paidOut(UUID payout, String purpose, UUID debt, UUID order) {
        return "{\"payoutId\":\"" + payout + "\",\"purpose\":\"" + purpose + "\",\"debtId\":\"" + debt + "\",\"cause\":\"RETURN\",\"orderId\":\"" + order
                + "\",\"confirmationId\":\"" + UUID.randomUUID() + "\",\"method\":\"MTN\",\"gross\":4500.00,\"customerFee\":2.50,\"net\":4497.50,\"sendCharge\":2.50,"
                + "\"feeBearer\":\"CUSTOMER\",\"currency\":\"ZMW\",\"paidAt\":\"2026-11-17T08:00:00Z\",\"recordedAt\":\"2026-11-17T08:03:10.250Z\"}";
    }

    private static String payoutVoided(UUID payout, UUID debt, UUID order) {
        return "{\"payoutId\":\"" + payout + "\",\"purpose\":\"REFUND\",\"debtId\":\"" + debt + "\",\"orderId\":\"" + order + "\",\"confirmationId\":\""
                + UUID.randomUUID() + "\",\"method\":\"MTN\",\"gross\":4500.00,\"customerFee\":2.50,\"net\":4497.50,\"sendCharge\":2.50,\"currency\":\"ZMW\","
                + "\"paidAt\":\"2026-11-17T08:00:00Z\",\"voidedAt\":\"2026-11-17T09:45:00.500Z\"}";
    }
    // ------------------------------------------------------------------ the packer's list

    @Test
    @DisplayName("a parcel whose every piece came back is no longer on the packer's 'on the way' list")
    void aFullyReturnedParcelLeavesTheOnTheWayList() throws Exception {
        inventoryReverses();
        UUID order = shippedRing();
        JsonNode before = body(mvc.perform(get("/api/v1/admin/orders/dispatch/queue").param("stage", "ON_THE_WAY").with(packer())).andReturn(), 200);
        assertThat(before.findValuesAsText("orderId")).contains(order.toString());

        returned(order, 1);

        JsonNode after = body(mvc.perform(get("/api/v1/admin/orders/dispatch/queue").param("stage", "ON_THE_WAY").with(packer())).andReturn(), 200);
        assertThat(after.findValuesAsText("orderId")).doesNotContain(order.toString());
    }
}
