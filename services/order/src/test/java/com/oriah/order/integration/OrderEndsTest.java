package com.oriah.order.integration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How an order that is waiting for money ENDS without it (Phase 7): its hold lapses, staff reject the claim the shopper made, or the shopper cancels.
 * Each ending carries the reservation the pieces were held under, so inventory lets go of THAT hold and a stale event can never reach a newer one; and
 * each acts only on an order still waiting for payment and not being settled: an event for any other state is a logged no-op, never an error (an event
 * that throws is parked on the dead-letter topic and nobody reads it).
 */
class OrderEndsTest extends SaleTestBase {

    private List<Map<String, Object>> expiredEvents(UUID id) {
        return events(id, "order.expired");
    }

    // ------------------------------------------------------------------ a claim rejected by staff

    @Test
    @DisplayName("staff rejecting the shopper's claim expires the order: order.expired carries the reservation id and the hold is released")
    void aRejectedClaimExpiresTheOrder() throws Exception {
        UUID id = claimedOrder();
        UUID claim = (UUID) row(id).get("claim_id");
        UUID reservation = (UUID) row(id).get("reservation_id");

        rejected(claim, id);

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("EXPIRED");
        assertThat(row.get("hold_state")).isEqualTo("RELEASED");
        assertThat(row.get("hold_extension_pending")).isEqualTo(false);
        assertThat(expiredEvents(id)).hasSize(1);
        assertThat(payloadOf(expiredEvents(id).get(0)).get("reservationId").asText()).isEqualTo(reservation.toString());
    }

    @Test
    @DisplayName("a rejection of another claim (an older one, or a stranger's) changes nothing: it is bound to the claim the order holds")
    void aRejectionIsBoundToTheClaim() throws Exception {
        UUID id = claimedOrder();

        rejected(UUID.randomUUID(), id);

        assertThat(row(id).get("status")).isEqualTo("PAYMENT_SUBMITTED");
        assertThat(expiredEvents(id)).isEmpty();
    }

    @Test
    @DisplayName("a rejection for an order that has no claim, is already paid, or does not exist is a no-op, not an error")
    void aRejectionForTheWrongStateIsANoOp() throws Exception {
        UUID unclaimed = heldOrder();
        UUID paid = heldOrder(2);
        confirmed(paid, "9000.00");
        UUID claimOfPaid = UUID.randomUUID();

        rejected(UUID.randomUUID(), unclaimed);
        rejected(claimOfPaid, paid);
        rejected(UUID.randomUUID(), UUID.randomUUID());

        assertThat(row(unclaimed).get("status")).isEqualTo("CREATED");
        assertThat(row(paid).get("status")).isEqualTo("PAID");
        assertThat(expiredEvents(unclaimed)).isEmpty();
        assertThat(expiredEvents(paid)).isEmpty();
    }

    @Test
    @DisplayName("the same rejection twice ends the order once")
    void aRejectionTwiceIsOnce() throws Exception {
        UUID id = claimedOrder();
        UUID claim = (UUID) row(id).get("claim_id");

        rejected(claim, id);
        rejected(claim, id);

        assertThat(expiredEvents(id)).hasSize(1);
    }

    // ------------------------------------------------------------------ the hold lapsing

    @Test
    @DisplayName("inventory's sweep ending the hold expires an order still waiting for payment, and the event carries the reservation")
    void aLapsedHoldExpiresTheOrder() throws Exception {
        UUID id = heldOrder();
        UUID reservation = (UUID) row(id).get("reservation_id");

        reservationExpired(reservation, id);

        assertThat(row(id).get("status")).isEqualTo("EXPIRED");
        assertThat(row(id).get("hold_state")).isEqualTo("RELEASED");
        assertThat(payloadOf(expiredEvents(id).get(0)).get("reservationId").asText()).isEqualTo(reservation.toString());
    }

