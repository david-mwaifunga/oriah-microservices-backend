package com.oriah.order.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import com.oriah.order.SaleStubs;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.service.SettlementJob;
import com.oriah.order.service.SettlementService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What happens to confirmed money when inventory does not simply say yes (Phase 7). The sale is applied in three steps with no transaction held across
 * the call to inventory: the settlement is recorded and the order marked as being settled; inventory sells the pieces; the order is made PAID. Every
 * way that can go wrong has a defined ending and none of them loses the money or sells a piece twice:
 *
 * <ul>
 *   <li>inventory does not answer: the settlement waits and the retry job asks again under the SAME reservation id (inventory answers a repeat once);</li>
 *   <li>the hold lapsed meanwhile: the old hold is given back, a new one taken and sold;</li>
 *   <li>the piece is gone: PIECE_SOLD, the money is held for the customer; only the box is gone: the sale waits (BLOCKED_BOX), a paid ring that is
 *       still free is never refunded for a missing box.</li>
 * </ul>
 */
class SettlementSagaTest extends SaleTestBase {

    @Autowired
    private SettlementJob job;

    @Autowired
    private SettlementService settlements;

    private static final String NOT_HELD = "{\"type\":\"https://oriah.dev/problems/reservation-not-held\",\"status\":409,\"detail\":\"released\"}";
    private static final String LAPSED = "{\"type\":\"https://oriah.dev/problems/reservation-expired\",\"status\":409,\"detail\":\"lapsed\"}";

    private void dueNow(UUID confirmation) {
        jdbc.update("UPDATE order_settlement SET next_attempt_at = now() - interval '1 minute' WHERE confirmation_id = ?", confirmation);
    }

    // ------------------------------------------------------------------ inventory does not answer

    @Test
    @DisplayName("inventory silent: the settlement waits CONSUMING (the order is marked as being settled, not PAID), and the retry job finishes it under the SAME reservation id")
    void aSilentInventoryIsRetriedUnderTheSameId() throws Exception {
        UUID id = heldOrder();
        UUID reservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());

        UUID confirmation = confirmed(id, "4500.00");

