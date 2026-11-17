package com.oriah.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.CatalogMarkdownClient;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.service.CorrectionJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Looking at a piece that came back (Phase 12, plan 4.6): fine (it goes back on sale), marked down (it is sold again on its own, cheaper, under a
 * listing of its own) or not sellable (it is written off). The piece is held at inventory, not for sale, until inventory has recorded the outcome, so
 * a failure on the way always means "not on sale", never "on sale wrongly". Order decides who may say what; inventory moves the stock.
 */
class ReturnAssessmentTest extends ReturnTestBase {

    @Autowired
    private CorrectionJob job;

    /** A return of one ring of two, received: its id and its one piece's id. */
    private UUID[] returnedPiece() throws Exception {
        inventoryReverses();
        UUID order = collectedRings(2);
        JsonNode view = returned(order, 1);
        return new UUID[] {UUID.fromString(view.get("returnId").asText()), UUID.fromString(view.get("pieces").get(0).get("pieceId").asText()), order};
    }

    private MvcResult assess(RequestPostProcessor who, UUID returnId, UUID pieceId, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/orders/returns/" + returnId + "/pieces/" + pieceId + "/assessment").with(who)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private Map<String, Object> piece(UUID pieceId) {
        return jdbc.queryForMap("SELECT * FROM order_return_piece WHERE id = ?", pieceId);
    }

    /** Inventory recording whatever outcome it is asked to. */
    private void inventoryDecides() {
        doAnswer(inv -> {
            InventoryClient.PieceOutcomeAsk ask = inv.getArgument(1);
            return new InventoryClient.PieceDecision(inv.getArgument(0), ask.outcome(), RING, ask.childVariantId(), ask.occurredAt(), false);
        }).when(inventory).decidePiece(any(), any());
    }

    private void inventoryAnswersTheOutcome(int status, String type) {
        doThrow(SaleStubs.feign(status, problem(status, type))).when(inventory).decidePiece(any(), any());
    }