    @Test
    @DisplayName("a lapse also ends an order whose shopper had said they paid: staff had a day, and the money did not come")
    void aLapsedClaimedOrderExpires() throws Exception {
        UUID id = claimedOrder();

        reservationExpired((UUID) row(id).get("reservation_id"), id);

        assertThat(row(id).get("status")).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("a lapse of an OLDER reservation does not touch the order: it names the reservation the order holds NOW")
    void aStaleLapseIsIgnored() throws Exception {
        UUID id = heldOrder();

        reservationExpired(UUID.randomUUID(), id);

        assertThat(row(id).get("status")).isEqualTo("CREATED");
        assertThat(row(id).get("hold_state")).isEqualTo("HELD");
        assertThat(expiredEvents(id)).isEmpty();
    }

    @Test
    @DisplayName("a lapse for a paid, cancelled or unknown order is a no-op")
    void aLapseForTheWrongStateIsANoOp() throws Exception {
        UUID paid = heldOrder();
        UUID paidReservation = (UUID) row(paid).get("reservation_id");
        confirmed(paid, "4500.00");
        UUID cancelled = heldOrder(2);
        UUID cancelledReservation = (UUID) row(cancelled).get("reservation_id");
        mvc.perform(post("/api/v1/orders/" + cancelled + "/cancel").with(alice())).andExpect(status().isOk());

        reservationExpired(paidReservation, paid);
        reservationExpired(cancelledReservation, cancelled);
        reservationExpired(UUID.randomUUID(), UUID.randomUUID());

        assertThat(row(paid).get("status")).isEqualTo("PAID");
        assertThat(row(cancelled).get("status")).isEqualTo("CANCELLED");
        assertThat(expiredEvents(paid)).isEmpty();
        assertThat(expiredEvents(cancelled)).isEmpty();
    }

    @Test
    @DisplayName("the same lapse twice ends the order once")
    void aLapseTwiceIsOnce() throws Exception {
        UUID id = heldOrder();
        UUID reservation = (UUID) row(id).get("reservation_id");

        reservationExpired(reservation, id);
        reservationExpired(reservation, id);

        assertThat(expiredEvents(id)).hasSize(1);
    }

    // ------------------------------------------------------------------ the clock ends an order whose expiry event was lost

    @org.springframework.beans.factory.annotation.Autowired
    private com.oriah.order.service.OrderEnds ends;

    /** The hold job's own query already leaves these out; the order is looked at AGAIN under its lock, because a claim or money can land in between. */
    @Test
    @DisplayName("ending an order from the clock re-checks it under the lock: one with a hold extension pending, or being settled, or not yet past the cutoff, is left alone")
    void theClockReChecksTheOrderBeforeEndingIt() throws Exception {
        java.time.Instant cutoff = java.time.Instant.now();
        UUID extending = heldOrder();
        jdbc.update("UPDATE orders SET hold_expires_at = now() - interval '10 minutes', hold_extension_pending = true WHERE id = ?", extending);
        UUID settling = heldOrder(2);
        jdbc.update("UPDATE orders SET hold_expires_at = now() - interval '10 minutes', settling_confirmation_id = gen_random_uuid() WHERE id = ?", settling);
        UUID running = heldOrder(3);
        jdbc.update("UPDATE orders SET hold_expires_at = now() + interval '5 minutes' WHERE id = ?", running);

        ends.lapsedByClock(extending, cutoff);
        ends.lapsedByClock(settling, cutoff);
        ends.lapsedByClock(running, cutoff);

        assertThat(row(extending).get("status")).as("a claim's extension decides this one").isEqualTo("CREATED");
        assertThat(row(settling).get("status")).as("the money decides this one").isEqualTo("CREATED");
        assertThat(row(running).get("status")).as("its hold has not run out").isEqualTo("CREATED");
        assertThat(expiredEvents(extending)).isEmpty();
        assertThat(expiredEvents(settling)).isEmpty();
        assertThat(expiredEvents(running)).isEmpty();
    }

    @Test
    @DisplayName("an expiry event for an order still PENDING (written, never announced) is a logged no-op: nothing is announced for an order nobody was told about")
    void aPendingOrderIsNotEndedByAnEvent() throws Exception {
        UUID id = heldOrder();
        UUID reservation = (UUID) row(id).get("reservation_id");
        // the state of a placement that stopped before it was announced
        jdbc.update("UPDATE orders SET hold_state = 'PENDING' WHERE id = ?", id);

        reservationExpired(reservation, id);

        assertThat(row(id).get("status")).isEqualTo("CREATED");
        assertThat(row(id).get("hold_state")).as("left for the hold job to remove").isEqualTo("PENDING");
        assertThat(expiredEvents(id)).isEmpty();
    }

    @Test
    @DisplayName("an order that is no longer waiting for money (paid) is never ended from the clock, whatever its hold says")
    void aPaidOrderIsNotEndedByTheClock() throws Exception {
        UUID id = heldOrder();
        confirmed(id, "4500.00");
        jdbc.update("UPDATE orders SET hold_expires_at = now() - interval '10 minutes' WHERE id = ?", id);

        ends.lapsedByClock(id, java.time.Instant.now());

        assertThat(row(id).get("status")).isEqualTo("PAID");
        assertThat(expiredEvents(id)).isEmpty();
    }

    // ------------------------------------------------------------------ the shopper cancels

    @Test
    @DisplayName("a shopper cancelling an order not yet paid lets go of the hold: order.cancelled carries the reservation id")
    void aCancelledOrderReleasesItsHold() throws Exception {
        UUID id = heldOrder();
        UUID reservation = (UUID) row(id).get("reservation_id");

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(alice())).andExpect(status().isOk());

        assertThat(row(id).get("status")).isEqualTo("CANCELLED");
        assertThat(row(id).get("hold_state")).isEqualTo("RELEASED");
        List<Map<String, Object>> cancelled = events(id, "order.cancelled");
        assertThat(cancelled).hasSize(1);
        assertThat(payloadOf(cancelled.get(0)).get("reservationId").asText()).isEqualTo(reservation.toString());
    }
}
