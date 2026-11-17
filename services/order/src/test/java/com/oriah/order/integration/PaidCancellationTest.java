package com.oriah.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.CatalogAsOfClient;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.service.CorrectionJob;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Cancelling a PAID order before it is dispatched (Phase 12, plan 4.2; FINAL AM2 and AV7). Everything goes back to stock and everything that was paid is
 * owed, delivery included. Order decides whether it may be done and keeps the record (a correction that points back at the sale; the paid rows are never
 * rewritten); inventory puts the stock back, asked between two short transactions and keyed by the correction's id, so a repeat is answered once.
 *
 * <p>While inventory has been asked and has not answered in a way that settles it, the correction is IN FLIGHT: nothing else may be done to the order, and
 * a job asks again. Only an answer that PROVES nothing moved frees the order; a proven refusal ends that attempt and a new request starts a new one.
 */
class PaidCancellationTest extends SaleTestBase {

    private static final UUID TOWN = UUID.fromString("5e6f7a8b-1c2d-4e3f-8a9b-0c1d2e3f4a5b");
    private static final UUID REPLACEMENT_BOX = UUID.fromString("c3e4a618-0a2c-43e4-a618-24a02c4e6f2b");

    @Autowired
    private CorrectionJob job;
    @Autowired
    private PlatformTransactionManager transactions;