    private static UUID markdownVariant(UUID pieceId) {
        return UUID.nameUUIDFromBytes(("markdown:" + pieceId).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ the three outcomes

    @Test
    @DisplayName("fine: inventory lets the piece go back on sale, the piece is DONE, catalog is not asked; the same answer again is a replay")
    void fine() throws Exception {
        UUID[] ids = returnedPiece();
        inventoryDecides();

        JsonNode view = body(assess(manager(), ids[0], ids[1], "{\"outcome\":\"FINE\"}"), 200);

        assertThat(view.get("pieceId").asText()).isEqualTo(ids[1].toString());
        assertThat(view.get("state").asText()).isEqualTo("DONE");
        assertThat(view.get("outcome").asText()).isEqualTo("FINE");
        assertThat(view.get("replay").asBoolean()).isFalse();
        assertThat(view.has("markdownVariantId")).isFalse();
        Map<String, Object> row = piece(ids[1]);
        assertThat(row.get("state")).isEqualTo("DONE");
        assertThat(row.get("outcome")).isEqualTo("FINE");
        assertThat(row.get("asked_by")).isEqualTo("user:kc-maya");
        assertThat(row.get("assessed_at")).isNotNull();
        assertThat(Instant.parse(view.get("assessedAt").asText())).isEqualTo(at(row.get("assessed_at")));
        ArgumentCaptor<InventoryClient.PieceOutcomeAsk> asked = ArgumentCaptor.forClass(InventoryClient.PieceOutcomeAsk.class);
        verify(inventory, times(1)).decidePiece(eq(ids[1]), asked.capture());
        assertThat(asked.getValue().outcome()).isEqualTo("RELEASED");
        assertThat(asked.getValue().childVariantId()).isNull();
        assertThat(asked.getValue().occurredAt()).isEqualTo(at(row.get("asked_at")));
        verify(catalogMarkdown, never()).create(any(), any());

        JsonNode again = body(assess(manager(), ids[0], ids[1], "{\"outcome\":\"FINE\"}"), 200);
        assertThat(again.get("replay").asBoolean()).isTrue();
        verify(inventory, times(1)).decidePiece(any(), any());

        // The return shows it, and leaves the work list once every piece is looked at.
        JsonNode read = body(mvc.perform(get("/api/v1/admin/orders/returns/" + ids[0]).with(manager())).andReturn(), 200);
        assertThat(read.get("pieces").get(0).get("state").asText()).isEqualTo("DONE");
        assertThat(read.get("pieces").get(0).get("outcome").asText()).isEqualTo("FINE");
        JsonNode waiting = body(mvc.perform(get("/api/v1/admin/orders/returns").with(manager())).andReturn(), 200);
        assertThat(waiting.findValuesAsText("returnId")).doesNotContain(ids[0].toString());
        JsonNode all = body(mvc.perform(get("/api/v1/admin/orders/returns").param("state", "ALL").with(manager())).andReturn(), 200);
        assertThat(all.findValuesAsText("returnId")).contains(ids[0].toString());
        assertThat(mvc.perform(get("/api/v1/admin/orders/returns").param("state", "SOMETHING").with(manager())).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("marked down: catalog first makes the piece a listing of its own (with the person's own token, an id derived from the piece), then inventory moves the piece to it")
    void markedDownMakesTheVariantThenMovesThePiece() throws Exception {
        UUID[] ids = returnedPiece();
        inventoryDecides();
        UUID child = markdownVariant(ids[1]);

        // While it waits, the return is on the work list.
        JsonNode waiting = body(mvc.perform(get("/api/v1/admin/orders/returns").with(manager())).andReturn(), 200);
        assertThat(waiting.findValuesAsText("returnId")).contains(ids[0].toString());

        JsonNode view = body(assess(owner(), ids[0], ids[1], "{\"outcome\":\"MARKED_DOWN\"}"), 200);

        assertThat(view.get("state").asText()).isEqualTo("DONE");
        assertThat(view.get("outcome").asText()).isEqualTo("MARKED_DOWN");
        assertThat(view.get("markdownVariantId").asText()).isEqualTo(child.toString());
        InOrder order = inOrder(catalogMarkdown, inventory);
        order.verify(catalogMarkdown).create(PRODUCT, new CatalogMarkdownClient.MarkdownAsk(child, RING, ids[1]));
        ArgumentCaptor<InventoryClient.PieceOutcomeAsk> asked = ArgumentCaptor.forClass(InventoryClient.PieceOutcomeAsk.class);
        order.verify(inventory).decidePiece(eq(ids[1]), asked.capture());
        assertThat(asked.getValue().outcome()).isEqualTo("RELISTED");
        assertThat(asked.getValue().childVariantId()).isEqualTo(child);
        assertThat(piece(ids[1]).get("markdown_variant_id")).isEqualTo(child);
        assertThat(piece(ids[1]).get("asked_by")).isEqualTo("user:kc-olive");
    }

    @Test
    @DisplayName("not sellable: needs the authority to write stock off and a note saying why; inventory writes the piece off")
    void unsellableNeedsTheWriteOffAuthorityAndANote() throws Exception {
        UUID[] ids = returnedPiece();
        inventoryDecides();

        assertThat(assess(manager(), ids[0], ids[1], "{\"outcome\":\"UNSELLABLE\",\"note\":\"the clasp is broken\"}").getResponse().getStatus())
                .as("an order administrator without STOCK_WRITE_OFF").isEqualTo(403);
        MvcResult noNote = assess(owner(), ids[0], ids[1], "{\"outcome\":\"UNSELLABLE\"}");
        assertThat(noNote.getResponse().getStatus()).isEqualTo(400);
        assertThat(type(noNote)).endsWith("/note-required");
        assertThat(assess(owner(), ids[0], ids[1], "{\"outcome\":\"UNSELLABLE\",\"note\":\"no\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(assess(owner(), ids[0], ids[1], "{\"outcome\":\"LOST\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(piece(ids[1]).get("state")).isEqualTo("AWAITING");
        verify(inventory, never()).decidePiece(any(), any());

        JsonNode view = body(assess(owner(), ids[0], ids[1], "{\"outcome\":\"UNSELLABLE\",\"note\":\"  the clasp is broken  \"}"), 200);

        assertThat(view.get("outcome").asText()).isEqualTo("UNSELLABLE");
        ArgumentCaptor<InventoryClient.PieceOutcomeAsk> asked = ArgumentCaptor.forClass(InventoryClient.PieceOutcomeAsk.class);
        verify(inventory).decidePiece(eq(ids[1]), asked.capture());
        assertThat(asked.getValue().outcome()).isEqualTo("WRITTEN_OFF");
        assertThat(piece(ids[1]).get("note")).isEqualTo("the clasp is broken");
        assertThat((String) view.toString()).doesNotContain("clasp");
    }

    @Test
    @DisplayName("marking down needs the authority to manage the catalogue: an order administrator without it is 403 before catalog or inventory is asked")
    void markedDownNeedsCatalogAdmin() throws Exception {
        UUID[] ids = returnedPiece();

        assertThat(assess(manager(), ids[0], ids[1], "{\"outcome\":\"MARKED_DOWN\"}").getResponse().getStatus()).isEqualTo(403);

        verify(catalogMarkdown, never()).create(any(), any());
        verify(inventory, never()).decidePiece(any(), any());
        assertThat(piece(ids[1]).get("state")).isEqualTo("AWAITING");
    }

    // ------------------------------------------------------------------ when something on the way fails

    @Test
    @DisplayName("catalog failing leaves the piece undecided: nothing is written and inventory is not asked, so another outcome can still be chosen")
    void catalogFailingLeavesThePieceUndecided() throws Exception {
        UUID[] ids = returnedPiece();
        inventoryDecides();

        doThrow(SaleStubs.feign(503, null)).when(catalogMarkdown).create(any(), any());
        MvcResult down = assess(owner(), ids[0], ids[1], "{\"outcome\":\"MARKED_DOWN\"}");
        assertThat(down.getResponse().getStatus()).as(down.getResponse().getContentAsString()).isEqualTo(503);
        doThrow(SaleStubs.feign(409, problem(409, "sku-unavailable"))).when(catalogMarkdown).create(any(), any());
        MvcResult refused = assess(owner(), ids[0], ids[1], "{\"outcome\":\"MARKED_DOWN\"}");
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(refused)).endsWith("/sku-unavailable");

        assertThat(piece(ids[1]).get("state")).isEqualTo("AWAITING");
        assertThat(piece(ids[1]).get("outcome")).isNull();
        verify(inventory, never()).decidePiece(any(), any());

        assertThat(body(assess(owner(), ids[0], ids[1], "{\"outcome\":\"FINE\"}"), 200).get("outcome").asText()).isEqualTo("FINE");
    }

    @Test
    @DisplayName("inventory has not heard of the new listing yet: the piece stays decided and in progress (202), and the job finishes it without anybody's token")
    void theVariantNotYetKnownIsRetriedByTheJob() throws Exception {
        UUID[] ids = returnedPiece();
        inventoryAnswersTheOutcome(409, "markdown-variant-not-known-yet");

        JsonNode view = body(assess(owner(), ids[0], ids[1], "{\"outcome\":\"MARKED_DOWN\"}"), 202);

        assertThat(view.get("state").asText()).isEqualTo("IN_PROGRESS");
        assertThat(view.get("outcome").asText()).isEqualTo("MARKED_DOWN");
        Map<String, Object> waiting = piece(ids[1]);
        assertThat(waiting.get("attempts")).isEqualTo(1);
        assertThat(at(waiting.get("next_attempt_at"))).isAfter(Instant.now().plusSeconds(10));

        // Not due yet: a pass does nothing.
        job.run();
        verify(inventory, times(1)).decidePiece(any(), any());

        jdbc.update("UPDATE order_return_piece SET next_attempt_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), ids[1]);
        inventoryDecides();
        job.run();

        assertThat(piece(ids[1]).get("state")).isEqualTo("DONE");
        ArgumentCaptor<InventoryClient.PieceOutcomeAsk> asked = ArgumentCaptor.forClass(InventoryClient.PieceOutcomeAsk.class);
        verify(inventory, times(2)).decidePiece(eq(ids[1]), asked.capture());
        assertThat(asked.getAllValues().get(1)).as("the very same request").isEqualTo(asked.getAllValues().get(0));
        verify(catalogMarkdown, times(1)).create(any(), any());
    }

    @Test
    @DisplayName("a refusal that proves inventory did not record it puts the piece back to waiting, whole (409 with inventory's reason), so another outcome can be chosen")
    void aProvenRefusalGoesBackToAwaiting() throws Exception {
        UUID[] ids = returnedPiece();
        inventoryAnswersTheOutcome(409, "already-marked-down");

        MvcResult refused = assess(owner(), ids[0], ids[1], "{\"outcome\":\"MARKED_DOWN\"}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(refused)).endsWith("/already-marked-down");
        Map<String, Object> row = piece(ids[1]);
        assertThat(row.get("state")).isEqualTo("AWAITING");
        assertThat(row.get("outcome")).isNull();
        assertThat(row.get("markdown_variant_id")).isNull();
        assertThat(row.get("asked_by")).isNull();
        assertThat(row.get("attempts")).isEqualTo(0);

        inventoryDecides();
        assertThat(body(assess(owner(), ids[0], ids[1], "{\"outcome\":\"UNSELLABLE\",\"note\":\"it came back scratched\"}"), 200).get("outcome").asText())
                .isEqualTo("UNSELLABLE");
    }

    // ------------------------------------------------------------------ once decided

    @Test
    @DisplayName("once decided it stays: another outcome for a piece that is done is 409 piece-already-assessed; while one is with inventory, another is 409 assessment-in-progress and the same one carries on")
    void onceDecidedItStays() throws Exception {
        UUID[] ids = returnedPiece();
        inventoryAnswersTheOutcome(503, null);

        assertThat(body(assess(manager(), ids[0], ids[1], "{\"outcome\":\"FINE\"}"), 202).get("state").asText()).isEqualTo("IN_PROGRESS");
        MvcResult other = assess(owner(), ids[0], ids[1], "{\"outcome\":\"UNSELLABLE\",\"note\":\"it came back scratched\"}");
        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(other)).endsWith("/assessment-in-progress");
        assertThat(json.readTree(other.getResponse().getContentAsString()).get("outcome").asText()).isEqualTo("FINE");

        inventoryDecides();
        assertThat(body(assess(manager(), ids[0], ids[1], "{\"outcome\":\"FINE\"}"), 200).get("state").asText()).isEqualTo("DONE");
        MvcResult changed = assess(owner(), ids[0], ids[1], "{\"outcome\":\"UNSELLABLE\",\"note\":\"it came back scratched\"}");
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(changed)).endsWith("/piece-already-assessed");
        assertThat(piece(ids[1]).get("outcome")).isEqualTo("FINE");
    }

    @Test
    @DisplayName("a piece is assessed only once its return has been received (409 return-not-received-yet), and only as a piece of that return (404)")
    void onlyAPieceOfAReceivedReturn() throws Exception {
        UUID[] ids = returnedPiece();
        UUID[] other = returnedPiece();
        inventoryAnswersTheReversal(503, null);
        UUID inFlightOrder = collectedRings(1);
        JsonNode inFlight = body(recordReturn(manager(), inFlightOrder, "k-" + UUID.randomUUID(), "{\"reasonCode\":\"FAULTY\"," + lines(lineOf(inFlightOrder), 1) + "}"), 202);
        UUID inFlightReturn = UUID.fromString(inFlight.get("returnId").asText());
        UUID inFlightPiece = UUID.fromString(inFlight.get("pieces").get(0).get("pieceId").asText());

        MvcResult early = assess(manager(), inFlightReturn, inFlightPiece, "{\"outcome\":\"FINE\"}");
        assertThat(early.getResponse().getStatus()).isEqualTo(409);
        assertThat(type(early)).endsWith("/return-not-received-yet");
        assertThat(assess(manager(), ids[0], other[1], "{\"outcome\":\"FINE\"}").getResponse().getStatus()).as("another return's piece").isEqualTo(404);
        assertThat(assess(manager(), ids[0], UUID.randomUUID(), "{\"outcome\":\"FINE\"}").getResponse().getStatus()).isEqualTo(404);
        assertThat(assess(manager(), UUID.randomUUID(), ids[1], "{\"outcome\":\"FINE\"}").getResponse().getStatus()).isEqualTo(404);
        verify(inventory, never()).decidePiece(any(), any());
    }

    @Test
    @DisplayName("only an order administrator assesses a piece: a salesperson, a packer and a shopper are 403")
    void whoMay() throws Exception {
        UUID[] ids = returnedPiece();
        for (String role : new String[] {"ROLE_SALES_AGENT", "ROLE_FULFILMENT_AGENT", "ROLE_USER", "ROLE_STOCK_WRITE_OFF", "ROLE_CATALOG_ADMIN"}) {
            RequestPostProcessor who = jwt().authorities(new SimpleGrantedAuthority(role)).jwt(j -> j.subject("kc-someone"));
            assertThat(assess(who, ids[0], ids[1], "{\"outcome\":\"FINE\"}").getResponse().getStatus()).as(role).isEqualTo(403);
        }
        assertThat(piece(ids[1]).get("state")).isEqualTo("AWAITING");
    }
}
