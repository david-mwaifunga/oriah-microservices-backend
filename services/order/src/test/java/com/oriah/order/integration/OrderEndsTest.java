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
