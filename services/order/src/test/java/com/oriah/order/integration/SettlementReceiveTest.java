package com.oriah.order.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.InventoryClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Money that staff confirmed reaches the order (Phase 7): the order decides whether it PAYS THE SALE or is HELD for the customer, and says which, so
 * payment never has to guess. {@code payment.confirmed} is read once however often it arrives; every state of every order has an outcome (nothing is
 * thrown at the dead-letter topic for being in an unexpected state); and a confirmation order has already answered is answered AGAIN when it is
 * announced again, so a lost answer never leaves money with no outcome.
 */
class SettlementReceiveTest extends SaleTestBase {

    // ------------------------------------------------------------------ the sale is paid

    @Test
    @DisplayName("money for the full total pays the sale: inventory sells the pieces dated to the day the money arrived, and the order is PAID with a receipt number")
    void moneyPaysTheSale() throws Exception {
        UUID id = heldOrder();
        UUID reservation = (UUID) row(id).get("reservation_id");

        UUID confirmation = confirmed(id, "4500.00");

        ArgumentCaptor<InventoryClient.ConsumeAsk> sold = ArgumentCaptor.forClass(InventoryClient.ConsumeAsk.class);
        verify(inventory).consume(eq(reservation), sold.capture());
        assertThat(sold.getValue().occurredAt()).as("the date of the sale is the day the money ARRIVED").isEqualTo(Instant.parse(ARRIVED));
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat(row.get("hold_state")).isEqualTo("CONSUMED");
        assertThat(row.get("paid_confirmation_id")).isEqualTo(confirmation);
        assertThat(at(row.get("paid_at"))).isEqualTo(Instant.parse(ARRIVED));
        assertThat((String) row.get("receipt_number")).matches("OR-\\d{6}");
        assertThat(row.get("settling_confirmation_id")).as("no settlement in flight any more").isNull();
        Map<String, Object> settlement = settlement(confirmation);
        assertThat(settlement.get("state")).isEqualTo("DONE");
        assertThat(settlement.get("reservation_id")).isEqualTo(reservation);
        assertThat(settlement.get("settled_at")).isNotNull();
    }