        Map<String, Object> waiting = settlement(confirmation);
        assertThat(waiting.get("state")).isEqualTo("CONSUMING");
        assertThat(((Number) waiting.get("attempts")).intValue()).isEqualTo(1);
        assertThat(waiting.get("next_attempt_at")).as("when to try again").isNotNull();
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).as("not paid until inventory has sold the pieces").isEqualTo("CREATED");
        assertThat(row.get("settling_confirmation_id")).isEqualTo(confirmation);
        assertThat(events(id, "order.paid")).isEmpty();

        job.run();
        assertThat(settlement(confirmation).get("state")).as("not due yet: the job leaves it").isEqualTo("CONSUMING");
        verify(inventory, times(1)).consume(any(), any());

        SaleStubs.inventorySells(inventory);
        dueNow(confirmation);
        job.run();

        assertThat(row(id).get("status")).isEqualTo("PAID");
        assertThat(settlement(confirmation).get("state")).isEqualTo("DONE");
        assertThat(events(id, "order.paid")).hasSize(1);
        ArgumentCaptor<UUID> asked = ArgumentCaptor.forClass(UUID.class);
        verify(inventory, times(2)).consume(asked.capture(), any());
        assertThat(asked.getAllValues()).as("both attempts name the one reservation").containsOnly(reservation);
    }

    @Test
    @DisplayName("every failed attempt moves the next one further off (15 s, 30 s, 60 s ... never past five minutes), and counts")
    void theRetriesBackOff() throws Exception {
        UUID id = heldOrder();
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        UUID confirmation = confirmed(id, "4500.00");
        long firstWait = java.time.Duration.between(java.time.Instant.now(), at(settlement(confirmation).get("next_attempt_at"))).toSeconds();

        for (int attempt = 2; attempt <= 12; attempt++) {
            dueNow(confirmation);
            job.run();
        }

        Map<String, Object> waiting = settlement(confirmation);
        assertThat(((Number) waiting.get("attempts")).intValue()).isEqualTo(12);
        assertThat(firstWait).isBetween(10L, 16L);
        assertThat(java.time.Duration.between(java.time.Instant.now(), at(waiting.get("next_attempt_at"))).toSeconds()).as("capped").isBetween(250L, 301L);
    }

    @Test
    @DisplayName("while a payment is being applied, the shopper cannot cancel the order and staff cannot move it: both are 409")
    void anOrderBeingSettledCannotBeChanged() throws Exception {
        UUID id = heldOrder();
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        confirmed(id, "4500.00");

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(alice())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/orders/" + id + "/transition")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ORDER_ADMIN")))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"targetStatus\":\"CANCELLED\"}"))
                .andExpect(status().isConflict());

        assertThat(row(id).get("status")).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("making the order PAID is ONE transaction: if it fails at commit, the status, the receipt and order.paid all go, and the settlement is still waiting")
    void markingPaidIsOneTransaction() throws Exception {
        UUID id = heldOrder();
        jdbc.execute("""
                CREATE FUNCTION test_refuse_paid_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test: this sale fails at commit';
                END;
                $$""");
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER orders_test_paid_commit_fails AFTER UPDATE ON orders
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
                WHEN (NEW.status = 'PAID') EXECUTE FUNCTION test_refuse_paid_commit()""");
        try {
            long idsBefore = jdbc.queryForObject("SELECT coalesce(pg_sequence_last_value(pg_get_serial_sequence('outbox', 'outbox_id')::regclass), 0)", Long.class);
            long rowsBefore = jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class);

            UUID confirmation = confirmed(id, "4500.00");

            Map<String, Object> row = row(id);
            assertThat(row.get("status")).as("not paid: the transaction that would have made it so was rolled back").isEqualTo("CREATED");
            assertThat(row.get("receipt_number")).isNull();
            assertThat(row.get("settling_confirmation_id")).as("still being settled: the retry job finishes it").isEqualTo(confirmation);
            assertThat(settlement(confirmation).get("state")).isEqualTo("CONSUMING");
            assertThat(jdbc.queryForObject("SELECT coalesce(pg_sequence_last_value(pg_get_serial_sequence('outbox', 'outbox_id')::regclass), 0)", Long.class))
                    .as("order.paid was appended inside that transaction (it drew an outbox id)").isGreaterThan(idsBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class)).as("... and was rolled back with it").isEqualTo(rowsBefore);
            assertThat(events(id, "order.paid")).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER orders_test_paid_commit_fails ON orders");
            jdbc.execute("DROP FUNCTION test_refuse_paid_commit()");
        }
    }

    @Test
    @DisplayName("inventory is asked to sell with NO transaction open: a connection is never held while another service answers")
    void inventoryIsAskedOutsideATransaction() throws Exception {
        UUID id = heldOrder();
        boolean[] inside = {true};
        boolean[] asked = {false};
        org.mockito.Mockito.doAnswer(call -> {
            asked[0] = true;
            inside[0] = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
            return new InventoryClient.Reservation(call.getArgument(0), id, "CONSUMED", null, null, null, List.of());
        }).when(inventory).consume(any(), any());

        confirmed(id, "4500.00");

        assertThat(asked[0]).as("inventory was never asked to sell").isTrue();
        assertThat(inside[0]).as("the call to inventory held a database transaction open").isFalse();
    }

    // ------------------------------------------------------------------ the hold lapsed meanwhile

    @Test
    @DisplayName("inventory says the hold lapsed (the sweep had not run): the old hold is given back, a new one is taken under the same lines and sold; the order is PAID")
    void aLapsedHoldIsReplaced() throws Exception {
        UUID id = heldOrder();
        UUID oldReservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(409, LAPSED)).when(inventory).consume(eq(oldReservation), any());

        UUID confirmation = confirmed(id, "4500.00");

        verify(inventory).release(oldReservation);
        ArgumentCaptor<InventoryClient.ReserveAsk> reserved = ArgumentCaptor.forClass(InventoryClient.ReserveAsk.class);
        verify(inventory, times(2)).reserve(reserved.capture());
        UUID newReservation = reserved.getAllValues().get(1).reservationId();
        assertThat(newReservation).isNotEqualTo(oldReservation);
        verify(inventory).consume(eq(newReservation), any());
        assertThat(settlement(confirmation).get("reservation_id")).as("the id this attempt uses is kept, so a retry names the same one").isEqualTo(newReservation);
        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a hold already released says the same thing (reservation-not-held) and is handled the same way")
    void aReleasedHoldIsReplaced() throws Exception {
        UUID id = heldOrder();
        UUID oldReservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(409, NOT_HELD)).when(inventory).consume(eq(oldReservation), any());

        confirmed(id, "4500.00");

        assertThat(row(id).get("status")).isEqualTo("PAID");
        verify(inventory).release(oldReservation);
    }

    @Test
    @DisplayName("a new hold that cannot be taken yet because inventory is silent is retried under the id already chosen, not a fresh one each time")
    void aNewHoldIsRetriedUnderItsOwnId() throws Exception {
        UUID id = heldOrder();
        UUID oldReservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(409, LAPSED)).when(inventory).consume(eq(oldReservation), any());
        doThrow(SaleStubs.feign(503, null)).when(inventory).reserve(any());
        // the first reserve (the placement's) already happened; from here the new one fails
        UUID confirmation = confirmed(id, "4500.00");
        UUID chosen = (UUID) settlement(confirmation).get("reservation_id");
        assertThat(chosen).as("a new id was chosen and kept").isNotEqualTo(oldReservation);

        SaleStubs.inventoryHolds(inventory);
        dueNow(confirmation);
        job.run();

        ArgumentCaptor<InventoryClient.ReserveAsk> reserved = ArgumentCaptor.forClass(InventoryClient.ReserveAsk.class);
        verify(inventory, times(3)).reserve(reserved.capture());
        assertThat(reserved.getAllValues().get(1).reservationId()).isEqualTo(chosen);
        assertThat(reserved.getAllValues().get(2).reservationId()).isEqualTo(chosen);
        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a replacement hold that inventory already shows EXPIRED is replaced by a fresh id, not asked for again for ever")
    void aDeadReplacementHoldIsReplacedByAFreshId() throws Exception {
        UUID id = heldOrder();
        UUID oldReservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(409, LAPSED)).when(inventory).consume(eq(oldReservation), any());
        // inventory answers a reserve of an id it already knows with whatever that hold is: here it ran out while the sale could not be applied
        doAnswer(call -> {
            InventoryClient.ReserveAsk ask = call.getArgument(0);
            return new InventoryClient.Reservation(ask.reservationId(), ask.orderId(), "EXPIRED", Instant.now().minusSeconds(900),
                    Instant.now().minusSeconds(300), Instant.now().minusSeconds(300), List.of());
        }).when(inventory).reserve(any());

        UUID confirmation = confirmed(id, "4500.00");

        ArgumentCaptor<InventoryClient.ReserveAsk> first = ArgumentCaptor.forClass(InventoryClient.ReserveAsk.class);
        verify(inventory, times(2)).reserve(first.capture());
        UUID deadReplacement = first.getAllValues().get(1).reservationId();
        Map<String, Object> waiting = settlement(confirmation);
        assertThat(waiting.get("state")).as("not paid, not stuck: waiting to try again").isEqualTo("CONSUMING");
        assertThat(waiting.get("reservation_id")).as("the dead id is not asked for again").isNotIn(oldReservation, deadReplacement);
        UUID fresh = (UUID) waiting.get("reservation_id");
        assertThat(row(id).get("status")).isEqualTo("CREATED");

        SaleStubs.inventoryHolds(inventory);
        SaleStubs.inventorySells(inventory);
        dueNow(confirmation);
        job.run();

        ArgumentCaptor<InventoryClient.ReserveAsk> all = ArgumentCaptor.forClass(InventoryClient.ReserveAsk.class);
        verify(inventory, times(3)).reserve(all.capture());
        assertThat(all.getAllValues().get(2).reservationId()).isEqualTo(fresh);
        verify(inventory).consume(eq(fresh), any());
        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a replacement hold that lapses before its sale gets through is replaced by a fresh id, and the next attempt pays the order")
    void aReplacementHoldThatLapsesBeforeItsSaleIsReplaced() throws Exception {
        UUID id = heldOrder();
        // every sale is refused as "no longer held": the old hold's, and then the replacement's
        doThrow(SaleStubs.feign(409, NOT_HELD)).when(inventory).consume(any(), any());

        UUID confirmation = confirmed(id, "4500.00");

        ArgumentCaptor<InventoryClient.ReserveAsk> reserved = ArgumentCaptor.forClass(InventoryClient.ReserveAsk.class);
        verify(inventory, times(2)).reserve(reserved.capture());
        UUID replacement = reserved.getAllValues().get(1).reservationId();
        Map<String, Object> waiting = settlement(confirmation);
        assertThat(waiting.get("state")).isEqualTo("CONSUMING");
        assertThat(waiting.get("reservation_id")).as("a hold that was taken and is already gone is not tried again").isNotEqualTo(replacement);
        UUID fresh = (UUID) waiting.get("reservation_id");

        SaleStubs.inventorySells(inventory);
        dueNow(confirmation);
        job.run();

        verify(inventory).consume(eq(fresh), any());
        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("two drivers that find the same hold dead agree on ONE replacement: the second is told the first's, and never overwrites it")
    void twoDriversAgreeOnOneReplacement() throws Exception {
        UUID id = heldOrder();
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        UUID confirmation = confirmed(id, "4500.00");
        UUID dead = (UUID) settlement(confirmation).get("reservation_id");

        UUID first = settlements.chooseNewReservation(confirmation, dead);
        UUID second = settlements.chooseNewReservation(confirmation, dead);

        assertThat(first).isNotEqualTo(dead);
        assertThat(second).as("the loser uses the winner's id").isEqualTo(first);
        assertThat(settlement(confirmation).get("reservation_id")).isEqualTo(first);
    }

    @Test
    @DisplayName("money voided after the old hold was given back ends the order: it must not stay live with a hold that no longer exists")
    void aVoidAfterTheOldHoldWasGivenBackEndsTheOrder() throws Exception {
        UUID id = heldOrder();
        UUID oldReservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(409, LAPSED)).when(inventory).consume(eq(oldReservation), any());
        doThrow(SaleStubs.feign(503, null)).when(inventory).reserve(any());
        UUID confirmation = confirmed(id, "4500.00");
        verify(inventory).release(oldReservation);
        assertThat(row(id).get("status")).as("still waiting for its replacement hold").isEqualTo("CREATED");

        voided(confirmation, id);
        dueNow(confirmation);
        job.run();

        assertThat(settlement(confirmation).get("reason")).isEqualTo("VOIDED");
        assertThat(row(id).get("status")).as("its hold is gone, so the order is over").isEqualTo("EXPIRED");
        assertThat(events(id, "order.expired")).hasSize(1);
    }

    // ------------------------------------------------------------------ the piece, or only its box, is gone

    @Test
    @DisplayName("the PIECE is gone (someone else's money got there first): PIECE_SOLD, the money is held for the customer, and the order expires")
    void aPieceSold() throws Exception {
        UUID id = heldOrder();
        UUID oldReservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(409, LAPSED)).when(inventory).consume(eq(oldReservation), any());
        doThrow(SaleStubs.feign(409, SaleStubs.shortage(RING, "GOODS", 1, 0))).when(inventory).reserve(any());

        UUID confirmation = confirmed(id, "4500.00");

        Map<String, Object> settlement = settlement(confirmation);
        assertThat(settlement.get("state")).isEqualTo("UNSETTLED");
        assertThat(settlement.get("reason")).isEqualTo("PIECE_SOLD");
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).as("the hold is dead, so the order ends").isEqualTo("EXPIRED");
        assertThat(row.get("settling_confirmation_id")).isNull();
        assertThat(events(id, "order.payment_unsettled")).hasSize(1);
        assertThat(payloadOf(events(id, "order.payment_unsettled").get(0)).get("reason").asText()).isEqualTo("PIECE_SOLD");
        List<Map<String, Object>> expired = events(id, "order.expired");
        assertThat(expired).hasSize(1);
        assertThat(payloadOf(expired.get(0)).get("reservationId").asText()).isEqualTo(oldReservation.toString());
        assertThat(events(id, "order.paid")).isEmpty();
    }

    @Test
    @DisplayName("only the BOX is gone: the sale waits (BLOCKED_BOX) and keeps trying; it is never refunded for a missing box; when a box is found it is PAID")
    void aBoxShort() throws Exception {
        UUID id = heldOrder();
        UUID oldReservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(409, LAPSED)).when(inventory).consume(eq(oldReservation), any());
        doThrow(SaleStubs.feign(409, SaleStubs.shortage(SaleStubs.BOX_VARIANT, "BOX", 1, 0))).when(inventory).reserve(any());

        UUID confirmation = confirmed(id, "4500.00");

        Map<String, Object> waiting = settlement(confirmation);
        assertThat(waiting.get("state")).isEqualTo("BLOCKED_BOX");
        assertThat(waiting.get("next_attempt_at")).isNotNull();
        assertThat(row(id).get("status")).as("not paid, not expired: waiting for a box").isEqualTo("CREATED");
        assertThat(row(id).get("settling_confirmation_id")).isEqualTo(confirmation);
        assertThat(events(id, "order.payment_unsettled")).as("the money is not given back for a missing box").isEmpty();

        SaleStubs.inventoryHolds(inventory);
        dueNow(confirmation);
        job.run();

        assertThat(settlement(confirmation).get("state")).isEqualTo("DONE");
        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    // ------------------------------------------------------------------ the job as the safety net

    @Test
    @DisplayName("a settlement recorded but never driven (this service stopped right after the money was recorded) is picked up by the job once it has waited a few seconds, and not before")
    void aSettlementNeverDrivenIsPickedUpByTheJob() throws Exception {
        UUID id = heldOrder();
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        UUID confirmation = confirmed(id, "4500.00");
        // the state a crash between "recorded" and "asked inventory" leaves: no attempt made, nothing scheduled, only just received
        jdbc.update("UPDATE order_settlement SET next_attempt_at = NULL, attempts = 0, received_at = now() WHERE confirmation_id = ?", confirmation);
        SaleStubs.inventorySells(inventory);

        job.run();

        assertThat(settlement(confirmation).get("state")).as("just received: the consumer may still be about to drive it").isEqualTo("CONSUMING");
        verify(inventory, times(1)).consume(any(), any());

        jdbc.update("UPDATE order_settlement SET received_at = now() - interval '1 minute' WHERE confirmation_id = ?", confirmation);
        job.run();

        assertThat(row(id).get("status")).isEqualTo("PAID");
        assertThat(settlement(confirmation).get("state")).isEqualTo("DONE");
        assertThat(events(id, "order.paid")).hasSize(1);
    }

    // ------------------------------------------------------------------ a lapse or a rejection that arrives while the money is being applied

    @Test
    @DisplayName("a lapse deferred while settling is APPLIED when the settlement ends for a reason of its own (here: the money was voided): the order expires, once")
    void aDeferredLapseIsAppliedWhenTheMoneyIsVoided() throws Exception {
        UUID id = heldOrder();
        UUID reservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        UUID confirmation = confirmed(id, "4500.00");
        reservationExpired(reservation, id);
        assertThat(settlement(confirmation).get("deferred_end")).isEqualTo("EXPIRED");

        voided(confirmation, id);
        dueNow(confirmation);
        job.run();

        assertThat(settlement(confirmation).get("reason")).isEqualTo("VOIDED");
        assertThat(row(id).get("status")).as("the lapse that was remembered is applied now").isEqualTo("EXPIRED");
        assertThat(events(id, "order.expired")).hasSize(1);
    }

    @Test
    @DisplayName("a rejection deferred while settling is APPLIED when the settlement ends unsettled: the order, waiting on that very claim, expires")
    void aDeferredRejectionIsAppliedWhenTheMoneyIsVoided() throws Exception {
        UUID id = claimedOrder();
        UUID claim = (UUID) row(id).get("claim_id");
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        UUID confirmation = confirmed(id, "4500.00");
        rejected(claim, id);
        assertThat(settlement(confirmation).get("deferred_end")).isEqualTo("REJECTED");
        assertThat(row(id).get("status")).as("not expired while money is being applied").isEqualTo("PAYMENT_SUBMITTED");

        voided(confirmation, id);
        dueNow(confirmation);
        job.run();

        assertThat(row(id).get("status")).isEqualTo("EXPIRED");
        assertThat(events(id, "order.expired")).hasSize(1);
    }

    @Test
    @DisplayName("the hold lapsing while a settlement is in flight is NOT acted on: it is remembered, and ignored when the sale is paid")
    void aLapseWhileSettlingIsDeferredAndIgnoredWhenPaid() throws Exception {
        UUID id = heldOrder();
        UUID reservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        UUID confirmation = confirmed(id, "4500.00");

        reservationExpired(reservation, id);

        assertThat(row(id).get("status")).as("not expired: money is being applied").isEqualTo("CREATED");
        assertThat(settlement(confirmation).get("deferred_end")).isEqualTo("EXPIRED");
        assertThat(events(id, "order.expired")).isEmpty();

        SaleStubs.inventorySells(inventory);
        dueNow(confirmation);
        job.run();

        assertThat(row(id).get("status")).isEqualTo("PAID");
        assertThat(events(id, "order.expired")).isEmpty();
    }

    @Test
    @DisplayName("a lapse deferred while settling is APPLIED when the settlement ends unsettled: the order then expires")
    void aDeferredLapseIsAppliedWhenTheSettlementFails() throws Exception {
        UUID id = heldOrder();
        UUID reservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        UUID confirmation = confirmed(id, "4500.00");
        reservationExpired(reservation, id);
        // the piece is gone when inventory is next asked
        doThrow(SaleStubs.feign(409, LAPSED)).when(inventory).consume(any(), any());
        doThrow(SaleStubs.feign(409, SaleStubs.shortage(RING, "GOODS", 1, 0))).when(inventory).reserve(any());
        dueNow(confirmation);

        job.run();

        assertThat(settlement(confirmation).get("reason")).isEqualTo("PIECE_SOLD");
        assertThat(row(id).get("status")).isEqualTo("EXPIRED");
        assertThat(events(id, "order.expired")).as("one ending, however many reasons").hasSize(1);
    }

    @Test
    @DisplayName("a claim rejected while a settlement is in flight is deferred the same way")
    void aRejectionWhileSettlingIsDeferred() throws Exception {
        UUID id = claimedOrder();
        UUID claim = (UUID) row(id).get("claim_id");
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        UUID confirmation = confirmed(id, "4500.00");

        rejected(claim, id);

        assertThat(row(id).get("status")).isEqualTo("PAYMENT_SUBMITTED");
        assertThat(settlement(confirmation).get("deferred_end")).isEqualTo("REJECTED");
    }

    // ------------------------------------------------------------------ voided money

    @Test
    @DisplayName("money voided BEFORE it is applied is never applied: the settlement ends unsettled VOIDED with nothing sold and nothing announced")
    void voidedBeforeItIsApplied() throws Exception {
        UUID id = heldOrder();
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        UUID confirmation = confirmed(id, "4500.00");
        SaleStubs.inventorySells(inventory);

        voided(confirmation, id);
        dueNow(confirmation);
        job.run();

        Map<String, Object> settlement = settlement(confirmation);
        assertThat(settlement.get("state")).isEqualTo("UNSETTLED");
        assertThat(settlement.get("reason")).isEqualTo("VOIDED");
        assertThat(settlement.get("voided_at")).isNotNull();
        assertThat(row(id).get("status")).isEqualTo("CREATED");
        assertThat(row(id).get("settling_confirmation_id")).isNull();
        verify(inventory, times(1)).consume(any(), any());
        assertThat(events(id, "order.paid")).isEmpty();
        assertThat(events(id, "order.payment_unsettled")).as("payment voided the money itself: it needs no answer").isEmpty();
    }

    @Test
    @DisplayName("a confirmation voided before order ever heard of it leaves a mark, so its late announcement can never pay a sale")
    void voidedBeforeItArrives() throws Exception {
        UUID id = heldOrder();
        UUID confirmation = UUID.randomUUID();

        voided(confirmation, id);
        confirmed(confirmation, id, "4500.00", "ZMW");

        Map<String, Object> settlement = settlement(confirmation);
        assertThat(settlement.get("state")).isEqualTo("UNSETTLED");
        assertThat(settlement.get("reason")).isEqualTo("VOIDED");
        assertThat(row(id).get("status")).isEqualTo("CREATED");
        verify(inventory, never()).consume(any(), any());
        assertThat(events(id, "order.payment_unsettled")).isEmpty();
    }

    @Test
    @DisplayName("money voided AFTER it paid the sale cannot un-sell it: the order stays PAID, the void is recorded for staff, and payment is told nothing new")
    void voidedAfterItPaid() throws Exception {
        UUID id = heldOrder();
        UUID confirmation = confirmed(id, "4500.00");

        voided(confirmation, id);

        assertThat(row(id).get("status")).isEqualTo("PAID");
        Map<String, Object> settlement = settlement(confirmation);
        assertThat(settlement.get("state")).isEqualTo("DONE");
        assertThat(settlement.get("voided_at")).as("flagged: a paid sale whose money was voided").isNotNull();
        assertThat(events(id, "order.paid")).hasSize(1);
    }

    @Test
    @DisplayName("money voided after it was found unsettled changes nothing here (payment closes the held money itself); the void is only noted")
    void voidedAfterItWasUnsettled() throws Exception {
        UUID stranger = UUID.randomUUID();
        UUID confirmation = confirmed(stranger, "4500.00");

        voided(confirmation, stranger);

        Map<String, Object> settlement = settlement(confirmation);
        assertThat(settlement.get("state")).isEqualTo("UNSETTLED");
        assertThat(settlement.get("reason")).as("still why it was unsettled").isEqualTo("ORDER_UNKNOWN");
        assertThat(settlement.get("voided_at")).isNotNull();
    }

    // ------------------------------------------------------------------ the retry job on its own

    @Test
    @DisplayName("the retry job leaves settled and unsettled money alone, and only the due ones are asked again")
    void theJobOnlyTouchesWhatIsDue() throws Exception {
        UUID paid = heldOrder();
        confirmed(paid, "4500.00");
        UUID stranger = UUID.randomUUID();
        confirmed(stranger, "4500.00");

        job.run();

        verify(inventory, times(1)).consume(any(), any());
        verify(inventory, never()).release(any());
    }
}
