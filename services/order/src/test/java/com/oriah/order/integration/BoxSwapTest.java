package com.oriah.order.integration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.InventoryClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A box changed AFTER the order was paid (Phase 7): the customer asks for a different box, or the one set aside is damaged. Only ORDER_ADMIN (held by a
 * manager and the owner, not by a sales person) may do it, only on a PAID order not yet fulfilled, and always with a reason. Order decides who may ask
 * and keeps the record; inventory does the stock: it reverses the original box's sale and sells the replacement, both dated to the day of the SALE, in
 * one transaction keyed by the amendment id. The amendment id makes a retry safe: a repeat of the same swap answers the same swap.
 */
class BoxSwapTest extends SaleTestBase {

    private static final UUID REPLACEMENT = UUID.fromString("c3e4a618-0a2c-43e4-a618-24a02c4e6f2b");
    private static final String REASON = "the customer chose the velvet box";

    private static RequestPostProcessor manager() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN")).jwt(j -> j.subject("kc-maya"));
    }

    private UUID paidOrder() throws Exception {
        UUID id = heldOrder();
        confirmed(id, "4500.00");
        assertThat(row(id).get("status")).isEqualTo("PAID");
        return id;
    }

    private UUID lineOf(UUID orderId) {
        return jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", UUID.class, orderId);
    }

    private MvcResult swap(RequestPostProcessor who, UUID orderId, UUID line, UUID box, String reason, UUID amendment) throws Exception {
        return mvc.perform(post("/api/v1/admin/orders/" + orderId + "/box-swaps").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lineId\":\"" + line + "\",\"replacementBoxVariantId\":\"" + box + "\",\"reason\":\"" + reason + "\""
                        + (amendment == null ? "" : ",\"amendmentId\":\"" + amendment + "\"") + "}")).andReturn();
    }

    private Map<String, Object> item(UUID line) {
        return jdbc.queryForMap("SELECT * FROM order_items WHERE id = ?", line);
    }

    private long amendments(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM order_box_amendment WHERE order_id = ?", Long.class, orderId);
    }

    /** The swap on this order that has been asked of inventory and not yet recorded here (at most one), as the database holds it. */
    private List<Map<String, Object>> inflight(UUID orderId) {
        return jdbc.queryForList("SELECT * FROM order_box_swap_inflight WHERE order_id = ?", orderId);
    }

    /** The hand-over of a pickup (these orders are pickups): its dispatch, which a swap in flight must hold back. */
    private MvcResult handOver(UUID orderId) throws Exception {
        return mvc.perform(post("/api/v1/admin/orders/" + orderId + "/dispatch/deliver")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT")).jwt(j -> j.subject("kc-dave"))).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn();
    }

    /** Inventory swapping whatever it is asked to, but first doing {@code whileAsked}: what happens while the swap is in flight. */
    private void inventorySwapsWhile(Executable whileAsked) {
        doAnswer(inv -> {
            whileAsked.execute();
            InventoryClient.SwapAsk ask = inv.getArgument(0);
            return new InventoryClient.BoxSwap(ask.amendmentId(), ask.orderId(), ask.productId(), ask.originalSaleLineId(), ask.replacementSaleLineId(),
                    ask.replacementBoxVariantId(), 1, Instant.parse(ARRIVED), Instant.now(), ask.reason(), false);
        }).when(inventory).swapBox(any());
    }

    private static String refusal(int status, String type) {
        return type == null || type.isBlank() ? null
                : "{\"type\":\"https://oriah.dev/problems/" + type + "\",\"title\":\"Conflict\",\"status\":" + status + ",\"detail\":\"inventory said " + type + "\"}";
    }

    @Autowired
    private PlatformTransactionManager transactions;

    // ------------------------------------------------------------------ a swap

    @Test
    @DisplayName("ORDER_ADMIN swaps the box of a paid order: inventory reverses the old box's sale and sells the new one, and the line now carries the new box")
    void aBoxIsSwapped() throws Exception {
        SaleStubs.inventorySwaps(inventory);
        UUID id = paidOrder();
        UUID line = lineOf(id);
        Map<String, Object> before = item(line);

        MvcResult result = swap(manager(), id, line, REPLACEMENT, REASON, null);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        UUID amendment = UUID.fromString(body.get("amendmentId").asText());
        assertThat(body.get("orderId").asText()).isEqualTo(id.toString());
        assertThat(body.get("lineId").asText()).isEqualTo(line.toString());
        assertThat(body.get("oldBoxVariantId").asText()).isEqualTo(SaleStubs.BOX_VARIANT.toString());
        assertThat(body.get("newBoxVariantId").asText()).isEqualTo(REPLACEMENT.toString());
        assertThat(body.get("quantity").asInt()).isEqualTo(1);
        assertThat(body.get("reason").asText()).isEqualTo(REASON);
        assertThat(body.get("replay").asBoolean()).isFalse();

        ArgumentCaptor<InventoryClient.SwapAsk> asked = ArgumentCaptor.forClass(InventoryClient.SwapAsk.class);
        verify(inventory).swapBox(asked.capture());
        InventoryClient.SwapAsk ask = asked.getValue();
        assertThat(ask.amendmentId()).isEqualTo(amendment);
        assertThat(ask.orderId()).isEqualTo(id);
        assertThat(ask.productId()).isEqualTo(PRODUCT);
        assertThat(ask.originalSaleLineId()).as("the box was sold under the line's box-line id").isEqualTo(before.get("box_line_id"));
        assertThat(ask.replacementSaleLineId()).as("the same one on every retry: derived from the amendment id")
                .isEqualTo(UUID.nameUUIDFromBytes(("order-box-swap:" + amendment).getBytes(StandardCharsets.UTF_8)));
        assertThat(ask.replacementBoxVariantId()).isEqualTo(REPLACEMENT);
        assertThat(ask.reason()).isEqualTo(REASON);

        Map<String, Object> after = item(line);
        assertThat(after.get("box_variant_id")).isEqualTo(REPLACEMENT);
        assertThat(after.get("box_line_id")).as("a second swap starts from the box sold now").isEqualTo(ask.replacementSaleLineId());
        Map<String, Object> record = jdbc.queryForMap("SELECT * FROM order_box_amendment WHERE id = ?", amendment);
        assertThat(record.get("order_id")).isEqualTo(id);
        assertThat(record.get("item_id")).isEqualTo(line);
        assertThat(record.get("old_box_variant_id")).isEqualTo(SaleStubs.BOX_VARIANT);
        assertThat(record.get("new_box_variant_id")).isEqualTo(REPLACEMENT);
        assertThat(record.get("old_box_line_id")).isEqualTo(before.get("box_line_id"));
        assertThat(record.get("new_box_line_id")).isEqualTo(ask.replacementSaleLineId());
        assertThat(record.get("amended_by")).isEqualTo("user:kc-maya");
        assertThat(record.get("reason")).isEqualTo(REASON);
        assertThat(at(record.get("occurred_at"))).as("the swap never moves the date of the sale").isEqualTo(Instant.parse(ARRIVED));
        assertThat(record.get("recorded_at")).isNotNull();
    }

    @Test
    @DisplayName("a second swap starts from the box sold by the first")
    void aSecondSwapStartsFromTheFirst() throws Exception {
        SaleStubs.inventorySwaps(inventory);
        UUID id = paidOrder();
        UUID line = lineOf(id);
        swap(manager(), id, line, REPLACEMENT, REASON, null);
        UUID firstReplacementLine = (UUID) item(line).get("box_line_id");

        MvcResult second = swap(manager(), id, line, SaleStubs.BOX_VARIANT, "the velvet box was marked, back to the first", null);

        assertThat(second.getResponse().getStatus()).as(second.getResponse().getContentAsString()).isEqualTo(200);
        ArgumentCaptor<InventoryClient.SwapAsk> asked = ArgumentCaptor.forClass(InventoryClient.SwapAsk.class);
        verify(inventory, times(2)).swapBox(asked.capture());
        assertThat(asked.getAllValues().get(1).originalSaleLineId()).isEqualTo(firstReplacementLine);
        assertThat(amendments(id)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ once

    @Test
    @DisplayName("the same swap again (the same amendment id) answers the same swap and asks inventory nothing; another swap under that id is refused")
    void aRepeatIsTheSameSwap() throws Exception {
        SaleStubs.inventorySwaps(inventory);
        UUID id = paidOrder();
        UUID line = lineOf(id);
        UUID amendment = UUID.randomUUID();
        swap(manager(), id, line, REPLACEMENT, REASON, amendment);

        MvcResult again = swap(manager(), id, line, REPLACEMENT, REASON, amendment);
        MvcResult other = swap(manager(), id, line, UUID.randomUUID(), REASON, amendment);

        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(again.getResponse().getContentAsString()).get("replay").asBoolean()).isTrue();
        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(other.getResponse().getContentAsString()).get("type").asText()).endsWith("/amendment-id-used");
        verify(inventory, times(1)).swapBox(any());
        assertThat(amendments(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("inventory not answering: 503 and nothing recorded; asking again with the same amendment id records it once")
    void aLostReplyIsRetried() throws Exception {
        SaleStubs.inventorySwaps(inventory);
        UUID id = paidOrder();
        UUID line = lineOf(id);
        UUID amendment = UUID.randomUUID();
        doThrow(SaleStubs.feign(503, null)).when(inventory).swapBox(any());

        MvcResult silent = swap(manager(), id, line, REPLACEMENT, REASON, amendment);

        assertThat(silent.getResponse().getStatus()).isEqualTo(503);
        assertThat(amendments(id)).isZero();
        assertThat(item(line).get("box_variant_id")).as("unchanged").isEqualTo(SaleStubs.BOX_VARIANT);
        assertThat(inflight(id)).as("inventory may have done it: the swap stays in flight until it is recorded").hasSize(1);
        assertThat(inflight(id).get(0).get("amendment_id")).isEqualTo(amendment);

        SaleStubs.inventorySwaps(inventory);
        MvcResult retry = swap(manager(), id, line, REPLACEMENT, REASON, amendment);

        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(inflight(id)).as("recorded: no longer in flight").isEmpty();
        assertThat(amendments(id)).isEqualTo(1);
        ArgumentCaptor<InventoryClient.SwapAsk> asked = ArgumentCaptor.forClass(InventoryClient.SwapAsk.class);
        verify(inventory, times(2)).swapBox(asked.capture());
        assertThat(asked.getAllValues().get(1).replacementSaleLineId()).as("inventory sees the same swap both times")
                .isEqualTo(asked.getAllValues().get(0).replacementSaleLineId());
    }

    @Test
    @DisplayName("a swap sent WITHOUT an amendment id is still safe to repeat blind: the id comes from the line as it stands, so a retry after a lost reply asks inventory for the very same swap")
    void aBlindRetryAfterALostReplyIsTheSameSwap() throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);
        // inventory did the swap, but its reply never came back, and the caller never learned the id order minted
        doThrow(SaleStubs.feign(503, null)).when(inventory).swapBox(any());
        MvcResult silent = swap(manager(), id, line, REPLACEMENT, REASON, null);
        assertThat(silent.getResponse().getStatus()).isEqualTo(503);
        assertThat(inflight(id)).as("the swap with its minted id stays in flight").hasSize(1);

        SaleStubs.inventorySwaps(inventory);
        MvcResult retry = swap(manager(), id, line, REPLACEMENT, REASON, null);

        assertThat(retry.getResponse().getStatus()).as(retry.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(inflight(id)).isEmpty();
        assertThat(amendments(id)).isEqualTo(1);
        ArgumentCaptor<InventoryClient.SwapAsk> asked = ArgumentCaptor.forClass(InventoryClient.SwapAsk.class);
        verify(inventory, times(2)).swapBox(asked.capture());
        assertThat(asked.getAllValues().get(1).amendmentId()).as("the same swap both times").isEqualTo(asked.getAllValues().get(0).amendmentId());
        assertThat(item(line).get("box_variant_id")).isEqualTo(REPLACEMENT);
    }

    @Test
    @DisplayName("the same change asked again on a line whose box has changed since is ANOTHER swap, even with no ids: A to B, B to A, A to B each get their own amendment")
    void theSameChangeOnAChangedLineIsAnotherSwap() throws Exception {
        SaleStubs.inventorySwaps(inventory);
        UUID id = paidOrder();
        UUID line = lineOf(id);

        assertThat(swap(manager(), id, line, REPLACEMENT, REASON, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(swap(manager(), id, line, SaleStubs.BOX_VARIANT, "back to the first box, the velvet one was marked", null).getResponse().getStatus()).isEqualTo(200);
        MvcResult third = swap(manager(), id, line, REPLACEMENT, REASON, null);

        assertThat(third.getResponse().getStatus()).as(third.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(amendments(id)).isEqualTo(3);
        assertThat(item(line).get("box_variant_id")).isEqualTo(REPLACEMENT);
    }

    // ------------------------------------------------------------------ who, when, and why

    @Test
    @DisplayName("a reason is judged after trimming: spaces cannot make a short one long enough (400, not a server error), and padding beyond 200 does not spoil a good one")
    void aPaddedReasonIsJudgedTrimmed() throws Exception {
        SaleStubs.inventorySwaps(inventory);
        UUID id = paidOrder();
        UUID line = lineOf(id);

        MvcResult padded = swap(manager(), id, line, REPLACEMENT, "short" + " ".repeat(8), null);

        assertThat(padded.getResponse().getStatus()).as(padded.getResponse().getContentAsString()).isEqualTo(400);
        verify(inventory, never()).swapBox(any());
        assertThat(swap(manager(), id, line, REPLACEMENT, REASON + " ".repeat(300), null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("only ORDER_ADMIN may swap: a shopper and a sales person are refused (403) and inventory is not asked")
    void whoMaySwap() throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);

        assertThat(swap(alice(), id, line, REPLACEMENT, REASON, null).getResponse().getStatus()).isEqualTo(403);
        assertThat(swap(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")), id, line, REPLACEMENT, REASON, null).getResponse().getStatus())
                .isEqualTo(403);
        verify(inventory, never()).swapBox(any());
    }

    @Test
    @DisplayName("a box can change until the parcel is dispatched: a PAID or a FULFILLED (packed) order may swap; an unpaid, a settling, a SHIPPED and a DELIVERED one may not (409)")
    void swapsAreAllowedUntilDispatch() throws Exception {
        SaleStubs.inventorySwaps(inventory);
        UUID unpaid = heldOrder();
        UUID paid = heldOrder(2);
        confirmed(paid, "9000.00");
        UUID packed = heldOrder(3);
        confirmed(packed, "13500.00");
        jdbc.update("UPDATE orders SET status = 'FULFILLED' WHERE id = ?", packed);
        UUID shipped = heldOrder(4);
        confirmed(shipped, "18000.00");
        jdbc.update("UPDATE orders SET status = 'SHIPPED' WHERE id = ?", shipped);
        UUID delivered = heldOrder(5);
        confirmed(delivered, "22500.00");
        jdbc.update("UPDATE orders SET status = 'DELIVERED' WHERE id = ?", delivered);
        UUID settling = heldOrder(6);
        jdbc.update("UPDATE orders SET status = 'PAID', settling_confirmation_id = ? WHERE id = ?", UUID.randomUUID(), settling);

        for (UUID order : new UUID[] {paid, packed}) {
            MvcResult result = swap(manager(), order, lineOf(order), REPLACEMENT, REASON, null);
            assertThat(result.getResponse().getStatus()).as(order + " " + result.getResponse().getContentAsString()).isEqualTo(200);
        }
        for (UUID order : new UUID[] {unpaid, shipped, delivered, settling}) {
            MvcResult result = swap(manager(), order, lineOf(order), REPLACEMENT, REASON, null);
            assertThat(result.getResponse().getStatus()).as(order.toString()).isEqualTo(409);
        }
        verify(inventory, times(2)).swapBox(any());
        assertThat(json.readTree(swap(manager(), shipped, lineOf(shipped), REPLACEMENT, REASON, null).getResponse().getContentAsString()).get("type").asText())
                .endsWith("/box-swap-not-allowed");
    }

    @Test
    @DisplayName("the reason is mandatory, 10 to 200 characters: a missing, short or long one is a 400 and inventory is not asked")
    void aReasonIsRequired() throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);

        assertThat(swap(manager(), id, line, REPLACEMENT, "short", null).getResponse().getStatus()).isEqualTo(400);
        assertThat(swap(manager(), id, line, REPLACEMENT, "x".repeat(201), null).getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post("/api/v1/admin/orders/" + id + "/box-swaps").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lineId\":\"" + line + "\",\"replacementBoxVariantId\":\"" + REPLACEMENT + "\"}")).andReturn().getResponse().getStatus()).isEqualTo(400);
        verify(inventory, never()).swapBox(any());
    }

    @Test
    @DisplayName("a line that is not this order's is 404; the box it already has is 409 same-box; an unknown order is 404")
    void theLineAndTheBox() throws Exception {
        UUID id = paidOrder();

        assertThat(swap(manager(), id, UUID.randomUUID(), REPLACEMENT, REASON, null).getResponse().getStatus()).isEqualTo(404);
        MvcResult same = swap(manager(), id, lineOf(id), SaleStubs.BOX_VARIANT, REASON, null);
        assertThat(same.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(same.getResponse().getContentAsString()).get("type").asText()).endsWith("/same-box");
        assertThat(swap(manager(), UUID.randomUUID(), UUID.randomUUID(), REPLACEMENT, REASON, null).getResponse().getStatus()).isEqualTo(404);
        verify(inventory, never()).swapBox(any());
    }

    @Test
    @DisplayName("inventory refusing the swap (not a box, no stock) is passed on under its own name, and nothing is recorded")
    void inventoryRefuses() throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);
        doThrow(SaleStubs.feign(409, "{\"type\":\"https://oriah.dev/problems/not-packaging\",\"status\":409,\"detail\":\"that variant is not packaging\"}"))
                .when(inventory).swapBox(any());

        MvcResult result = swap(manager(), id, line, REPLACEMENT, REASON, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("type").asText()).endsWith("/not-packaging");
        assertThat(amendments(id)).isZero();
        assertThat(item(line).get("box_variant_id")).isEqualTo(SaleStubs.BOX_VARIANT);
    }

    // ------------------------------------------------------------------ the record

    @Test
    @DisplayName("the amendment record is append-only: the database refuses to change or delete one")
    void theRecordIsAppendOnly() throws Exception {
        SaleStubs.inventorySwaps(inventory);
        UUID id = paidOrder();
        swap(manager(), id, lineOf(id), REPLACEMENT, REASON, null);

        assertThatThrownBy(() -> jdbc.update("UPDATE order_box_amendment SET reason = 'rewritten history' WHERE order_id = ?", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM order_box_amendment WHERE order_id = ?", id)).hasMessageContaining("append-only");
        assertThat(amendments(id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ a swap in flight (Phase 8, slice 8.1)
    //
    // A swap is asked of inventory between two short transactions, with no connection held. For that window the order carries a DURABLE record -- one per
    // order -- that the swap is in flight. It is cleared only when the swap is recorded here, or when inventory refuses in a way that proves it moved nothing.
    // While it stands, the order cannot be shipped and no OTHER swap can start: a parcel must never leave with one box while the books say another.

    @Test
    @DisplayName("while inventory is being asked the swap is IN FLIGHT -- one durable record names it -- and once the swap is recorded here that record is gone")
    void aSwapIsInFlightWhileInventoryIsAsked() throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);
        UUID amendment = UUID.randomUUID();
        AtomicReference<List<Map<String, Object>>> seen = new AtomicReference<>();
        inventorySwapsWhile(() -> seen.set(inflight(id)));

        MvcResult done = swap(manager(), id, line, REPLACEMENT, REASON, amendment);

        assertThat(done.getResponse().getStatus()).as(done.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(seen.get()).as("one record, while inventory was being asked").hasSize(1);
        Map<String, Object> flight = seen.get().get(0);
        assertThat(flight.get("amendment_id")).isEqualTo(amendment);
        assertThat(flight.get("item_id")).isEqualTo(line);
        assertThat(flight.get("new_box_variant_id")).isEqualTo(REPLACEMENT);
        assertThat(flight.get("reason")).isEqualTo(REASON);
        assertThat(flight.get("requested_by")).isEqualTo("user:kc-maya");
        assertThat(flight.get("started_at")).isNotNull();
        assertThat(inflight(id)).as("recorded: nothing is in flight any more").isEmpty();
        assertThat(amendments(id)).isEqualTo(1);
    }

    @ParameterizedTest(name = "inventory says {0} {1}: it may have happened, so the swap stays in flight (the answer is {2})")
    @CsvSource({
            "503, ,                                 503",
            "500, ,                                 503",
            "502, ,                                 503",
            "-1,  ,                                 503",
            "409, amendment-id-used,                409",
            "409, already-swapped,                  409",
            "409, a-refusal-nobody-has-heard-of,    409",
            "409, ,                                 409",
            "408, ,                                 503",
            "425, ,                                 503",
            "429, ,                                 503",
            "404, ,                                 404",
    })
    @DisplayName("an answer that does not prove inventory moved nothing leaves the swap in flight, and nothing is recorded")
    void anUncertainAnswerKeepsTheSwapInFlight(int status, String type, int answered) throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);
        doThrow(SaleStubs.feign(status, refusal(status, type))).when(inventory).swapBox(any());

        MvcResult result = swap(manager(), id, line, REPLACEMENT, REASON, null);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(answered);
        assertThat(inflight(id)).as("it may have happened: it stays in flight").hasSize(1);
        assertThat(amendments(id)).isZero();
        assertThat(item(line).get("box_variant_id")).as("unchanged").isEqualTo(SaleStubs.BOX_VARIANT);
    }

    @ParameterizedTest(name = "inventory says {0} {1}: that proves it moved nothing, so the swap is over (the answer is {2})")
    @CsvSource({
            "404, sale-line-not-found,  404",
            "409, not-a-box-sale,       409",
            "409, same-box,             409",
            "409, not-packaging,        409",
            "409, insufficient-stock,   409",
            "400, ,                     500",
            "403, ,                     500",
            "422, ,                     500",
    })
    @DisplayName("a refusal that proves inventory moved nothing ends the swap: nothing stays in flight, and the same swap can be asked again")
    void aDefiniteRefusalEndsTheSwap(int status, String type, int answered) throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);
        doThrow(SaleStubs.feign(status, refusal(status, type))).when(inventory).swapBox(any());

        MvcResult result = swap(manager(), id, line, REPLACEMENT, REASON, null);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(answered);
        assertThat(inflight(id)).as("nothing moved: nothing is in flight").isEmpty();
        assertThat(amendments(id)).isZero();

        SaleStubs.inventorySwaps(inventory);
        assertThat(swap(manager(), id, line, REPLACEMENT, REASON, null).getResponse().getStatus()).as("the swap can be asked again").isEqualTo(200);
    }

    @Test
    @DisplayName("a swap inventory never answered keeps its order from shipping and from any OTHER swap, until the same swap is repeated and recorded")
    void anUnansweredSwapBlocksUntilItIsResumed() throws Exception {
        UUID id = paidOrder();
        jdbc.update("UPDATE orders SET status = 'FULFILLED' WHERE id = ?", id);
        UUID line = lineOf(id);
        UUID amendment = UUID.randomUUID();
        doThrow(SaleStubs.feign(503, null)).when(inventory).swapBox(any());
        assertThat(swap(manager(), id, line, REPLACEMENT, REASON, amendment).getResponse().getStatus()).isEqualTo(503);

        MvcResult other = swap(manager(), id, line, UUID.randomUUID(), "a different box, asked while the first is unresolved", null);
        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        JsonNode problem = json.readTree(other.getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).endsWith("/box-swap-in-progress");
        assertThat(problem.get("amendmentId").asText()).as("it names the swap to resume").isEqualTo(amendment.toString());
        assertThat(problem.get("startedAt").asText()).isNotBlank();
        assertThat(problem.get("lineId").asText()).as("everything a person needs to repeat the swap exactly").isEqualTo(line.toString());
        assertThat(problem.get("replacementBoxVariantId").asText()).isEqualTo(REPLACEMENT.toString());
        assertThat(problem.get("reason").asText()).isEqualTo(REASON);
        MvcResult shipped = handOver(id);
        assertThat(shipped.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(shipped.getResponse().getContentAsString()).get("type").asText()).endsWith("/box-swap-in-progress");
        assertThat(row(id).get("status")).as("not shipped").isEqualTo("FULFILLED");
        verify(inventory, times(1)).swapBox(any());

        SaleStubs.inventorySwaps(inventory);
        assertThat(swap(manager(), id, line, REPLACEMENT, REASON, amendment).getResponse().getStatus()).as("the same swap resumes").isEqualTo(200);

        assertThat(inflight(id)).isEmpty();
        assertThat(amendments(id)).isEqualTo(1);
        assertThat(handOver(id).getResponse().getStatus()).as("now nothing is in flight").isEqualTo(200);
    }

    @Test
    @DisplayName("an order cannot be shipped while a swap on it is with inventory, and can be once the swap is recorded: the race, through today's staff door")
    void aSwapWithInventoryBlocksShipping() throws Exception {
        UUID id = paidOrder();
        jdbc.update("UPDATE orders SET status = 'FULFILLED' WHERE id = ?", id);
        UUID line = lineOf(id);
        AtomicReference<MvcResult> shippedMeanwhile = new AtomicReference<>();
        inventorySwapsWhile(() -> shippedMeanwhile.set(handOver(id)));

        MvcResult done = swap(manager(), id, line, REPLACEMENT, REASON, null);

        assertThat(done.getResponse().getStatus()).as(done.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(shippedMeanwhile.get().getResponse().getStatus()).as(shippedMeanwhile.get().getResponse().getContentAsString()).isEqualTo(409);
        assertThat(json.readTree(shippedMeanwhile.get().getResponse().getContentAsString()).get("type").asText()).endsWith("/box-swap-in-progress");
        assertThat(row(id).get("status")).isEqualTo("FULFILLED");
        assertThat(item(line).get("box_variant_id")).isEqualTo(REPLACEMENT);

        assertThat(handOver(id).getResponse().getStatus()).isEqualTo(200);
        assertThat(row(id).get("status")).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("a second, different swap asked while the first is still with inventory is 409 box-swap-in-progress, and inventory is asked once")
    void oneSwapAtATime() throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);
        AtomicReference<MvcResult> second = new AtomicReference<>();
        AtomicReference<Object> firstAmendment = new AtomicReference<>();
        inventorySwapsWhile(() -> {
            firstAmendment.set(inflight(id).get(0).get("amendment_id"));
            second.set(swap(manager(), id, line, UUID.randomUUID(), "another box entirely, asked at the same time", null));
        });

        MvcResult first = swap(manager(), id, line, REPLACEMENT, REASON, null);

        assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(second.get().getResponse().getStatus()).as(second.get().getResponse().getContentAsString()).isEqualTo(409);
        JsonNode problem = json.readTree(second.get().getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).endsWith("/box-swap-in-progress");
        assertThat(problem.get("amendmentId").asText()).isEqualTo(firstAmendment.get().toString());
        verify(inventory, times(1)).swapBox(any());
        assertThat(amendments(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("the same swap sent twice at once (a double click) is asked of inventory under one amendment id and recorded once")
    void aDoubleSubmitIsOneSwap() throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);
        UUID amendment = UUID.randomUUID();
        AtomicBoolean inside = new AtomicBoolean();
        AtomicReference<MvcResult> twin = new AtomicReference<>();
        inventorySwapsWhile(() -> {
            if (inside.compareAndSet(false, true)) {
                twin.set(swap(manager(), id, line, REPLACEMENT, REASON, amendment));
            }
        });

        MvcResult first = swap(manager(), id, line, REPLACEMENT, REASON, amendment);

        assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(twin.get().getResponse().getStatus()).as(twin.get().getResponse().getContentAsString()).isEqualTo(200);
        assertThat(amendments(id)).as("one swap").isEqualTo(1);
        assertThat(inflight(id)).isEmpty();
        ArgumentCaptor<InventoryClient.SwapAsk> asked = ArgumentCaptor.forClass(InventoryClient.SwapAsk.class);
        verify(inventory, times(2)).swapBox(asked.capture());
        assertThat(asked.getAllValues()).extracting(InventoryClient.SwapAsk::amendmentId).containsOnly(amendment);
        assertThat(asked.getAllValues()).extracting(InventoryClient.SwapAsk::replacementSaleLineId).containsOnly(asked.getAllValues().get(0).replacementSaleLineId());
        assertThat(item(line).get("box_variant_id")).isEqualTo(REPLACEMENT);
        assertThat(item(line).get("box_line_id")).isEqualTo(asked.getValue().replacementSaleLineId());
    }

    @Test
    @DisplayName("the id of a swap in flight cannot be used for another swap: 409 amendment-id-used, and inventory is not asked again")
    void anInFlightIdIsNotReusedForAnotherSwap() throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);
        UUID amendment = UUID.randomUUID();
        doThrow(SaleStubs.feign(503, null)).when(inventory).swapBox(any());
        assertThat(swap(manager(), id, line, REPLACEMENT, REASON, amendment).getResponse().getStatus()).isEqualTo(503);

        MvcResult other = swap(manager(), id, line, UUID.randomUUID(), REASON, amendment);
        MvcResult reworded = swap(manager(), id, line, REPLACEMENT, "the customer chose the velvet box after all", amendment);

        for (MvcResult result : new MvcResult[] {other, reworded}) {
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(409);
            assertThat(json.readTree(result.getResponse().getContentAsString()).get("type").asText()).endsWith("/amendment-id-used");
        }
        verify(inventory, times(1)).swapBox(any());
        assertThat(inflight(id)).as("the swap in flight is untouched").hasSize(1);
    }

    @Test
    @DisplayName("the line takes the new box only if it still carries the box line the swap was made from; inventory's swap is recorded either way, never lost")
    void theLineChangesOnlyFromTheBoxTheSwapWasMadeFrom() throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);
        UUID elsewhere = UUID.randomUUID();
        inventorySwapsWhile(() -> jdbc.update("UPDATE order_items SET box_line_id = ? WHERE id = ?", elsewhere, line));

        MvcResult done = swap(manager(), id, line, REPLACEMENT, REASON, null);

        assertThat(done.getResponse().getStatus()).as(done.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(item(line).get("box_line_id")).as("the line is not overwritten").isEqualTo(elsewhere);
        assertThat(item(line).get("box_variant_id")).isEqualTo(SaleStubs.BOX_VARIANT);
        assertThat(amendments(id)).as("inventory moved the stock, so the swap is recorded").isEqualTo(1);
        assertThat(inflight(id)).isEmpty();
    }

    @Test
    @DisplayName("a swap judges the order AFTER whoever else is changing it has finished: an order shipped at that very moment is refused, never swapped from a stale look")
    void aSwapWaitsForTheOrderLock() throws Exception {
        UUID id = paidOrder();
        UUID line = lineOf(id);
        CountDownLatch holding = new CountDownLatch(1);
        Thread other = new Thread(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE", UUID.class, id);
            holding.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            jdbc.update("UPDATE orders SET status = 'SHIPPED' WHERE id = ?", id);
        }));
        other.start();
        assertThat(holding.await(20, TimeUnit.SECONDS)).isTrue();

        MvcResult result = swap(manager(), id, line, REPLACEMENT, REASON, null);
        other.join();

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("type").asText()).endsWith("/box-swap-not-allowed");
        verify(inventory, never()).swapBox(any());
        assertThat(inflight(id)).isEmpty();
    }

    @Test
    @DisplayName("a shipment judges the order AFTER whoever else is changing it has finished: a swap planned at that very moment stops it, never a shipment from a stale look")
    void aShipmentWaitsForTheOrderLock() throws Exception {
        UUID id = paidOrder();
        jdbc.update("UPDATE orders SET status = 'FULFILLED' WHERE id = ?", id);
        UUID line = lineOf(id);
        CountDownLatch holding = new CountDownLatch(1);
        Thread other = new Thread(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE", UUID.class, id);
            holding.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            jdbc.update("INSERT INTO order_box_swap_inflight (order_id, amendment_id, item_id, new_box_variant_id, reason, requested_by, started_at) "
                    + "VALUES (?, ?, ?, ?, ?, 'user:kc-maya', now())", id, UUID.randomUUID(), line, REPLACEMENT, REASON);
        }));
        other.start();
        assertThat(holding.await(20, TimeUnit.SECONDS)).isTrue();

        MvcResult result = handOver(id);
        other.join();

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("type").asText()).endsWith("/box-swap-in-progress");
        assertThat(row(id).get("status")).isEqualTo("FULFILLED");
    }

    @Test
    @DisplayName("the database allows one swap in flight per order, and holds the reason to 10 to 200 characters")
    void theInFlightRecordHoldsItsRules() {
        UUID order = UUID.randomUUID();
        String sql = "INSERT INTO order_box_swap_inflight (order_id, amendment_id, item_id, new_box_variant_id, reason, requested_by, started_at) "
                + "VALUES (?, ?, ?, ?, ?, 'user:kc-maya', now())";

        jdbc.update(sql, order, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a good enough reason");

        assertThatThrownBy(() -> jdbc.update(sql, order, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "another good reason")).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "short")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "x".repeat(201))).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM order_box_swap_inflight WHERE order_id = ?", order);
    }
}