    @Test
    @DisplayName("order.paid is the version-2 snapshot: the old fields first, the money arrived as its date, the confirmation, the receipt, and every line with its product, discount and box")
    void paidIsAnnouncedAsVersionTwo() throws Exception {
        UUID id = heldOrder();
        UUID confirmation = confirmed(id, "4500.00");

        List<Map<String, Object>> paid = events(id, "order.paid");

        assertThat(paid).hasSize(1);
        Map<String, Object> event = paid.get(0);
        assertThat(event.get("schema_version")).isEqualTo(2);
        assertThat(event.get("topic")).isEqualTo("oriah.order");
        assertThat(at(event.get("occurred_at"))).as("the envelope's date is the day the money arrived").isEqualTo(Instant.parse(ARRIVED));
        JsonNode body = payloadOf(event);
        assertThat(body.get("orderId").asText()).isEqualTo(id.toString());
        assertThat(body.get("keycloakId").asText()).isEqualTo("kc-alice");
        assertThat(body.get("previousStatus").asText()).isEqualTo("CREATED");
        assertThat(body.get("newStatus").asText()).isEqualTo("PAID");
        assertThat(body.get("total").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(body.get("currency").asText()).isEqualTo("ZMW");
        assertThat(body.get("confirmationId").asText()).isEqualTo(confirmation.toString());
        assertThat(body.get("receiptNumber").asText()).isEqualTo(row(id).get("receipt_number"));
        assertThat(Instant.parse(body.get("arrivedAt").asText())).isEqualTo(Instant.parse(ARRIVED));
        assertThat(body.get("recordedAt").asText()).isNotBlank();
        assertThat(body.get("paymentMethod").asText()).isEqualTo("MTN");
        assertThat(body.get("paymentGross").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(body.get("paymentFee").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(body.get("paymentNet").decimalValue()).isEqualByComparingTo("4455.00");
        Map<String, Object> item = jdbc.queryForMap("SELECT * FROM order_items WHERE order_id = ?", id);
        JsonNode line = body.get("lines").get(0);
        assertThat(line.get("variantId").asText()).isEqualTo(RING.toString());
        assertThat(line.get("lineId").asText()).as("the line is sold under its own id").isEqualTo(item.get("id").toString());
        assertThat(line.get("productId").asText()).isEqualTo(PRODUCT.toString());
        assertThat(line.get("quantity").asInt()).isEqualTo(1);
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(line.get("discountPerPiece").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(line.get("boxVariantId").asText()).isEqualTo(SaleStubs.BOX_VARIANT.toString());
        assertThat(line.get("boxLineId").asText()).isEqualTo(item.get("box_line_id").toString());
    }

    @Test
    @DisplayName("an order whose shopper already said they paid is settled the same way")
    void aClaimedOrderIsSettled() throws Exception {
        UUID id = claimedOrder();

        confirmed(id, "4500.00");

        assertThat(row(id).get("status")).isEqualTo("PAID");
        assertThat(events(id, "order.paid")).hasSize(1);
    }

    @Test
    @DisplayName("an order that expired is settled all the same, if its piece is still free: the old hold is given back, a NEW one is taken and sold, under the same lines")
    void anExpiredOrderIsSettledWithANewReservation() throws Exception {
        UUID id = heldOrder();
        UUID oldReservation = (UUID) row(id).get("reservation_id");
        jdbc.update("UPDATE orders SET status = 'EXPIRED', hold_state = 'RELEASED' WHERE id = ?", id);

        UUID confirmation = confirmed(id, "4500.00");

        verify(inventory).release(oldReservation);
        ArgumentCaptor<InventoryClient.ReserveAsk> reserved = ArgumentCaptor.forClass(InventoryClient.ReserveAsk.class);
        verify(inventory, times(2)).reserve(reserved.capture());
        InventoryClient.ReserveAsk again = reserved.getAllValues().get(1);
        assertThat(again.reservationId()).as("a new reservation, not the old one").isNotEqualTo(oldReservation);
        assertThat(again.orderId()).isEqualTo(id);
        InventoryClient.LineAsk first = reserved.getAllValues().get(0).lines().get(0);
        assertThat(again.lines().get(0).saleLineId()).as("the same lines, sold under the same ids").isEqualTo(first.saleLineId());
        assertThat(again.lines().get(0).boxLineId()).isEqualTo(first.boxLineId());
        verify(inventory).consume(eq(again.reservationId()), any());
        assertThat(settlement(confirmation).get("reservation_id")).isEqualTo(again.reservationId());
        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    // ------------------------------------------------------------------ money after an END: the plan's four cells (lapse or rejection, piece free or sold)

    @Test
    @DisplayName("lapse, piece still free: money for an order whose hold LAPSED (the real expiry event, not a row edit) pays it on a new hold")
    void moneyAfterALapseWithThePieceFree() throws Exception {
        UUID id = heldOrder();
        reservationExpired((UUID) row(id).get("reservation_id"), id);
        assertThat(row(id).get("status")).isEqualTo("EXPIRED");

        UUID confirmation = confirmed(id, "4500.00");

        assertThat(row(id).get("status")).isEqualTo("PAID");
        assertThat(settlement(confirmation).get("state")).isEqualTo("DONE");
        assertThat(events(id, "order.paid")).hasSize(1);
        assertThat(events(id, "order.payment_unsettled")).isEmpty();
    }

    @Test
    @DisplayName("lapse, piece SOLD meanwhile: the money is held for the customer (PIECE_SOLD), the order stays EXPIRED and nothing is sold")
    void moneyAfterALapseWithThePieceSold() throws Exception {
        UUID id = heldOrder();
        reservationExpired((UUID) row(id).get("reservation_id"), id);
        doThrow(SaleStubs.feign(409, SaleStubs.shortage(RING, "GOODS", 1, 0))).when(inventory).reserve(any());

        UUID confirmation = confirmed(id, "4500.00");

        Map<String, Object> settlement = settlement(confirmation);
        assertThat(settlement.get("state")).isEqualTo("UNSETTLED");
        assertThat(settlement.get("reason")).isEqualTo("PIECE_SOLD");
        assertThat(row(id).get("status")).isEqualTo("EXPIRED");
        assertThat(events(id, "order.paid")).isEmpty();
        assertThat(events(id, "order.payment_unsettled")).hasSize(1);
        assertThat(payloadOf(events(id, "order.payment_unsettled").get(0)).get("reason").asText()).isEqualTo("PIECE_SOLD");
        verify(inventory, never()).consume(any(), any());
    }

    @Test
    @DisplayName("rejection, piece still free: money for an order whose claim staff REJECTED pays it all the same (a rejected claim is not a rejected sale)")
    void moneyAfterARejectionWithThePieceFree() throws Exception {
        UUID id = claimedOrder();
        rejected((UUID) row(id).get("claim_id"), id);
        assertThat(row(id).get("status")).isEqualTo("EXPIRED");

        UUID confirmation = confirmed(id, "4500.00");

        assertThat(row(id).get("status")).isEqualTo("PAID");
        assertThat(settlement(confirmation).get("state")).isEqualTo("DONE");
        assertThat(events(id, "order.paid")).hasSize(1);
    }

    @Test
    @DisplayName("rejection, piece SOLD meanwhile: the money is held for the customer (PIECE_SOLD) and the order stays EXPIRED")
    void moneyAfterARejectionWithThePieceSold() throws Exception {
        UUID id = claimedOrder();
        rejected((UUID) row(id).get("claim_id"), id);
        doThrow(SaleStubs.feign(409, SaleStubs.shortage(RING, "GOODS", 1, 0))).when(inventory).reserve(any());

        UUID confirmation = confirmed(id, "4500.00");

        Map<String, Object> settlement = settlement(confirmation);
        assertThat(settlement.get("state")).isEqualTo("UNSETTLED");
        assertThat(settlement.get("reason")).isEqualTo("PIECE_SOLD");
        assertThat(row(id).get("status")).isEqualTo("EXPIRED");
        assertThat(events(id, "order.paid")).isEmpty();
        verify(inventory, never()).consume(any(), any());
    }

    // ------------------------------------------------------------------ the money cannot pay the sale: held for the customer

    @Test
    @DisplayName("money for an order nobody knows (a mistyped id) is recorded and announced as unsettled ORDER_UNKNOWN, with nothing asked of inventory")
    void anUnknownOrder() throws Exception {
        UUID stranger = UUID.randomUUID();

        UUID confirmation = confirmed(stranger, "4500.00");

        Map<String, Object> settlement = settlement(confirmation);
        assertThat(settlement.get("state")).isEqualTo("UNSETTLED");
        assertThat(settlement.get("reason")).isEqualTo("ORDER_UNKNOWN");
        assertThat(settlement.get("order_id")).isEqualTo(stranger);
        List<Map<String, Object>> unsettled = events(stranger, "order.payment_unsettled");
        assertThat(unsettled).hasSize(1);
        JsonNode body = payloadOf(unsettled.get(0));
        assertThat(body.get("confirmationId").asText()).isEqualTo(confirmation.toString());
        assertThat(body.get("orderId").asText()).isEqualTo(stranger.toString());
        assertThat(body.get("reason").asText()).isEqualTo("ORDER_UNKNOWN");
        assertThat(body.get("currency").asText()).isEqualTo("ZMW");
        assertThat(body.size()).as("exactly the four fields payment reads").isEqualTo(4);
        assertThat(unsettled.get(0).get("schema_version")).isEqualTo(1);
        verify(inventory, never()).consume(any(), any());
    }

    @Test
    @DisplayName("an order from before holds existed (no reservation, no hold) can be settled by no one: ORDER_UNKNOWN, the money is held")
    void aLegacyOrder() throws Exception {
        UUID id = heldOrder();
        jdbc.update("UPDATE orders SET reservation_id = NULL, hold_state = NULL WHERE id = ?", id);

        UUID confirmation = confirmed(id, "4500.00");

        assertThat(settlement(confirmation).get("reason")).isEqualTo("ORDER_UNKNOWN");
        assertThat(row(id).get("status")).as("untouched").isEqualTo("CREATED");
        verify(inventory, never()).consume(any(), any());
    }

    @Test
    @DisplayName("money for a cancelled order is unsettled ORDER_CANCELLED, and the order stays cancelled")
    void aCancelledOrder() throws Exception {
        UUID id = heldOrder();
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", id);

        UUID confirmation = confirmed(id, "4500.00");

        assertThat(settlement(confirmation).get("reason")).isEqualTo("ORDER_CANCELLED");
        assertThat(row(id).get("status")).isEqualTo("CANCELLED");
        assertThat(payloadOf(events(id, "order.payment_unsettled").get(0)).get("reason").asText()).isEqualTo("ORDER_CANCELLED");
    }

    @Test
    @DisplayName("money for an order that is already paid is unsettled ALREADY_PAID: a second payment is never a second sale")
    void anOrderAlreadyPaid() throws Exception {
        UUID id = heldOrder();
        confirmed(id, "4500.00");

        UUID second = confirmed(id, "4500.00");

        assertThat(settlement(second).get("reason")).isEqualTo("ALREADY_PAID");
        verify(inventory, times(1)).consume(any(), any());
        assertThat(events(id, "order.paid")).hasSize(1);
        assertThat(events(id, "order.payment_unsettled")).hasSize(1);
    }

    @Test
    @DisplayName("while one confirmation is being applied, another for the same order is unsettled ALREADY_PAID, never a second sale in flight")
    void aSecondConfirmationWhileTheFirstIsInFlight() throws Exception {
        UUID id = heldOrder();
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        UUID first = confirmed(id, "4500.00");
        assertThat(settlement(first).get("state")).as("in flight: inventory did not answer").isEqualTo("CONSUMING");

        UUID second = confirmed(id, "4500.00");

        assertThat(settlement(second).get("state")).isEqualTo("UNSETTLED");
        assertThat(settlement(second).get("reason")).isEqualTo("ALREADY_PAID");
        assertThat(row(id).get("settling_confirmation_id")).as("the first is still the one in flight").isEqualTo(first);
    }

    @Test
    @DisplayName("money that is not the total is unsettled WRONG_AMOUNT: too little, too much, or in another currency; the order keeps its state and its hold")
    void aWrongAmount() throws Exception {
        UUID id = heldOrder();

        UUID less = confirmed(id, "4000.00");
        UUID more = confirmed(id, "5000.00");
        UUID foreign = confirmed(UUID.randomUUID(), id, "4500.00", "USD");

        for (UUID confirmation : List.of(less, more, foreign)) {
            assertThat(settlement(confirmation).get("state")).isEqualTo("UNSETTLED");
            assertThat(settlement(confirmation).get("reason")).isEqualTo("WRONG_AMOUNT");
        }
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(row.get("hold_state")).isEqualTo("HELD");
        assertThat(row.get("settling_confirmation_id")).isNull();
        verify(inventory, never()).consume(any(), any());
        assertThat(events(id, "order.payment_unsettled")).hasSize(3);
    }

    @Test
    @DisplayName("after a wrong amount the right one still pays the sale: UNSETTLED money does not count as a settlement in the way")
    void theRightAmountAfterAWrongOne() throws Exception {
        UUID id = heldOrder();
        confirmed(id, "4000.00");

        confirmed(id, "4500.00");

        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    // ------------------------------------------------------------------ once, and answered again

    @Test
    @DisplayName("the very same event twice (a redelivery) settles once and announces once")
    void aRedeliveryIsApplyOnce() throws Exception {
        UUID id = heldOrder();
        UUID confirmation = UUID.randomUUID();
        var event = message("payment_confirmation", confirmation, "payment.confirmed", 1, confirmedPayload(confirmation, id, "4500.00", "ZMW"), "payment");

        paymentEvents.accept(event);
        paymentEvents.accept(event);

        verify(inventory, times(1)).consume(any(), any());
        assertThat(events(id, "order.paid")).hasSize(1);
    }

    @Test
    @DisplayName("a confirmation announced AGAIN after order paid with it is answered again (order.paid is re-published), and nothing is sold twice")
    void aPaidAnswerIsRepublished() throws Exception {
        UUID id = heldOrder();
        UUID confirmation = confirmed(id, "4500.00");

        announcedAgain(confirmation, id, "4500.00");

        assertThat(events(id, "order.paid")).as("the answer, again: payment may not have heard the first").hasSize(2);
        assertThat(payloadOf(events(id, "order.paid").get(1)).get("confirmationId").asText()).isEqualTo(confirmation.toString());
        com.fasterxml.jackson.databind.JsonNode first = payloadOf(events(id, "order.paid").get(0));
        com.fasterxml.jackson.databind.JsonNode again = payloadOf(events(id, "order.paid").get(1));
        assertThat(first.has("republished") && first.get("republished").asBoolean()).as("the first announcement is not a republish").isFalse();
        assertThat(again.get("republished").asBoolean()).as("the second says it is one, so notification sends no second email").isTrue();
        assertThat(again.has("previousStatus") && !again.get("previousStatus").isNull()).as("a republish has no transition to report").isFalse();
        first.fieldNames().forEachRemaining(field -> {
            if (!List.of("republished", "previousStatus", "recordedAt").contains(field)) {
                assertThat(again.get(field)).as("the answer is the same fact: " + field).isEqualTo(first.get(field));
            }
        });
        verify(inventory, times(1)).consume(any(), any());
        assertThat(settlements(confirmation)).isEqualTo(1);
        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a confirmation announced AGAIN after it was found unsettled is answered again with the same reason")
    void anUnsettledAnswerIsRepublished() throws Exception {
        UUID stranger = UUID.randomUUID();
        UUID confirmation = confirmed(stranger, "4500.00");

        announcedAgain(confirmation, stranger, "4500.00");

        List<Map<String, Object>> answers = events(stranger, "order.payment_unsettled");
        assertThat(answers).hasSize(2);
        assertThat(payloadOf(answers.get(1)).get("reason").asText()).isEqualTo("ORDER_UNKNOWN");
        assertThat(settlements(confirmation)).isEqualTo(1);
    }

    @Test
    @DisplayName("a confirmation announced again while it is still being applied is not answered yet: it is in flight, and nothing is repeated")
    void aRepeatWhileInFlightIsLeftAlone() throws Exception {
        UUID id = heldOrder();
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());
        UUID confirmation = confirmed(id, "4500.00");

        announcedAgain(confirmation, id, "4500.00");

        assertThat(events(id, "order.paid")).isEmpty();
        assertThat(events(id, "order.payment_unsettled")).isEmpty();
        assertThat(settlements(confirmation)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ what the consumer declares

    @Test
    @DisplayName("the types order has no use for on the payment and inventory topics are declared and ignored, so a replay never stalls on them")
    void otherTypesAreIgnored() {
        UUID id = UUID.randomUUID();

        for (String type : List.of("payment.succeeded", "payment.failed", "payment.held")) {
            paymentEvents.accept(message("payment_confirmation", id, type, 1, "{}", "payment"));
        }
        for (String type : List.of("reservation.consumed", "box.swapped")) {
            inventoryEvents.accept(message("reservation", id, type, 1, "{}", "inventory"));
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_settlement", Long.class)).isZero();
    }

    @Test
    @DisplayName("a type nobody declared, or a version of one that was never agreed, is refused on its first delivery (never retried)")
    void anUndeclaredEventIsRefused() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> paymentEvents.accept(message("payment_confirmation", id, "payment.refunded", 1, "{}", "payment")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paymentEvents.accept(message("payment_confirmation", id, "payment.confirmed", 2, "{}", "payment")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inventoryEvents.accept(message("reservation", id, "reservation.surprised", 1, "{}", "inventory")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a confirmation that cannot be read (no id, no amount, an amount that is not money) is a broken event: refused on its first delivery")
    void aBrokenConfirmationIsRefused() {
        UUID order = UUID.randomUUID();
        UUID confirmation = UUID.randomUUID();

        assertThatThrownBy(() -> paymentEvents.accept(message("payment_confirmation", confirmation, "payment.confirmed", 1,
                "{\"orderId\":\"" + order + "\",\"gross\":4500.00,\"currency\":\"ZMW\",\"arrivedAt\":\"" + ARRIVED + "\"}", "payment")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paymentEvents.accept(message("payment_confirmation", confirmation, "payment.confirmed", 1,
                "{\"confirmationId\":\"" + confirmation + "\",\"orderId\":\"" + order + "\",\"method\":\"MTN\",\"currency\":\"ZMW\",\"arrivedAt\":\"" + ARRIVED + "\"}", "payment")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paymentEvents.accept(message("payment_confirmation", confirmation, "payment.confirmed", 1,
                confirmedPayload(confirmation, order, "0.00", "ZMW"), "payment"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_settlement WHERE confirmation_id = ?", Long.class, confirmation)).isZero();
    }
}
