package com.oriah.order.integration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.InventoryClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

        SaleStubs.inventorySwaps(inventory);
        MvcResult retry = swap(manager(), id, line, REPLACEMENT, REASON, amendment);

        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(amendments(id)).isEqualTo(1);
        ArgumentCaptor<InventoryClient.SwapAsk> asked = ArgumentCaptor.forClass(InventoryClient.SwapAsk.class);
        verify(inventory, times(2)).swapBox(asked.capture());
        assertThat(asked.getAllValues().get(1).replacementSaleLineId()).as("inventory sees the same swap both times")
                .isEqualTo(asked.getAllValues().get(0).replacementSaleLineId());
    }

    // ------------------------------------------------------------------ who, when, and why

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
    @DisplayName("an order that is not paid, or is already fulfilled, or is being settled, cannot change its box: 409")
    void onlyAPaidOrderNotYetFulfilled() throws Exception {
        SaleStubs.inventorySwaps(inventory);
        UUID unpaid = heldOrder();
        UUID fulfilled = heldOrder(2);
        confirmed(fulfilled, "9000.00");
        jdbc.update("UPDATE orders SET status = 'FULFILLED' WHERE id = ?", fulfilled);
        UUID settling = heldOrder(3);
        jdbc.update("UPDATE orders SET status = 'PAID', settling_confirmation_id = ? WHERE id = ?", UUID.randomUUID(), settling);

        for (UUID order : new UUID[] {unpaid, fulfilled, settling}) {
            MvcResult result = swap(manager(), order, lineOf(order), REPLACEMENT, REASON, null);
            assertThat(result.getResponse().getStatus()).as(order.toString()).isEqualTo(409);
        }
        verify(inventory, never()).swapBox(any());
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
}
