package com.oriah.order.integration;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two lists the people who confirm money work from (Phase 7): who SAYS they have paid and is waiting to be matched against the bank or the
 * provider ({@code awaiting-payment}), and money that is stuck ({@code settlements}). Order serves them because it owns the data; they are for
 * SALES_AGENT (the capability a sales person, a manager and the owner hold), never for ORDER_ADMIN alone and never for a shopper.
 */
class SalesQueuesTest extends SaleTestBase {

    private static final String AWAITING = "/api/v1/admin/orders/awaiting-payment";
    private static final String SETTLEMENTS = "/api/v1/admin/orders/settlements";

    private static RequestPostProcessor sales() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")).jwt(j -> j.subject("kc-sam"));
    }

    private JsonNode list(String path) throws Exception {
        MvcResult result = mvc.perform(get(path).with(sales())).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ who may read them

    @Test
    @DisplayName("sales staff read both lists; a shopper and staff with only the order capability are refused (403)")
    void whoMayRead() throws Exception {
        for (String path : List.of(AWAITING, SETTLEMENTS)) {
            mvc.perform(get(path).with(sales())).andExpect(status().isOk());
            mvc.perform(get(path).with(alice())).andExpect(status().isForbidden());
            mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN")))).andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------------ awaiting payment

    @Test
    @DisplayName("awaiting-payment lists the orders whose shopper said they paid, soonest to lapse first, with the claim staff must match")
    void awaitingPayment() throws Exception {
        UUID first = claimedOrder(1);
        UUID second = claimedOrder(2);
        UUID unclaimed = heldOrder(3);
        jdbc.update("UPDATE orders SET hold_expires_at = now() + interval '3 hours' WHERE id = ?", first);
        jdbc.update("UPDATE orders SET hold_expires_at = now() + interval '1 hour' WHERE id = ?", second);

        JsonNode rows = list(AWAITING);

        assertThat(rows.size()).as("the unclaimed order is not waiting to be matched against anything").isEqualTo(2);
        assertThat(rows.get(0).get("orderId").asText()).as("the one that lapses first").isEqualTo(second.toString());
        assertThat(rows.get(1).get("orderId").asText()).isEqualTo(first.toString());
        JsonNode row = rows.get(0);
        assertThat(row.get("status").asText()).isEqualTo("PAYMENT_SUBMITTED");
        assertThat(row.get("total").decimalValue()).as("two rings: the order that lapses first").isEqualByComparingTo("9000.00");
        assertThat(rows.get(1).get("total").decimalValue()).as("one ring").isEqualByComparingTo("4500.00");
        assertThat(row.get("currency").asText()).isEqualTo("ZMW");
        assertThat(row.get("claimMethod").asText()).isEqualTo("MTN");
        assertThat(row.get("claimReference").asText()).isEqualTo("MP261113");
        assertThat(row.get("claimSubmittedAt").asText()).isNotBlank();
        assertThat(row.get("holdExpiresAt").asText()).isNotBlank();
        assertThat(row.get("customer").asText()).isEqualTo("kc-alice");
        assertThat(unclaimed).isNotNull();
    }

    @Test
    @DisplayName("an order that is paid, ended, or having money applied is not in the list")
    void awaitingPaymentLeavesOutWhatIsNotWaiting() throws Exception {
        UUID paid = claimedOrder(1);
        UUID rejected = claimedOrder(2);
        UUID settling = claimedOrder(3);
        confirmed(paid, "4500.00");
        rejected(UUID.fromString(String.valueOf(row(rejected).get("claim_id"))), rejected);
        jdbc.update("UPDATE orders SET settling_confirmation_id = ? WHERE id = ?", UUID.randomUUID(), settling);

        assertThat(list(AWAITING).size()).isZero();
    }

    @Test
    @DisplayName("looking an order up by id finds it while it can still take money (waiting, claimed or lapsed), so staff can confirm money for a customer who has not typed a reference; not when it is paid or cancelled or unknown")
    void lookUpByOrderId() throws Exception {
        UUID waiting = heldOrder();
        UUID lapsed = heldOrder(2);
        jdbc.update("UPDATE orders SET status = 'EXPIRED', hold_state = 'RELEASED' WHERE id = ?", lapsed);
        UUID paid = heldOrder(3);
        confirmed(paid, "13500.00");
        UUID cancelled = heldOrder(4);
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelled);

        assertThat(list(AWAITING + "?orderId=" + waiting).get(0).get("status").asText()).isEqualTo("CREATED");
        assertThat(list(AWAITING + "?orderId=" + lapsed).get(0).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(list(AWAITING + "?orderId=" + lapsed).get(0).get("total").decimalValue()).isEqualByComparingTo("9000.00");
        assertThat(list(AWAITING + "?orderId=" + paid).size()).isZero();
        assertThat(list(AWAITING + "?orderId=" + cancelled).size()).isZero();
        assertThat(list(AWAITING + "?orderId=" + UUID.randomUUID()).size()).isZero();
    }

    // ------------------------------------------------------------------ settlements

    @Test
    @DisplayName("money that could not pay a sale is listed with the reason, so staff can see why it is held")
    void unsettledMoney() throws Exception {
        UUID id = heldOrder();
        UUID wrong = confirmed(id, "4000.00");
        UUID stranger = confirmed(UUID.randomUUID(), "100.00");

        JsonNode rows = list(SETTLEMENTS + "?state=UNSETTLED");

        assertThat(rows.size()).isEqualTo(2);
        JsonNode theWrongOne = null;
        for (JsonNode row : rows) {
            if (row.get("confirmationId").asText().equals(wrong.toString())) {
                theWrongOne = row;
            }
        }
        assertThat(theWrongOne).isNotNull();
        assertThat(theWrongOne.get("reason").asText()).isEqualTo("WRONG_AMOUNT");
        assertThat(theWrongOne.get("orderId").asText()).isEqualTo(id.toString());
        assertThat(theWrongOne.get("method").asText()).isEqualTo("MTN");
        assertThat(theWrongOne.get("reference").asText()).isEqualTo("MP261113");
        assertThat(theWrongOne.get("gross").decimalValue()).isEqualByComparingTo("4000.00");
        assertThat(theWrongOne.get("arrivedAt").asText()).isNotBlank();
        assertThat(stranger).isNotNull();
    }

    @Test
    @DisplayName("a sale waiting for a BOX is listed with the box named, and money in flight longer than five minutes is flagged stuck")
    void inFlightAndBlocked() throws Exception {
        // Both are placed BEFORE inventory is made to fail: a refused hold would be no order at all.
        UUID blockedOrder = heldOrder();
        UUID silentOrder = heldOrder(2);
        UUID reservation = (UUID) row(blockedOrder).get("reservation_id");
        doThrow(SaleStubs.feign(409, "{\"type\":\"https://oriah.dev/problems/reservation-expired\",\"status\":409}")).when(inventory).consume(eq(reservation), any());
        doThrow(SaleStubs.feign(409, SaleStubs.shortage(SaleStubs.BOX_VARIANT, "BOX", 1, 0))).when(inventory).reserve(any());
        UUID blocked = confirmed(blockedOrder, "4500.00");

        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(eq((UUID) row(silentOrder).get("reservation_id")), any());
        UUID silent = confirmed(silentOrder, "9000.00");
        jdbc.update("UPDATE order_settlement SET received_at = now() - interval '10 minutes' WHERE confirmation_id = ?", silent);

        JsonNode rows = list(SETTLEMENTS + "?state=IN_FLIGHT");

        assertThat(rows.size()).isEqualTo(2);
        for (JsonNode row : rows) {
            if (row.get("confirmationId").asText().equals(blocked.toString())) {
                assertThat(row.get("state").asText()).isEqualTo("BLOCKED_BOX");
                assertThat(row.get("stuck").asBoolean()).as("it has not waited five minutes yet").isFalse();
                assertThat(row.get("boxVariantIds").get(0).asText()).as("which box staff must receive").isEqualTo(SaleStubs.BOX_VARIANT.toString());
            } else {
                assertThat(row.get("confirmationId").asText()).isEqualTo(silent.toString());
                assertThat(row.get("state").asText()).isEqualTo("CONSUMING");
                assertThat(row.get("stuck").asBoolean()).isTrue();
                assertThat(row.get("attempts").asInt()).isEqualTo(1);
            }
        }
        assertThat(list(SETTLEMENTS + "?state=BLOCKED_BOX").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a paid sale whose money was voided afterwards is listed, because the pieces cannot be un-sold and a person must look; an ordinary paid sale is not")
    void voidedAfterPaid() throws Exception {
        UUID ordinary = heldOrder();
        confirmed(ordinary, "4500.00");
        UUID oddOrder = heldOrder(2);
        UUID odd = confirmed(oddOrder, "9000.00");
        voided(odd, oddOrder);

        JsonNode all = list(SETTLEMENTS);
        JsonNode voided = list(SETTLEMENTS + "?state=VOIDED");

        assertThat(all.size()).isEqualTo(1);
        assertThat(all.get(0).get("confirmationId").asText()).isEqualTo(odd.toString());
        assertThat(all.get(0).get("state").asText()).isEqualTo("DONE");
        assertThat(all.get(0).get("voidedAt").asText()).isNotBlank();
        assertThat(voided.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown state filter is a 400, not an empty answer")
    void anUnknownFilter() throws Exception {
        mvc.perform(get(SETTLEMENTS + "?state=WHATEVER").with(sales())).andExpect(status().isBadRequest());
    }
}