    private static RequestPostProcessor manager() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN")).jwt(j -> j.subject("kc-maya"));
    }

    private static RequestPostProcessor packer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT")).jwt(j -> j.subject("kc-dave"));
    }

    /**
     * A correction is a record: it is never deleted, so one that an earlier test left in flight is still there. Each test starts with none in flight
     * (they are ended the way a proven refusal ends them), so a pass of the job touches only what the test itself made.
     */
    @org.junit.jupiter.api.BeforeEach
    void nothingInFlight() {
        jdbc.update("UPDATE order_correction SET state = 'REFUSED', refused_code = 'left-by-an-earlier-test', next_attempt_at = NULL WHERE state = 'REQUESTED'");
    }

    // ------------------------------------------------------------------ orders to cancel
    private UUID paidPickup() throws Exception {
        UUID id = heldOrder();
        confirmed(id, "4500.00");
        assertThat(row(id).get("status")).isEqualTo("PAID");
        return id;
    }

    /** One ring delivered by this option, placed and held; not paid yet. */
    private UUID placedDelivery(String option, String payer, String charge) throws Exception {
        SaleStubs.shippingDelivers(shipping, option, TOWN, payer, charge, "ZMW");
        doReturn(List.of(new com.oriah.order.client.CustomerClient.Address(UUID.fromString(ADDRESS), "Plot 12 Great East Road", null, "Lusaka",
                "Lusaka Province", "10101", "ZM"))).when(customer).myAddresses();
        String digest = QuoteDigest.of(new Quote(List.of(new QuoteLine(RING, 1, new BigDecimal("4500.00"), new BigDecimal("0.00"), null)), "ZMW", option,
                new BigDecimal(charge), TOWN, payer));
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"" + option + "\",\"deliveryTownId\":\"" + TOWN
                + "\",\"payer\":\"" + payer + "\",\"recipientName\":\"Mulenga Chanda\",\"recipientPhone\":\"0971234567\",\"quoteDigest\":\"" + digest + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":1,\"unitPrice\":4500.00}]}";
        MvcResult placed = mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(json.readTree(placed.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID paidDelivery(String option, String payer, String charge) throws Exception {
        UUID id = placedDelivery(option, payer, charge);
        confirmed(id, new BigDecimal("4500.00").add(new BigDecimal(charge)).toPlainString());
        assertThat(row(id).get("status")).isEqualTo("PAID");
        return id;
    }

    private MvcResult step(UUID id, String step, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/orders/" + id + "/dispatch/" + step).with(packer()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    // ------------------------------------------------------------------ the cancellation

    private MvcResult cancel(RequestPostProcessor who, UUID id, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/orders/" + id + "/cancellation").with(who).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult cancel(UUID id) throws Exception {
        return cancel(manager(), id, "{\"reasonCode\":\"CUSTOMER_ASKED\"}");
    }

    private JsonNode body(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String type(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("type").asText();
    }

    private List<Map<String, Object>> corrections(UUID orderId) {
        return jdbc.queryForList("SELECT * FROM order_correction WHERE order_id = ? ORDER BY recorded_at", orderId);
    }

    private Map<String, Object> item(UUID orderId) {
        return jdbc.queryForMap("SELECT * FROM order_items WHERE order_id = ?", orderId);
    }

    /** Inventory putting back whatever it is asked to. */
    private void inventoryReverses() {
        doAnswer(inv -> {
            InventoryClient.ReversalAsk ask = inv.getArgument(0);
            return new InventoryClient.Reversal(ask.reversalId(), ask.kind(), ask.orderId(), ask.occurredAt(), Instant.now(), false);
        }).when(inventory).reverseSale(any());
    }

    private static String problem(int status, String type) {
        return type == null ? null
                : "{\"type\":\"https://oriah.dev/problems/" + type + "\",\"title\":\"x\",\"status\":" + status + ",\"detail\":\"inventory said " + type + "\"}";
    }

    private void inventoryAnswersTheReversal(int status, String type) {
        doThrow(SaleStubs.feign(status, problem(status, type))).when(inventory).reverseSale(any());
    }

    /** What inventory says when it is asked whether it holds a reversal. */
    private void inventoryReadsTheReversal(int status, String type) {
        doThrow(SaleStubs.feign(status, problem(status, type))).when(inventory).reversal(any());
    }

    private void inventoryHasTheReversal() {
        doAnswer(inv -> new InventoryClient.Reversal(inv.getArgument(0), "CANCELLATION", UUID.randomUUID(), Instant.now(), Instant.now(), false))
                .when(inventory).reversal(any());
    }

    private void due(UUID correctionId) {
        jdbc.update("UPDATE order_correction SET next_attempt_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), correctionId);
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("a paid delivery order is cancelled: the pieces and their box go back, everything paid is owed (delivery included), the order is CANCELLED and forgets who it was for, and order.sale_cancelled says what is owed")
    void aPaidOrderIsCancelledAndOwesItsTotal() throws Exception {
        inventoryReverses();
        UUID id = paidDelivery("OWN_DELIVERY", "ORIAH_COLLECTS", "45.00");
        Map<String, Object> line = item(id);
        Map<String, Object> sale = row(id);
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        JsonNode view = body(cancel(id), 201);

        UUID correctionId = UUID.fromString(view.get("correctionId").asText());
        assertThat(view.get("orderId").asText()).isEqualTo(id.toString());
        assertThat(view.get("state").asText()).isEqualTo("DONE");
        assertThat(view.get("reasonCode").asText()).isEqualTo("CUSTOMER_ASKED");
        assertThat(view.get("goodsAtList").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(view.get("discounts").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(view.get("deliveryRefunded").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(view.get("waiverTakenBack").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(view.get("owedGross").decimalValue()).isEqualByComparingTo("4545.00");
        assertThat(view.get("feeBearer").asText()).isEqualTo("CUSTOMER");
        assertThat(view.get("currency").asText()).isEqualTo("ZMW");
        assertThat(view.get("replay").asBoolean()).isFalse();
        assertThat(view.has("refusedCode")).isFalse();

        // What inventory was asked: this order's sale lines, whole, and the box that went with each piece.
        ArgumentCaptor<InventoryClient.ReversalAsk> asked = ArgumentCaptor.forClass(InventoryClient.ReversalAsk.class);
        verify(inventory, times(1)).reverseSale(asked.capture());
        InventoryClient.ReversalAsk ask = asked.getValue();
        assertThat(ask.reversalId()).isEqualTo(correctionId);
        assertThat(ask.kind()).isEqualTo("CANCELLATION");
        assertThat(ask.orderId()).isEqualTo(id);
        assertThat(ask.reservationId()).isEqualTo(sale.get("reservation_id"));
        assertThat(ask.goods()).containsExactly(new InventoryClient.GoodsAsk((UUID) line.get("id"), RING, 1));
        assertThat(ask.boxes()).containsExactly(new InventoryClient.BoxAsk((UUID) line.get("box_line_id"), SaleStubs.BOX_VARIANT, PRODUCT, 1));
        assertThat(ask.pieces()).isNullOrEmpty();

        // The correction: a record that points back at the sale.
        Map<String, Object> correction = corrections(id).get(0);
        assertThat(correction.get("id")).isEqualTo(correctionId);
        assertThat(correction.get("kind")).isEqualTo("CANCELLATION");
        assertThat(correction.get("state")).isEqualTo("DONE");
        assertThat(correction.get("status_before")).isEqualTo("PAID");
        assertThat(correction.get("confirmation_id")).isEqualTo(sale.get("paid_confirmation_id"));
        assertThat(correction.get("receipt_number")).isEqualTo(sale.get("receipt_number"));
        assertThat(at(correction.get("sale_arrived_at"))).isEqualTo(Instant.parse(ARRIVED));
        assertThat(at(correction.get("occurred_at"))).isBetween(before, Instant.now());
        assertThat(ask.occurredAt()).isEqualTo(at(correction.get("occurred_at")));
        assertThat(correction.get("recorded_by")).isEqualTo("user:kc-maya");
        assertThat(correction.get("completed_at")).isNotNull();
        assertThat(Instant.parse(view.get("cancelledAt").asText())).isEqualTo(at(correction.get("occurred_at")));

        // The order: cancelled, its pieces were sold (the hold stays consumed), the sale itself not rewritten, nobody's name or address kept.
        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("CANCELLED");
        assertThat(after.get("hold_state")).isEqualTo("CONSUMED");
        assertThat(after.get("total")).isEqualTo(sale.get("total"));
        assertThat(after.get("receipt_number")).isEqualTo(sale.get("receipt_number"));
        assertThat(after.get("paid_at")).isEqualTo(sale.get("paid_at"));
        assertThat(after.get("recipient_name")).isNull();
        assertThat(after.get("recipient_phone")).isNull();
        assertThat(after.get("address_line1")).isNull();
        assertThat(item(id)).isEqualTo(line);

        // The event: order.sale_cancelled, by the person, dated to the cancellation; never a plain order.cancelled.
        List<Map<String, Object>> events = events(id, "order.sale_cancelled");
        assertThat(events).hasSize(1);
        assertThat(events(id, "order.cancelled")).isEmpty();
        assertThat(events.get(0).get("actor")).isEqualTo("user:kc-maya");
        assertThat(at(events.get(0).get("occurred_at"))).isEqualTo(at(correction.get("occurred_at")));
        JsonNode payload = payloadOf(events.get(0));
        assertThat(payload.get("correctionId").asText()).isEqualTo(correctionId.toString());
        assertThat(payload.get("kind").asText()).isEqualTo("CANCELLATION");
        assertThat(payload.get("statusBefore").asText()).isEqualTo("PAID");
        assertThat(payload.get("statusAfter").asText()).isEqualTo("CANCELLED");
        assertThat(payload.get("owedGross").decimalValue()).isEqualByComparingTo("4545.00");
        assertThat(payload.get("deliveryRefunded").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(payload.get("lines")).hasSize(1);
        assertThat(payload.get("lines").get(0).get("lineRefund").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(payload.has("pieces")).isFalse();
        assertThat((String) events.get(0).get("payload")).doesNotContain("Mulenga").doesNotContain("0971234567").doesNotContain("Great East");

        // Reading it back.
        JsonNode read = body(mvc.perform(get("/api/v1/admin/orders/" + id + "/cancellation").with(manager())).andReturn(), 200);
        assertThat(read.get("correctionId").asText()).isEqualTo(correctionId.toString());
        assertThat(read.get("state").asText()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("a packed order that has not been dispatched is cancelled the same way; a pickup owes its pieces only")
    void aPackedOrderToo() throws Exception {
        inventoryReverses();
        UUID id = paidPickup();
        assertThat(step(id, "pack", "{}").getResponse().getStatus()).isEqualTo(200);

        JsonNode view = body(cancel(id), 201);

        assertThat(view.get("deliveryRefunded").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(view.get("owedGross").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(corrections(id).get(0).get("status_before")).isEqualTo("FULFILLED");
        assertThat(row(id).get("status")).isEqualTo("CANCELLED");
        assertThat(payloadOf(events(id, "order.sale_cancelled").get(0)).get("statusBefore").asText()).isEqualTo("FULFILLED");

        // The packer is told plainly: it was paid and then cancelled, so the parcel is unpacked, not "not paid yet".
        MvcResult slip = mvc.perform(get("/api/v1/admin/orders/" + id + "/dispatch").with(packer())).andReturn();
        assertThat(slip.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(slip)).endsWith("/order-cancelled");
        assertThat(json.readTree(slip.getResponse().getContentAsString()).get("detail").asText()).contains("cancelled").doesNotContain("not paid");
        MvcResult handOver = step(id, "deliver", "{}");
        assertThat(handOver.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(handOver)).endsWith("/order-cancelled");
        assertThat(row(id).get("status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("once dispatched or handed over an order is not cancelled (what comes back is a return), and an unpaid order has its own cancel: 409 cancellation-not-allowed saying which door; an unknown order is 404")
    void notOnceDispatched() throws Exception {
        UUID handedOver = paidPickup();
        step(handedOver, "pack", "{}");
        assertThat(step(handedOver, "deliver", "{}").getResponse().getStatus()).isEqualTo(200);
        UUID unpaid = heldOrder(2);
        UUID shipped = paidDelivery("OWN_DELIVERY", "ORIAH_COLLECTS", "45.00");
        step(shipped, "pack", "{}");
        assertThat(step(shipped, "ship", "{}").getResponse().getStatus()).isEqualTo(200);
        for (UUID gone : new UUID[] {shipped, handedOver}) {
            MvcResult refused = cancel(gone);
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(type(refused)).endsWith("/cancellation-not-allowed");
            assertThat(json.readTree(refused.getResponse().getContentAsString()).get("use").asText()).isEqualTo("a-return");
        }
        MvcResult notPaid = cancel(unpaid);
        assertThat(type(notPaid)).endsWith("/cancellation-not-allowed");
        assertThat(json.readTree(notPaid.getResponse().getContentAsString()).get("use").asText()).isEqualTo("the-cancel-door");
        assertThat(cancel(UUID.randomUUID()).getResponse().getStatus()).isEqualTo(404);

        verify(inventory, never()).reverseSale(any());
        assertThat(corrections(shipped)).isEmpty();
        assertThat(corrections(unpaid)).isEmpty();
        assertThat(row(shipped).get("status")).isEqualTo("SHIPPED");
    }

    @Test
    @DisplayName("the reason is one of the four, and 'other' says why: 400 otherwise, nothing written")
    void theReasonIsFromTheListAndOtherSaysWhy() throws Exception {
        inventoryReverses();
        UUID id = paidPickup();

        assertThat(cancel(manager(), id, "{}").getResponse().getStatus()).isEqualTo(400);
        assertThat(cancel(manager(), id, "{\"reasonCode\":\"CHANGED_MIND\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(cancel(manager(), id, "{\"reasonCode\":\"OTHER\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(cancel(manager(), id, "{\"reasonCode\":\"SHOP_ERROR\",\"note\":\"no\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(corrections(id)).isEmpty();

        JsonNode view = body(cancel(manager(), id, "{\"reasonCode\":\"OTHER\",\"note\":\"  the owner asked for it  \"}"), 201);
        assertThat(view.get("reasonCode").asText()).isEqualTo("OTHER");
        assertThat(corrections(id).get(0).get("note")).isEqualTo("the owner asked for it");
    }

    // ------------------------------------------------------------------ what is owed

    @Test
    @DisplayName("a delivery the recipient pays the courier for was never paid to Oriah: only the pieces are owed")
    void recipientPaid() throws Exception {
        inventoryReverses();
        UUID id = paidDelivery("YANGO", "RECIPIENT_PAYS", "0.00");

        JsonNode view = body(cancel(id), 201);

        assertThat(view.get("deliveryRefunded").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(view.get("owedGross").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(payloadOf(events(id, "order.sale_cancelled").get(0)).get("deliveryPayer").asText()).isEqualTo("RECIPIENT_PAYS");
    }

    @Test
    @DisplayName("a delivery that was waived was never paid, so it is not owed: the amount waived is only taken back from the memo")
    void aWaivedDelivery() throws Exception {
        inventoryReverses();
        UUID id = placedDelivery("OWN_DELIVERY", "ORIAH_COLLECTS", "45.00");
        MvcResult waived = mvc.perform(post("/api/v1/admin/orders/" + id + "/delivery-waiver")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DELIVERY_WAIVER")).jwt(j -> j.subject("kc-maya")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"a regular customer\"}")).andReturn();
        assertThat(waived.getResponse().getStatus()).as(waived.getResponse().getContentAsString()).isEqualTo(200);
        confirmed(id, "4500.00");
        assertThat(row(id).get("status")).isEqualTo("PAID");

        JsonNode view = body(cancel(id), 201);

        assertThat(view.get("deliveryRefunded").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(view.get("waiverTakenBack").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(view.get("owedGross").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(payloadOf(events(id, "order.sale_cancelled").get(0)).get("waiverTakenBack").decimalValue()).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("a phone order with a delivery agreed by phone owes the amount charged; its caller is kept (the refund is theirs), its recipient and address are forgotten, and nothing personal is in the event")
    void aPhoneOrderKeepsItsCaller() throws Exception {
        inventoryReverses();
        doReturn(new CatalogAsOfClient.AsOfResponse(Instant.now(), List.of(new CatalogAsOfClient.AsOfEntry(RING, "PRICED", PRODUCT, "Aurelia 18k",
                "Aurelia 18k / Ring Size M", new BigDecimal("4500.00"), "ZMW", UUID.randomUUID(), new BigDecimal("0.00"), null, new BigDecimal("4500.00")))))
                .when(catalogAsOf).priceAsOf(any());
        SaleStubs.shippingPlaces(shipping, TOWN);
        SaleStubs.shippingNotOffered(shipping);
        MvcResult recorded = mvc.perform(post("/api/v1/admin/orders/recorded")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")).jwt(j -> j.subject("kc-sam").tokenValue("sam-token")))
                .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"LIVE_PHONE\",\"caller\":{\"name\":\"Mulenga Chanda\",\"phone\":\"097 123 4567\"},\"lines\":[{\"variantId\":\"" + RING
                        + "\",\"quantity\":1}],\"delivery\":{\"mode\":\"AGREED\",\"optionCode\":\"OWN_DELIVERY\",\"townId\":\"" + TOWN
                        + "\",\"payer\":\"ORIAH_COLLECTS\",\"agreedAmount\":60.00,\"agreedReason\":\"agreed on the phone\"},"
                        + "\"address\":{\"line1\":\"Plot 12 Great East Road\",\"city\":\"Kabwe\",\"country\":\"ZM\"},\"expectedTotal\":4560.00}")).andReturn();
        UUID id = UUID.fromString(body(recorded, 201).get("id").asText());
        confirmed(id, "4560.00");
        assertThat(row(id).get("status")).isEqualTo("PAID");

        JsonNode view = body(cancel(id), 201);

        assertThat(view.get("deliveryRefunded").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(view.get("owedGross").decimalValue()).isEqualByComparingTo("4560.00");
        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("CANCELLED");
        assertThat(after.get("caller_name")).isEqualTo("Mulenga Chanda");
        assertThat(after.get("caller_phone")).isEqualTo("+260971234567");
        assertThat(after.get("recipient_name")).isNull();
        assertThat(after.get("address_line1")).isNull();
        Map<String, Object> event = events(id, "order.sale_cancelled").get(0);
        JsonNode payload = payloadOf(event);
        assertThat(payload.get("channel").asText()).isEqualTo("PHONE");
        assertThat(payload.get("saleKind").asText()).isEqualTo("LIVE_PHONE");
        assertThat(payload.get("keycloakId").isNull()).isTrue();
        assertThat((String) event.get("payload")).doesNotContain("Mulenga").doesNotContain("971234567").doesNotContain("Great East").doesNotContain("agreed on the phone");
    }

    @Test
    @DisplayName("the box that stands on the line is the one sent back: after a swap, the replacement's own sale line and variant")
    void theLiveBoxAfterASwapIsSent() throws Exception {
        inventoryReverses();
        SaleStubs.inventorySwaps(inventory);
        UUID id = paidPickup();
        UUID line = (UUID) item(id).get("id");
        MvcResult swapped = mvc.perform(post("/api/v1/admin/orders/" + id + "/box-swaps").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lineId\":\"" + line + "\",\"replacementBoxVariantId\":\"" + REPLACEMENT_BOX + "\",\"reason\":\"the customer chose the velvet box\"}")).andReturn();
        assertThat(swapped.getResponse().getStatus()).as(swapped.getResponse().getContentAsString()).isEqualTo(200);
        UUID newBoxLine = (UUID) item(id).get("box_line_id");

        body(cancel(id), 201);

        ArgumentCaptor<InventoryClient.ReversalAsk> asked = ArgumentCaptor.forClass(InventoryClient.ReversalAsk.class);
        verify(inventory).reverseSale(asked.capture());
        assertThat(asked.getValue().boxes()).containsExactly(new InventoryClient.BoxAsk(newBoxLine, REPLACEMENT_BOX, PRODUCT, 1));
    }

    @Test
    @DisplayName("a cancellation is never dated before the money arrived, even when the money's clock ran a little ahead of ours (AV7)")
    void neverDatedBeforeTheMoneyArrived() throws Exception {
        inventoryReverses();
        UUID id = heldOrder();
        Instant ahead = Instant.now().plusSeconds(25).truncatedTo(ChronoUnit.MILLIS);
        confirmed(UUID.randomUUID(), id, "4500.00", "ZMW", ahead.toString());
        assertThat(row(id).get("status")).isEqualTo("PAID");

        body(cancel(id), 201);

        Map<String, Object> correction = corrections(id).get(0);
        assertThat(at(correction.get("sale_arrived_at"))).isEqualTo(ahead);
        assertThat(at(correction.get("occurred_at"))).isEqualTo(ahead);
    }

    @Test
    @DisplayName("a sale from before lines kept their product and discount is refused, never guessed: 409 order-too-old-to-correct, nothing asked and nothing written")
    void legacyLinesAreRefusedNeverGuessed() throws Exception {
        UUID noProduct = paidPickup();
        jdbc.update("UPDATE order_items SET product_id = NULL WHERE order_id = ?", noProduct);
        UUID noDiscount = heldOrder(2);
        confirmed(noDiscount, "9000.00");
        jdbc.update("UPDATE order_items SET discount_per_piece = NULL WHERE order_id = ?", noDiscount);

        for (UUID old : new UUID[] {noProduct, noDiscount}) {
            MvcResult refused = cancel(old);
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(type(refused)).endsWith("/order-too-old-to-correct");
            assertThat(corrections(old)).isEmpty();
            assertThat(row(old).get("status")).isEqualTo("PAID");
        }
        verify(inventory, never()).reverseSale(any());
    }

    // ------------------------------------------------------------------ repeats

    @Test
    @DisplayName("the same cancellation asked again answers the one that was made (200, replay), asks inventory nothing more and announces nothing more; another reason is 409 already-cancelled")
    void aRepeatIsAReplay() throws Exception {
        inventoryReverses();
        UUID id = paidPickup();
        JsonNode first = body(cancel(id), 201);

        JsonNode again = body(cancel(id), 200);

        assertThat(again.get("correctionId").asText()).isEqualTo(first.get("correctionId").asText());
        assertThat(again.get("replay").asBoolean()).isTrue();
        assertThat(again.get("state").asText()).isEqualTo("DONE");
        MvcResult other = cancel(manager(), id, "{\"reasonCode\":\"SHOP_ERROR\"}");
        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(other)).endsWith("/already-cancelled");
        verify(inventory, times(1)).reverseSale(any());
        assertThat(corrections(id)).hasSize(1);
        assertThat(events(id, "order.sale_cancelled")).hasSize(1);
    }

    // ------------------------------------------------------------------ in flight

    @Test
    @DisplayName("inventory not answering leaves the cancellation in flight (202): the order is still paid, nothing is announced, and until it is settled nothing else is done to the order: no packing, no swap, no corrected address, no other cancellation")
    void blocksPackingShippingSwapsAndCorrectionsWhileInFlight() throws Exception {
        inventoryAnswersTheReversal(503, null);
        UUID id = paidDelivery("OWN_DELIVERY", "ORIAH_COLLECTS", "45.00");

        JsonNode view = body(cancel(id), 202);

        assertThat(view.get("state").asText()).isEqualTo("REQUESTED");
        UUID correctionId = UUID.fromString(view.get("correctionId").asText());
        Map<String, Object> correction = corrections(id).get(0);
        assertThat(correction.get("state")).isEqualTo("REQUESTED");
        assertThat(correction.get("attempts")).isEqualTo(1);
        assertThat(at(correction.get("next_attempt_at"))).isBetween(Instant.now().plusSeconds(10), Instant.now().plusSeconds(16));
        assertThat(row(id).get("status")).isEqualTo("PAID");
        assertThat(row(id).get("recipient_name")).isEqualTo("Mulenga Chanda");
        assertThat(events(id, "order.sale_cancelled")).isEmpty();

        MvcResult pack = step(id, "pack", "{}");
        assertThat(pack.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(pack)).endsWith("/correction-in-progress");
        assertThat(json.readTree(pack.getResponse().getContentAsString()).get("correctionId").asText()).isEqualTo(correctionId.toString());
        MvcResult swap = mvc.perform(post("/api/v1/admin/orders/" + id + "/box-swaps").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lineId\":\"" + item(id).get("id") + "\",\"replacementBoxVariantId\":\"" + REPLACEMENT_BOX + "\",\"reason\":\"the customer chose the velvet box\"}")).andReturn();
        assertThat(type(swap)).endsWith("/correction-in-progress");
        MvcResult address = mvc.perform(post("/api/v1/admin/orders/" + id + "/delivery-details").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientName\":\"Chanda Mulenga\",\"recipientPhone\":\"0971234567\",\"address\":{\"line1\":\"Plot 9\",\"city\":\"Lusaka\",\"country\":\"ZM\"},\"reason\":\"the customer moved\"}")).andReturn();
        assertThat(address.getResponse().getStatus()).as(address.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(type(address)).endsWith("/correction-in-progress");
        MvcResult other = cancel(manager(), id, "{\"reasonCode\":\"SHOP_ERROR\"}");
        assertThat(type(other)).endsWith("/already-cancelled");

        // The same request again asks inventory the same thing (the same id) and, once it answers, finishes.
        inventoryReverses();
        JsonNode finished = body(cancel(id), 200);
        assertThat(finished.get("correctionId").asText()).isEqualTo(correctionId.toString());
        assertThat(finished.get("state").asText()).isEqualTo("DONE");
        assertThat(finished.get("replay").asBoolean()).isTrue();
        assertThat(row(id).get("status")).isEqualTo("CANCELLED");
        assertThat(events(id, "order.sale_cancelled")).hasSize(1);
    }

    @Test
    @DisplayName("inventory did the work but the reply was lost (or this service stopped): the job asks inventory whether it holds the reversal and finishes the cancellation")
    void aCrashAfterInventoryIsFinishedByTheJob() throws Exception {
        inventoryAnswersTheReversal(504, null);
        UUID id = paidPickup();
        UUID correctionId = UUID.fromString(body(cancel(id), 202).get("correctionId").asText());

        // Not due yet: a pass does nothing.
        job.run();
        verify(inventory, never()).reversal(any());

        due(correctionId);
        inventoryHasTheReversal();
        job.run();

        verify(inventory, times(1)).reversal(correctionId);
        verify(inventory, times(1)).reverseSale(any());
        assertThat(corrections(id).get(0).get("state")).isEqualTo("DONE");
        assertThat(row(id).get("status")).isEqualTo("CANCELLED");
        List<Map<String, Object>> events = events(id, "order.sale_cancelled");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("actor")).as("the person who asked, not the job").isEqualTo("user:kc-maya");
    }

    @Test
    @DisplayName("inventory never got the request: the job hears its typed 'no such reversal' and sends the same request again; while inventory stays silent the wait doubles and the count of stuck corrections says so")
    void inventoryDownLeavesItRequested() throws Exception {
        inventoryAnswersTheReversal(503, null);
        UUID id = paidPickup();
        UUID correctionId = UUID.fromString(body(cancel(id), 202).get("correctionId").asText());

        // Inventory silent on the read too: nothing is concluded, the wait grows.
        due(correctionId);
        inventoryReadsTheReversal(503, null);
        job.run();
        Map<String, Object> waiting = corrections(id).get(0);
        assertThat(waiting.get("state")).isEqualTo("REQUESTED");
        assertThat(waiting.get("attempts")).isEqualTo(2);
        assertThat(at(waiting.get("next_attempt_at"))).isAfter(Instant.now().plusSeconds(25));
        verify(inventory, times(1)).reverseSale(any());
        assertThat(job.stuck(Instant.now())).isZero();
        assertThat(job.stuck(Instant.now().plusSeconds(301))).isEqualTo(1);

        // It answers: it has none. The same request goes again, and this time it is done.
        due(correctionId);
        inventoryReadsTheReversal(404, "reversal-not-found");
        inventoryReverses();
        job.run();

        ArgumentCaptor<InventoryClient.ReversalAsk> asked = ArgumentCaptor.forClass(InventoryClient.ReversalAsk.class);
        verify(inventory, times(2)).reverseSale(asked.capture());
        assertThat(asked.getAllValues().get(1)).as("the very same request").isEqualTo(asked.getAllValues().get(0));
        assertThat(corrections(id).get(0).get("state")).isEqualTo("DONE");
        assertThat(row(id).get("status")).isEqualTo("CANCELLED");
        assertThat(job.stuck(Instant.now().plusSeconds(301))).isZero();
    }

    @Test
    @DisplayName("a refusal that proves nothing moved ends that attempt (409 cancellation-refused, naming inventory's reason): the order is free again, and a new request is a new attempt with a new id")
    void aProvenRefusalFreesTheOrderAndANewAttemptHasANewId() throws Exception {
        inventoryAnswersTheReversal(409, "box-line-not-live");
        UUID id = paidPickup();

        MvcResult refused = cancel(id);

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(refused)).endsWith("/cancellation-refused");
        JsonNode problem = json.readTree(refused.getResponse().getContentAsString());
        assertThat(problem.get("refusedCode").asText()).isEqualTo("box-line-not-live");
        Map<String, Object> first = corrections(id).get(0);
        assertThat(first.get("state")).isEqualTo("REFUSED");
        assertThat(first.get("refused_code")).isEqualTo("box-line-not-live");
        assertThat(first.get("completed_at")).isNull();
        assertThat(row(id).get("status")).isEqualTo("PAID");
        assertThat(events(id, "order.sale_cancelled")).isEmpty();
        JsonNode read = body(mvc.perform(get("/api/v1/admin/orders/" + id + "/cancellation").with(manager())).andReturn(), 200);
        assertThat(read.get("state").asText()).isEqualTo("REFUSED");
        assertThat(read.get("refusedCode").asText()).isEqualTo("box-line-not-live");

        inventoryReverses();
        JsonNode second = body(cancel(id), 201);

        assertThat(second.get("correctionId").asText()).isNotEqualTo(first.get("id").toString());
        assertThat(second.get("state").asText()).isEqualTo("DONE");
        assertThat(corrections(id)).hasSize(2);
        assertThat(row(id).get("status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("'that id was already used' and 'more than is outstanding' are what a repeat of a reversal that DID happen can look like: never proof, the cancellation stays in flight")
    void reversalIdUsedIsNeverProof() throws Exception {
        UUID id = paidPickup();
        for (String code : new String[] {"reversal-id-used", "more-than-outstanding"}) {
            inventoryAnswersTheReversal(409, code);

            JsonNode view = body(cancel(id), 202);

            assertThat(view.get("state").asText()).as(code).isEqualTo("REQUESTED");
        }
        assertThat(corrections(id)).hasSize(1);
        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a stuck cancellation is abandoned by hand only when inventory itself says it holds no such reversal; a bare 404 proves nothing (503), and if it did move the cancellation is finished instead (409 it-did-move)")
    void abandonOnlyWhenInventorySaysNothingMoved() throws Exception {
        inventoryAnswersTheReversal(503, null);
        UUID id = paidPickup();
        UUID correctionId = UUID.fromString(body(cancel(id), 202).get("correctionId").asText());
        String abandon = "/api/v1/admin/orders/corrections/" + correctionId + "/abandon";

        // The list of what is in flight shows it.
        JsonNode list = body(mvc.perform(get("/api/v1/admin/orders/corrections").param("state", "REQUESTED").with(manager())).andReturn(), 200);
        assertThat(list.findValuesAsText("correctionId")).contains(correctionId.toString());

        inventoryReadsTheReversal(404, null);
        MvcResult unknown = mvc.perform(post(abandon).with(manager())).andReturn();
        assertThat(unknown.getResponse().getStatus()).as(unknown.getResponse().getContentAsString()).isEqualTo(503);
        assertThat(corrections(id).get(0).get("state")).isEqualTo("REQUESTED");

        inventoryReadsTheReversal(404, "reversal-not-found");
        JsonNode abandoned = body(mvc.perform(post(abandon).with(manager())).andReturn(), 200);
        assertThat(abandoned.get("state").asText()).isEqualTo("REFUSED");
        assertThat(abandoned.get("refusedCode").asText()).isEqualTo("abandoned-nothing-moved");
        assertThat(row(id).get("status")).isEqualTo("PAID");
        assertThat(step(id, "pack", "{}").getResponse().getStatus()).as("the order is free again").isEqualTo(200);

        // Not in flight any more.
        MvcResult again = mvc.perform(post(abandon).with(manager())).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(again)).endsWith("/not-in-flight");
        assertThat(mvc.perform(post("/api/v1/admin/orders/corrections/" + UUID.randomUUID() + "/abandon").with(manager())).andReturn().getResponse().getStatus())
                .isEqualTo(404);

        // A second attempt, stuck too, but this time inventory DID put the stock back: abandoning finishes it.
        UUID second = UUID.fromString(body(cancel(id), 202).get("correctionId").asText());
        inventoryHasTheReversal();
        MvcResult moved = mvc.perform(post("/api/v1/admin/orders/corrections/" + second + "/abandon").with(manager())).andReturn();
        assertThat(moved.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(moved)).endsWith("/it-did-move");
        assertThat(row(id).get("status")).isEqualTo("CANCELLED");
        assertThat(events(id, "order.sale_cancelled")).hasSize(1);
    }

    @Test
    @DisplayName("a box swap that is with inventory must be finished first: 409 box-swap-in-progress, nothing written")
    void refusedWhileASwapIsInFlight() throws Exception {
        UUID id = paidPickup();
        jdbc.update("INSERT INTO order_box_swap_inflight (order_id, amendment_id, item_id, new_box_variant_id, reason, requested_by, started_at) VALUES (?, ?, ?, ?, ?, ?, now())",
                id, UUID.randomUUID(), item(id).get("id"), REPLACEMENT_BOX, "the customer chose the velvet box", "user:kc-maya");

        MvcResult refused = cancel(id);

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(refused)).endsWith("/box-swap-in-progress");
        assertThat(corrections(id)).isEmpty();
        verify(inventory, never()).reverseSale(any());
        jdbc.update("DELETE FROM order_box_swap_inflight WHERE order_id = ?", id);
    }

    @Test
    @DisplayName("a cancellation waits for the order's lock: one asked while a packer's step is still open judges the order as that step left it")
    void waitsForTheOrderLock() throws Exception {
        inventoryReverses();
        UUID id = paidPickup();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        TransactionStatus gate = transactions.getTransaction(new DefaultTransactionDefinition());
        Future<Integer> cancelling;
        try {
            jdbc.queryForList("SELECT id FROM orders WHERE id = ? FOR UPDATE", UUID.class, id);
            cancelling = pool.submit(() -> cancel(id).getResponse().getStatus());
            long deadline = System.currentTimeMillis() + 15_000;
            while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class) == 0) {
                assertThat(System.currentTimeMillis()).as("the cancellation should be waiting on the order's row").isLessThan(deadline);
                Thread.sleep(20);
            }
            assertThat(corrections(id)).as("nothing is decided before the lock is granted").isEmpty();
        } finally {
            transactions.rollback(gate);
            pool.shutdown();
        }
        assertThat(cancelling.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        assertThat(row(id).get("status")).isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------ who

    @Test
    @DisplayName("only an order administrator cancels a paid order or abandons a correction: a salesperson, a packer and a shopper are 403, no token is 401")
    void whoMay() throws Exception {
        UUID id = paidPickup();
        for (String role : new String[] {"ROLE_SALES_AGENT", "ROLE_FULFILMENT_AGENT", "ROLE_USER", "ROLE_PAYMENT_ADMIN"}) {
            RequestPostProcessor who = jwt().authorities(new SimpleGrantedAuthority(role)).jwt(j -> j.subject("kc-someone"));
            assertThat(cancel(who, id, "{\"reasonCode\":\"CUSTOMER_ASKED\"}").getResponse().getStatus()).as(role).isEqualTo(403);
            assertThat(mvc.perform(get("/api/v1/admin/orders/" + id + "/cancellation").with(who)).andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
            assertThat(mvc.perform(get("/api/v1/admin/orders/corrections").with(who)).andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
            assertThat(mvc.perform(post("/api/v1/admin/orders/corrections/" + UUID.randomUUID() + "/abandon").with(who)).andReturn().getResponse().getStatus())
                    .as(role).isEqualTo(403);
        }
        assertThat(mvc.perform(post("/api/v1/admin/orders/" + id + "/cancellation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"CUSTOMER_ASKED\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/admin/orders/" + id + "/cancellation").with(manager())).andReturn().getResponse().getStatus())
                .as("nothing was cancelled").isEqualTo(404);
        assertThat(corrections(id)).isEmpty();
        verify(inventory, never()).reverseSale(any());
    }
}
