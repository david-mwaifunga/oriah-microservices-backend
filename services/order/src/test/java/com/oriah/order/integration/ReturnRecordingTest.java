package com.oriah.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.service.CorrectionJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Recording pieces that came back after an order was dispatched or handed over (Phase 12, plan 4.3). The customer is owed the pieces' own price less
 * their own discount: never delivery, never the box. The order's status does not change and its paid rows are never rewritten: the return is a record
 * of its own that points back at the sale's lines. Each piece that came back is held at inventory, not for sale, until someone looks at it.
 */
class ReturnRecordingTest extends ReturnTestBase {

    @Autowired
    private CorrectionJob job;

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private static String changedMind(UUID line, int quantity) {
        return "{\"reasonCode\":\"CHANGED_MIND\"," + lines(line, quantity) + "}";
    }

    // ------------------------------------------------------------------ the plan's example

    @Test
    @DisplayName("one of two rings comes back: K100.00 less its K10.00 promotion is owed (K90.00), the piece is held for assessment under an id of its own, the order and its paid rows are untouched, and order.return_recorded says what is owed")
    void onePieceOfTwo() throws Exception {
        inventoryReverses();
        UUID id = collectedRings(2);
        UUID line = lineOf(id);
        Map<String, Object> orderBefore = row(id);
        Map<String, Object> lineBefore = jdbc.queryForMap("SELECT * FROM order_items WHERE id = ?", line);
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        JsonNode view = body(recordReturn(manager(), id, key(), changedMind(line, 1)), 201);

        UUID returnId = UUID.fromString(view.get("returnId").asText());
        UUID pieceId = UUID.nameUUIDFromBytes(("return-piece:" + returnId + ":" + line + ":1").getBytes(StandardCharsets.UTF_8));
        assertThat(view.get("orderId").asText()).isEqualTo(id.toString());
        assertThat(view.get("receiptNumber").asText()).isEqualTo(orderBefore.get("receipt_number"));
        assertThat(view.get("state").asText()).isEqualTo("DONE");
        assertThat(view.get("reasonCode").asText()).isEqualTo("CHANGED_MIND");
        assertThat(view.get("owedGross").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(view.get("feeBearer").asText()).isEqualTo("CUSTOMER");
        assertThat(view.get("currency").asText()).isEqualTo("ZMW");
        assertThat(view.get("replay").asBoolean()).isFalse();
        assertThat(view.has("exchange")).isFalse();
        JsonNode returnedLine = view.get("lines").get(0);
        assertThat(view.get("lines")).hasSize(1);
        assertThat(returnedLine.get("lineId").asText()).isEqualTo(line.toString());
        assertThat(returnedLine.get("title").asText()).isEqualTo("Aurelia 18k / Ring Size M");
        assertThat(returnedLine.get("quantity").asInt()).isEqualTo(1);
        assertThat(returnedLine.get("unitPrice").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(returnedLine.get("discountPerPiece").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(returnedLine.get("lineRefund").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(view.get("pieces")).hasSize(1);
        assertThat(view.get("pieces").get(0).get("pieceId").asText()).isEqualTo(pieceId.toString());
        assertThat(view.get("pieces").get(0).get("lineId").asText()).isEqualTo(line.toString());
        assertThat(view.get("pieces").get(0).get("pieceNo").asInt()).isEqualTo(1);
        assertThat(view.get("pieces").get(0).get("state").asText()).isEqualTo("AWAITING");

        // Inventory was asked to take back this one piece, under the id order minted for it; nothing of a cancellation.
        ArgumentCaptor<InventoryClient.ReversalAsk> asked = ArgumentCaptor.forClass(InventoryClient.ReversalAsk.class);
        verify(inventory, times(1)).reverseSale(asked.capture());
        InventoryClient.ReversalAsk ask = asked.getValue();
        assertThat(ask.reversalId()).isEqualTo(returnId);
        assertThat(ask.kind()).isEqualTo("RETURN");
        assertThat(ask.orderId()).isEqualTo(id);
        assertThat(ask.reservationId()).isNull();
        assertThat(ask.pieces()).containsExactly(new InventoryClient.PieceAsk(pieceId, line, RING));
        assertThat(ask.goods()).isEmpty();
        assertThat(ask.boxes()).isEmpty();

        Map<String, Object> correction = corrections(id).get(0);
        assertThat(correction.get("kind")).isEqualTo("RETURN");
        assertThat(correction.get("state")).isEqualTo("DONE");
        assertThat(correction.get("status_before")).isEqualTo("DELIVERED");
        assertThat(correction.get("delivery_refunded").toString()).isEqualTo("0.00");
        assertThat(at(correction.get("occurred_at"))).isBetween(before, Instant.now());
        assertThat(ask.occurredAt()).isEqualTo(at(correction.get("occurred_at")));
        assertThat(Instant.parse(view.get("returnedAt").asText())).isEqualTo(at(correction.get("occurred_at")));
        assertThat(correction.get("recorded_by")).isEqualTo("user:kc-maya");

        // The paid rows are never rewritten, and a return does not change the order's status.
        assertThat(row(id)).isEqualTo(orderBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM order_items WHERE id = ?", line)).isEqualTo(lineBefore);

        List<Map<String, Object>> events = events(id, "order.return_recorded");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("actor")).isEqualTo("user:kc-maya");
        assertThat(at(events.get(0).get("occurred_at"))).isEqualTo(at(correction.get("occurred_at")));
        JsonNode payload = payloadOf(events.get(0));
        assertThat(payload.get("kind").asText()).isEqualTo("RETURN");
        assertThat(payload.get("statusBefore").asText()).isEqualTo("DELIVERED");
        assertThat(payload.get("statusAfter").asText()).isEqualTo("DELIVERED");
        assertThat(payload.get("goodsAtList").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(payload.get("discounts").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(payload.get("deliveryRefunded").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(payload.get("owedGross").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(payload.get("lines").get(0).get("quantity").asInt()).isEqualTo(1);
        assertThat(payload.get("lines").get(0).get("promotionId").asText()).isEqualTo(PROMOTION.toString());
        assertThat(payload.get("pieces")).hasSize(1);
        assertThat(payload.get("pieces").get(0).get("pieceId").asText()).isEqualTo(pieceId.toString());
        assertThat(events(id, "order.sale_cancelled")).isEmpty();

        // Reading it back.
        JsonNode read = body(mvc.perform(get("/api/v1/admin/orders/returns/" + returnId).with(manager())).andReturn(), 200);
        assertThat(read.get("returnId").asText()).isEqualTo(returnId.toString());
        assertThat(read.get("owedGross").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(read.get("replay").asBoolean()).isFalse();
        assertThat(mvc.perform(get("/api/v1/admin/orders/returns/" + UUID.randomUUID()).with(manager())).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a return gives back the goods only: a parcel that was delivered for K45.00 owes the ring's K4,500.00 and nothing for delivery")
    void goodsOnlyNeverDelivery() throws Exception {
        inventoryReverses();
        UUID id = shippedRing();

        JsonNode view = body(recordReturn(manager(), id, key(), "{\"reasonCode\":\"NOT_DELIVERED\"," + lines(lineOf(id), 1) + "}"), 201);

        assertThat(view.get("owedGross").decimalValue()).isEqualByComparingTo("4500.00");
        Map<String, Object> correction = corrections(id).get(0);
        assertThat(correction.get("status_before")).isEqualTo("SHIPPED");
        assertThat(correction.get("delivery_refunded").toString()).isEqualTo("0.00");
        assertThat(correction.get("waiver_taken_back").toString()).isEqualTo("0.00");
        assertThat(row(id).get("status")).as("a return never changes the status").isEqualTo("SHIPPED");
        assertThat(row(id).get("recipient_name")).as("and the parcel's details stay").isEqualTo("Mulenga Chanda");
    }

    // ------------------------------------------------------------------ when it may be recorded

    @Test
    @DisplayName("only an order that was dispatched or handed over takes a return: a paid one is cancelled instead (409 return-not-allowed, use the-paid-cancellation), an unpaid one has nothing to return, an unknown one is 404")
    void onlyOnceDispatchedOrHandedOver() throws Exception {
        UUID unpaid = heldOrder(3);
        UUID paid = paidRings(2);
        UUID packed = paidRings(1);
        stepped(packed, "pack", "{}");
        for (UUID notGone : new UUID[] {paid, packed}) {
            MvcResult refused = recordReturn(manager(), notGone, key(), changedMind(lineOf(notGone), 1));
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(type(refused)).endsWith("/return-not-allowed");
            assertThat(json.readTree(refused.getResponse().getContentAsString()).get("use").asText()).isEqualTo("the-paid-cancellation");
        }
        MvcResult notPaid = recordReturn(manager(), unpaid, key(), changedMind(lineOf(unpaid), 1));
        assertThat(type(notPaid)).endsWith("/return-not-allowed");
        assertThat(json.readTree(notPaid.getResponse().getContentAsString()).has("use")).isFalse();
        assertThat(recordReturn(manager(), UUID.randomUUID(), key(), changedMind(UUID.randomUUID(), 1)).getResponse().getStatus()).isEqualTo(404);

        verify(inventory, never()).reverseSale(any());
        assertThat(corrections(paid)).isEmpty();
    }

    @Test
    @DisplayName("never more than was sold: 409 more-than-was-sold naming the line, what was sold and what already came back; a line that is not this order's, a quantity below one, a line named twice or no line at all is 400")
    void neverMoreThanWasSold() throws Exception {
        inventoryReverses();
        UUID id = collectedRings(2);
        UUID line = lineOf(id);

        MvcResult tooMany = recordReturn(manager(), id, key(), changedMind(line, 3));
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(tooMany)).endsWith("/more-than-was-sold");
        JsonNode problem = json.readTree(tooMany.getResponse().getContentAsString());
        assertThat(problem.get("lineId").asText()).isEqualTo(line.toString());
        assertThat(problem.get("sold").asInt()).isEqualTo(2);
        assertThat(problem.get("alreadyReturned").asInt()).isZero();

        MvcResult notALine = recordReturn(manager(), id, key(), changedMind(UUID.randomUUID(), 1));
        assertThat(notALine.getResponse().getStatus()).isEqualTo(400);
        assertThat(type(notALine)).endsWith("/invalid-line");
        MvcResult twice = recordReturn(manager(), id, key(), "{\"reasonCode\":\"CHANGED_MIND\",\"lines\":[{\"lineId\":\"" + line + "\",\"quantity\":1},{\"lineId\":\""
                + line + "\",\"quantity\":1}]}");
        assertThat(twice.getResponse().getStatus()).isEqualTo(400);
        assertThat(type(twice)).endsWith("/invalid-line");
        assertThat(recordReturn(manager(), id, key(), changedMind(line, 0)).getResponse().getStatus()).isEqualTo(400);
        assertThat(recordReturn(manager(), id, key(), "{\"reasonCode\":\"CHANGED_MIND\",\"lines\":[]}").getResponse().getStatus()).isEqualTo(400);
        assertThat(recordReturn(manager(), id, key(), "{\"reasonCode\":\"CUSTOMER_ASKED\"," + lines(line, 1) + "}").getResponse().getStatus())
                .as("a cancellation's reason is not a return's").isEqualTo(400);
        assertThat(recordReturn(manager(), id, key(), "{\"reasonCode\":\"OTHER\"," + lines(line, 1) + "}").getResponse().getStatus())
                .as("other says why").isEqualTo(400);

        assertThat(corrections(id)).isEmpty();
        verify(inventory, never()).reverseSale(any());
    }

    @Test
    @DisplayName("two returns of one line add up: the second, the same pieces minutes later, must be confirmed (409 similar-return-just-recorded); its piece has its own id; a third is more than was sold")
    void twoReturnsOfOneLineAddUp() throws Exception {
        inventoryReverses();
        UUID id = collectedRings(2);
        UUID line = lineOf(id);
        UUID first = UUID.fromString(body(recordReturn(manager(), id, key(), changedMind(line, 1)), 201).get("returnId").asText());

        MvcResult similar = recordReturn(manager(), id, key(), changedMind(line, 1));
        assertThat(similar.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(similar)).endsWith("/similar-return-just-recorded");
        assertThat(json.readTree(similar.getResponse().getContentAsString()).get("returnId").asText()).isEqualTo(first.toString());
        assertThat(corrections(id)).hasSize(1);

        JsonNode second = body(recordReturn(manager(), id, key(), "{\"reasonCode\":\"CHANGED_MIND\",\"confirmDuplicate\":true," + lines(line, 1) + "}"), 201);

        UUID secondId = UUID.fromString(second.get("returnId").asText());
        assertThat(secondId).isNotEqualTo(first);
        assertThat(second.get("pieces").get(0).get("pieceId").asText()).isNotEqualTo(pieces(first).get(0).get("id").toString());
        assertThat(second.get("owedGross").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(events(id, "order.return_recorded")).hasSize(2);

        MvcResult third = recordReturn(manager(), id, key(), "{\"reasonCode\":\"CHANGED_MIND\",\"confirmDuplicate\":true," + lines(line, 1) + "}");
        assertThat(type(third)).endsWith("/more-than-was-sold");
        assertThat(json.readTree(third.getResponse().getContentAsString()).get("alreadyReturned").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("a parcel whose every piece came back is not marked delivered: 409 fully-returned")
    void aFullyReturnedOrderIsNotDelivered() throws Exception {
        inventoryReverses();
        UUID id = shippedRing();
        returned(id, 1);

        MvcResult deliver = step(id, "deliver", "{}");

        assertThat(deliver.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(deliver)).endsWith("/fully-returned");
        assertThat(row(id).get("status")).isEqualTo("SHIPPED");
    }

    // ------------------------------------------------------------------ when it came back

    @Test
    @DisplayName("a return is dated when the parcel came back, which may be earlier than when it is typed: never before the order was handed over or dispatched, never in the future")
    void datedWhenItCameBack() throws Exception {
        inventoryReverses();
        UUID id = collectedRings(2);
        UUID line = lineOf(id);
        Instant handedOver = at(jdbc.queryForMap("SELECT occurred_at FROM order_dispatch_step WHERE order_id = ? AND step = 'DELIVERED'", id).get("occurred_at"));

        MvcResult tooEarly = recordReturn(manager(), id, key(), "{\"reasonCode\":\"FAULTY\",\"returnedAt\":\"" + handedOver.minusSeconds(60) + "\"," + lines(line, 1) + "}");
        assertThat(tooEarly.getResponse().getStatus()).isEqualTo(400);
        assertThat(type(tooEarly)).endsWith("/invalid-occurred-at");
        MvcResult future = recordReturn(manager(), id, key(), "{\"reasonCode\":\"FAULTY\",\"returnedAt\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"," + lines(line, 1) + "}");
        assertThat(future.getResponse().getStatus()).isEqualTo(400);
        assertThat(type(future)).endsWith("/invalid-occurred-at");
        assertThat(corrections(id)).isEmpty();

        JsonNode view = body(recordReturn(manager(), id, key(), "{\"reasonCode\":\"FAULTY\",\"returnedAt\":\"" + handedOver + "\"," + lines(line, 1) + "}"), 201);

        assertThat(Instant.parse(view.get("returnedAt").asText())).isEqualTo(handedOver);
        assertThat(at(corrections(id).get(0).get("occurred_at"))).isEqualTo(handedOver);
        assertThat(at(events(id, "order.return_recorded").get(0).get("occurred_at"))).isEqualTo(handedOver);
        assertThat(corrections(id).get(0).get("backdate_reason")).isNull();
    }

    @Test
    @DisplayName("a parcel that came back more than 72 hours ago is recorded at its true date, by someone who may back-date and says why: 403 without the authority, 400 without the reason")
    void olderThan72HoursNeedsTheAuthorityAndAReason() throws Exception {
        inventoryReverses();
        UUID id = paidRings(2);
        UUID line = lineOf(id);
        // The money arrived days ago (ARRIVED): the order was packed and collected that same morning, and one ring came back that afternoon.
        Instant arrived = Instant.parse(ARRIVED);
        stepped(id, "pack", "{\"occurredAt\":\"" + arrived.plusSeconds(3600) + "\",\"backdateReason\":\"keyed after the weekend\"}");
        stepped(id, "deliver", "{\"occurredAt\":\"" + arrived.plusSeconds(7200) + "\",\"backdateReason\":\"keyed after the weekend\"}");
        String cameBack = arrived.plusSeconds(6 * 3600).toString();

        MvcResult noAuthority = recordReturn(manager(), id, key(), "{\"reasonCode\":\"WRONG_SIZE\",\"returnedAt\":\"" + cameBack
                + "\",\"backdateReason\":\"the slip was found today\"," + lines(line, 1) + "}");
        assertThat(noAuthority.getResponse().getStatus()).isEqualTo(403);
        MvcResult noReason = recordReturn(owner(), id, key(), "{\"reasonCode\":\"WRONG_SIZE\",\"returnedAt\":\"" + cameBack + "\"," + lines(line, 1) + "}");
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(type(noReason)).endsWith("/backdate-reason-required");
        MvcResult emojiReason = recordReturn(owner(), id, key(), "{\"reasonCode\":\"WRONG_SIZE\",\"returnedAt\":\"" + cameBack
                + "\",\"backdateReason\":\"\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\"," + lines(line, 1) + "}");
        assertThat(emojiReason.getResponse().getStatus()).as("three emoji are three characters, not six").isEqualTo(400);
        assertThat(type(emojiReason)).endsWith("/backdate-reason-required");
        assertThat(recordReturn(owner(), id, key(), "{\"reasonCode\":\"OTHER\",\"note\":\"\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\"," + lines(line, 1) + "}").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(corrections(id)).isEmpty();

        body(recordReturn(owner(), id, key(), "{\"reasonCode\":\"WRONG_SIZE\",\"returnedAt\":\"" + cameBack
                + "\",\"backdateReason\":\"  the slip was found today  \"," + lines(line, 1) + "}"), 201);

        Map<String, Object> correction = corrections(id).get(0);
        assertThat(at(correction.get("occurred_at"))).isEqualTo(Instant.parse(cameBack));
        assertThat(correction.get("backdate_reason")).isEqualTo("the slip was found today");
        assertThat(correction.get("recorded_by")).isEqualTo("user:kc-olive");
        assertThat((String) events(id, "order.return_recorded").get(0).get("payload")).doesNotContain("slip was found");
    }

    // ------------------------------------------------------------------ repeats

    @Test
    @DisplayName("the same key with the same request is the same return (200, replay) and asks inventory nothing more; the same key for anything else is 409 idempotency-key-reused; a key is required, 1 to 128 characters")
    void idempotentByKey() throws Exception {
        inventoryReverses();
        UUID id = collectedRings(2);
        UUID line = lineOf(id);
        String key = key();
        JsonNode first = body(recordReturn(manager(), id, key, changedMind(line, 1)), 201);

        JsonNode again = body(recordReturn(manager(), id, key, changedMind(line, 1)), 200);

        assertThat(again.get("returnId").asText()).isEqualTo(first.get("returnId").asText());
        assertThat(again.get("replay").asBoolean()).isTrue();
        verify(inventory, times(1)).reverseSale(any());
        assertThat(corrections(id)).hasSize(1);
        assertThat(events(id, "order.return_recorded")).hasSize(1);

        // The key answers BEFORE any rule: even once every piece has come back, the same request is still the same return.
        returned(id, 1);
        assertThat(body(recordReturn(manager(), id, key, changedMind(line, 1)), 200).get("returnId").asText()).isEqualTo(first.get("returnId").asText());

        MvcResult other = recordReturn(manager(), id, key, "{\"reasonCode\":\"FAULTY\"," + lines(line, 1) + "}");
        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(other)).endsWith("/idempotency-key-reused");
        UUID another = collectedRings(1);
        MvcResult otherOrder = recordReturn(manager(), another, key, changedMind(lineOf(another), 1));
        assertThat(type(otherOrder)).endsWith("/idempotency-key-reused");

        assertThat(recordReturn(manager(), id, null, changedMind(line, 1)).getResponse().getStatus()).isEqualTo(400);
        assertThat(recordReturn(manager(), id, "k".repeat(129), changedMind(line, 1)).getResponse().getStatus()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ in flight

    @Test
    @DisplayName("inventory not answering leaves the return in flight (202): nothing is announced, no other return or delivery step is taken meanwhile, and the job finishes it once inventory says it holds the reversal")
    void aCrashIsFinishedByTheJob() throws Exception {
        inventoryAnswersTheReversal(503, null);
        UUID id = shippedRing();
        UUID line = lineOf(id);
        String key = key();

        JsonNode view = body(recordReturn(manager(), id, key, "{\"reasonCode\":\"DAMAGED_IN_TRANSIT\"," + lines(line, 1) + "}"), 202);

        UUID returnId = UUID.fromString(view.get("returnId").asText());
        assertThat(view.get("state").asText()).isEqualTo("REQUESTED");
        assertThat(events(id, "order.return_recorded")).isEmpty();
        MvcResult another = recordReturn(manager(), id, key(), "{\"reasonCode\":\"FAULTY\"," + lines(line, 1) + "}");
        assertThat(another.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(another)).endsWith("/correction-in-progress");
        assertThat(type(step(id, "deliver", "{}"))).endsWith("/correction-in-progress");

        due(returnId);
        inventoryHasTheReversal();
        job.run();

        assertThat(corrections(id).get(0).get("state")).isEqualTo("DONE");
        List<Map<String, Object>> events = events(id, "order.return_recorded");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("actor")).isEqualTo("user:kc-maya");
        assertThat(body(recordReturn(manager(), id, key, "{\"reasonCode\":\"DAMAGED_IN_TRANSIT\"," + lines(line, 1) + "}"), 200).get("state").asText()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("a refusal that proves nothing moved ends that attempt (409 return-refused): its pieces do not count as returned, the same key answers the refusal again, and a new key is a new attempt")
    void aProvenRefusalEndsTheAttempt() throws Exception {
        inventoryAnswersTheReversal(409, "not-a-sale");
        UUID id = collectedRings(1);
        UUID line = lineOf(id);
        String key = key();

        MvcResult refused = recordReturn(manager(), id, key, changedMind(line, 1));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(refused)).endsWith("/return-refused");
        assertThat(json.readTree(refused.getResponse().getContentAsString()).get("refusedCode").asText()).isEqualTo("not-a-sale");
        assertThat(corrections(id).get(0).get("state")).isEqualTo("REFUSED");
        assertThat(events(id, "order.return_recorded")).isEmpty();

        inventoryReverses();
        MvcResult sameKey = recordReturn(manager(), id, key, changedMind(line, 1));
        assertThat(sameKey.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(sameKey)).endsWith("/return-refused");
        verify(inventory, times(1)).reverseSale(any());

        JsonNode second = body(recordReturn(manager(), id, key(), changedMind(line, 1)), 201);
        assertThat(second.get("state").asText()).isEqualTo("DONE");
        assertThat(corrections(id)).hasSize(2);
    }

    @Test
    @DisplayName("a sale from before lines kept their product and discount is refused, never guessed: 409 order-too-old-to-correct")
    void legacyLinesAreRefusedNeverGuessed() throws Exception {
        UUID id = collectedRings(1);
        UUID line = lineOf(id);
        jdbc.update("UPDATE order_items SET discount_per_piece = NULL WHERE id = ?", line);

        MvcResult refused = recordReturn(manager(), id, key(), changedMind(line, 1));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(refused)).endsWith("/order-too-old-to-correct");
        assertThat(corrections(id)).isEmpty();
    }

    // ------------------------------------------------------------------ who

    @Test
    @DisplayName("only an order administrator records or reads a return: a salesperson, a packer and a shopper are 403, no token is 401")
    void whoMay() throws Exception {
        UUID id = collectedRings(1);
        UUID line = lineOf(id);
        for (String role : new String[] {"ROLE_SALES_AGENT", "ROLE_FULFILMENT_AGENT", "ROLE_USER", "ROLE_PAYMENT_ADMIN"}) {
            RequestPostProcessor who = jwt().authorities(new SimpleGrantedAuthority(role)).jwt(j -> j.subject("kc-someone"));
            assertThat(recordReturn(who, id, key(), changedMind(line, 1)).getResponse().getStatus()).as(role).isEqualTo(403);
            assertThat(mvc.perform(get("/api/v1/admin/orders/returns/" + UUID.randomUUID()).with(who)).andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
        }
        assertThat(mvc.perform(post("/api/v1/admin/orders/" + id + "/returns").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(changedMind(line, 1))).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(corrections(id)).isEmpty();
        verify(inventory, never()).reverseSale(any());
    }
}
