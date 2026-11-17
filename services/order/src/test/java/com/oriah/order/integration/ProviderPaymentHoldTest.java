package com.oriah.order.integration;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.service.HoldJob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * An order and a payment through ZynlePay that is in flight for it (Phase 13).
 *
 * <p>Before payment asks ZynlePay for any money it asks the order, once, to take the attempt: the order must be the shopper's, waiting for payment
 * and holding its pieces, and the pieces are then held for 45 minutes so they are still there when the customer has approved. The order answers
 * what is owed, so the amount asked for is the order's own and never the shopper's.
 *
 * <p>While the order carries the attempt, nothing else may pay or end it: the shopper cannot cancel it or say "I paid another way", staff cannot
 * cancel it or make its delivery free. It lets go when payment says the attempt FAILED, or when the money is being applied. It has no clock: an
 * attempt ZynlePay never answers keeps the order closed to other money until the owner decides it.
 */
class ProviderPaymentHoldTest extends SaleTestBase {

    @Autowired
    private HoldJob holdJob;

    private static RequestPostProcessor bob() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-bob"));
    }

    private static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN"), new SimpleGrantedAuthority("ROLE_DELIVERY_WAIVER"));
    }

    private MvcResult take(RequestPostProcessor who, UUID order, UUID attempt, UUID supersedes) throws Exception {
        String body = "{\"providerPaymentId\":\"" + attempt + "\"" + (supersedes == null ? "" : ",\"supersedes\":\"" + supersedes + "\"") + "}";
        return mvc.perform(post("/api/v1/orders/" + order + "/provider-payment").with(who).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private UUID taken(UUID order) throws Exception {
        UUID attempt = UUID.randomUUID();
        MvcResult result = take(alice(), order, attempt, null);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return attempt;
    }

    private String problem(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentAsString()).as("a problem body").contains("\"type\"");
        String type = json.readTree(result.getResponse().getContentAsString()).get("type").asText();
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private void providerUpdate(UUID order, UUID attempt, String status, String failureCode) {
        paymentEvents.accept(message("provider_payment", attempt, "payment.provider_payment_updated", 1, "{\"providerPaymentId\":\"" + attempt
                + "\",\"orderId\":\"" + order + "\",\"keycloakId\":\"kc-alice\",\"channel\":\"MOBILE_MONEY\",\"method\":\"MTN\",\"status\":\"" + status + "\""
                + (failureCode == null ? "" : ",\"failureCode\":\"" + failureCode + "\"") + ",\"startedAt\":\"" + ARRIVED + "\",\"at\":\"" + ARRIVED + "\"}",
                "payment"));
    }

    /** {@code payment.confirmed} for money a provider collected: the staff shape and, at the end, the provider and its attempt. */
    private UUID providerConfirmed(UUID order, UUID attempt, String gross) {
        UUID confirmation = UUID.randomUUID();
        String payload = confirmedPayload(confirmation, order, gross, "ZMW");
        paymentEvents.accept(message("payment_confirmation", confirmation, "payment.confirmed", 1,
                payload.substring(0, payload.length() - 1) + ",\"provider\":\"ZYNLEPAY\",\"providerPaymentId\":\"" + attempt + "\"}", "payment"));
        return confirmation;
    }

    // ------------------------------------------------------------------ taking the attempt

    @Test
    @DisplayName("the order takes the attempt, answers what is owed (K4,500.00) and holds its pieces for 45 minutes; it stays CREATED and announces no claim")
    void theOrderTakesTheAttempt() throws Exception {
        UUID order = heldOrder();
        UUID attempt = UUID.randomUUID();

        MvcResult result = take(alice(), order, attempt, null);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("orderId").asText()).isEqualTo(order.toString());
        assertThat(new BigDecimal(body.get("total").asText())).isEqualByComparingTo("4500.00");
        assertThat(body.get("currency").asText()).isEqualTo("ZMW");
        assertThat(Instant.parse(body.get("holdExpiresAt").asText())).isCloseTo(Instant.now().plus(Duration.ofMinutes(45)), within(Duration.ofSeconds(30)));
        assertThat(row(order).get("provider_payment_id")).isEqualTo(attempt);
        assertThat(row(order).get("status")).isEqualTo("CREATED");
        assertThat(row(order).get("hold_extension_pending")).isEqualTo(false);
        assertThat(at(row(order).get("hold_expires_at"))).isCloseTo(Instant.now().plus(Duration.ofMinutes(45)), within(Duration.ofSeconds(30)));
        assertThat(events(order, "order.payment_submitted")).as("no claim was made: staff are not asked to confirm anything").isEmpty();
    }

    @Test
    @DisplayName("the same attempt again is the same answer; another attempt is refused while this one stands, unless payment says this one is finished")
    void oneAttemptAtATime() throws Exception {
        UUID order = heldOrder();
        UUID first = taken(order);

        assertThat(take(alice(), order, first, null).getResponse().getStatus()).isEqualTo(200);

        UUID second = UUID.randomUUID();
        MvcResult refused = take(alice(), order, second, null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(refused)).isEqualTo("provider-payment-in-progress");
        assertThat(row(order).get("provider_payment_id")).isEqualTo(first);

        // A retry after a decline: payment knows the first is finished before its FAILED event has reached the order.
        assertThat(take(alice(), order, second, UUID.randomUUID()).getResponse().getStatus()).as("superseding some other attempt").isEqualTo(409);
        assertThat(take(alice(), order, second, first).getResponse().getStatus()).isEqualTo(200);
        assertThat(row(order).get("provider_payment_id")).isEqualTo(second);
    }

    @Test
    @DisplayName("only the shopper's own order, waiting for payment and holding its pieces long enough, takes an attempt")
    void whichOrdersTakeAnAttempt() throws Exception {
        UUID order = heldOrder();
        assertThat(take(bob(), order, UUID.randomUUID(), null).getResponse().getStatus()).as("somebody else's order").isEqualTo(404);
        assertThat(take(alice(), UUID.randomUUID(), UUID.randomUUID(), null).getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/api/v1/orders/" + order + "/provider-payment").contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerPaymentId\":\"" + UUID.randomUUID() + "\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(take(alice(), order, null, null).getResponse().getStatus()).as("no attempt named").isEqualTo(400);

        // Less than two minutes of hold left: the customer could not approve in time, and nothing is taken.
        jdbc.update("UPDATE orders SET hold_expires_at = now() + interval '90 seconds' WHERE id = ?", order);
        MvcResult tooShort = take(alice(), order, UUID.randomUUID(), null);
        assertThat(tooShort.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(tooShort)).isEqualTo("hold-too-short");
        assertThat(row(order).get("provider_payment_id")).isNull();

        UUID claimed = claimedOrder(2);
        MvcResult afterClaim = take(alice(), claimed, UUID.randomUUID(), null);
        assertThat(afterClaim.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(afterClaim)).isEqualTo("payment-already-submitted");
    }

    // ------------------------------------------------------------------ while it stands

    @Test
    @DisplayName("while the attempt stands the order cannot be cancelled (by the shopper or by staff), claimed as paid another way, or have its delivery made free")
    void nothingElsePaysOrEndsTheOrder() throws Exception {
        UUID order = heldOrder();
        taken(order);

        MvcResult cancel = mvc.perform(post("/api/v1/orders/" + order + "/cancel").with(alice())).andReturn();
        assertThat(cancel.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(cancel)).isEqualTo("provider-payment-in-progress");

        MvcResult staffCancel = mvc.perform(post("/api/v1/admin/orders/" + order + "/transition").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"CANCELLED\"}")).andReturn();
        assertThat(staffCancel.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(staffCancel)).isEqualTo("provider-payment-in-progress");

        MvcResult claim = mvc.perform(post("/api/v1/orders/" + order + "/payment-claim").with(alice()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"MTN\",\"reference\":\"MP261199\"}")).andReturn();
        assertThat(claim.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(claim)).isEqualTo("provider-payment-in-progress");

        MvcResult waiver = mvc.perform(post("/api/v1/admin/orders/" + order + "/delivery-waiver").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"a regular customer, agreed on the phone\"}")).andReturn();
        assertThat(waiver.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(waiver)).isEqualTo("provider-payment-in-progress");

        assertThat(row(order).get("status")).isEqualTo("CREATED");
    }

    // ------------------------------------------------------------------ letting go

    @Test
    @DisplayName("a FAILED attempt lets the order go; one that is unresolved or verified does not, and neither does another attempt's failure")
    void onlyItsOwnFailureLetsGo() throws Exception {
        UUID order = heldOrder();
        UUID attempt = taken(order);

        providerUpdate(order, UUID.randomUUID(), "FAILED", "DECLINED");
        providerUpdate(order, attempt, "UNRESOLVED", null);
        providerUpdate(order, attempt, "VERIFIED", null);
        assertThat(row(order).get("provider_payment_id")).as("still carried").isEqualTo(attempt);
        assertThat(mvc.perform(post("/api/v1/orders/" + order + "/cancel").with(alice())).andReturn().getResponse().getStatus())
                .as("VERIFIED arrived before the money itself: the order is still closed").isEqualTo(409);

        providerUpdate(order, attempt, "FAILED", "DECLINED");
        assertThat(row(order).get("provider_payment_id")).isNull();
        assertThat(mvc.perform(post("/api/v1/orders/" + order + "/cancel").with(alice())).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("the attempt's own money pays the order: it is applied like any confirmed money, and the order lets go of the attempt as it begins")
    void itsMoneyPaysTheOrder() throws Exception {
        UUID order = heldOrder();
        UUID attempt = taken(order);

        UUID confirmation = providerConfirmed(order, attempt, "4500.00");

        assertThat(row(order).get("provider_payment_id")).isNull();
        assertThat(row(order).get("status")).isEqualTo("PAID");
        assertThat(row(order).get("paid_confirmation_id")).isEqualTo(confirmation);
    }

    @Test
    @DisplayName("money that cannot pay the order lets go only if it is the attempt's own: other money held for the order leaves the attempt standing")
    void heldMoneyOfAnotherRecordLeavesItStanding() throws Exception {
        UUID order = heldOrder();
        UUID attempt = taken(order);

        // An earlier attempt's money, recorded by the owner for the wrong amount: held, and nothing to do with the attempt in flight.
        providerConfirmed(order, UUID.randomUUID(), "100.00");
        assertThat(row(order).get("provider_payment_id")).isEqualTo(attempt);

        // The attempt's own money, in an amount that is not the order's: held too, and now the attempt is over.
        providerConfirmed(order, attempt, "4400.00");
        assertThat(row(order).get("provider_payment_id")).isNull();
        assertThat(row(order).get("status")).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("money of ANOTHER attempt being applied does not make the order let go of the attempt in flight; once the order is paid it carries none")
    void anotherAttemptsMoneyBeingApplied() throws Exception {
        UUID order = heldOrder();
        UUID attempt = taken(order);
        // The owner recorded an earlier, failed attempt as paid after all; inventory cannot be asked just now, so that money is still being applied.
        doThrow(SaleStubs.feign(503, null)).when(inventory).consume(any(), any());

        providerConfirmed(order, UUID.randomUUID(), "4500.00");

        assertThat(row(order).get("status")).isEqualTo("CREATED");
        assertThat(row(order).get("settling_confirmation_id")).isNotNull();
        assertThat(row(order).get("provider_payment_id")).as("the attempt in flight may still collect: the order stays closed for it").isEqualTo(attempt);

        UUID paid = heldOrder(2);
        taken(paid);
        SaleStubs.inventorySells(inventory);
        providerConfirmed(paid, UUID.randomUUID(), "9000.00");
        assertThat(row(paid).get("status")).isEqualTo("PAID");
        assertThat(row(paid).get("provider_payment_id")).as("a paid order carries no attempt").isNull();
    }

    @Test
    @DisplayName("an attempt that ended while its pieces were being held is not answered 'taken': 409, so payment asks ZynlePay for nothing")
    void anAttemptThatEndedMeanwhile() throws Exception {
        UUID order = heldOrder();
        UUID attempt = UUID.randomUUID();
        doAnswer(inv -> {
            // While inventory is answering, payment gives the attempt up (it was never sent) and says so.
            providerUpdate(order, attempt, "FAILED", "NOT_SENT");
            return new InventoryClient.Reservation(inv.getArgument(0), UUID.randomUUID(), "HELD", Instant.now(), Instant.now().plusSeconds(2_700), null,
                    List.of());
        }).when(inventory).extend(any(), any());

        MvcResult result = take(alice(), order, attempt, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(result)).isEqualTo("provider-payment-ended");
        assertThat(row(order).get("provider_payment_id")).isNull();
    }

    // ------------------------------------------------------------------ the hold

    @Test
    @DisplayName("inventory silent: 503, the attempt is kept, and the same attempt again is 503 until the pieces are really held; the hold job finishes it without announcing a claim")
    void theHoldIsStretchedBeforeAnythingIsAsked() throws Exception {
        UUID order = heldOrder();
        UUID attempt = UUID.randomUUID();
        doThrow(SaleStubs.feign(503, null)).when(inventory).extend(any(), any());

        assertThat(take(alice(), order, attempt, null).getResponse().getStatus()).isEqualTo(503);
        assertThat(row(order).get("provider_payment_id")).isEqualTo(attempt);
        assertThat(row(order).get("hold_extension_pending")).isEqualTo(true);
        assertThat(take(alice(), order, attempt, null).getResponse().getStatus()).as("never 200 while the pieces are not held for it").isEqualTo(503);

        SaleStubs.inventoryExtends(inventory);
        holdJob.run();

        assertThat(row(order).get("hold_extension_pending")).isEqualTo(false);
        assertThat(row(order).get("status")).isEqualTo("CREATED");
        assertThat(at(row(order).get("hold_expires_at"))).isCloseTo(Instant.now().plus(Duration.ofMinutes(45)), within(Duration.ofSeconds(30)));
        assertThat(events(order, "order.payment_submitted")).isEmpty();
        assertThat(take(alice(), order, attempt, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a stretch the hold job finishes later is judged by the same rule: one that leaves under two minutes makes the order let the attempt go")
    void aStretchFinishedLaterThatIsTooShort() throws Exception {
        UUID order = heldOrder();
        UUID attempt = UUID.randomUUID();
        doThrow(SaleStubs.feign(503, null)).when(inventory).extend(any(), any());
        assertThat(take(alice(), order, attempt, null).getResponse().getStatus()).isEqualTo(503);
        doAnswer(inv -> new InventoryClient.Reservation(inv.getArgument(0), UUID.randomUUID(), "HELD", Instant.now(), Instant.now().plusSeconds(60), null,
                List.of())).when(inventory).extend(any(), any());

        holdJob.run();

        assertThat(row(order).get("provider_payment_id")).isNull();
        assertThat(row(order).get("hold_extension_pending")).isEqualTo(false);
        // The repeat of the start is then refused on the same ground, and nothing is asked of ZynlePay.
        MvcResult again = take(alice(), order, attempt, null);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(again)).isEqualTo("hold-too-short");
    }

    @Test
    @DisplayName("a hold inventory will not stretch far enough (its own cap) is too short: 409, and the order does not keep the attempt")
    void aHoldInventoryWillNotStretch() throws Exception {
        UUID order = heldOrder();
        doAnswer(inv -> new InventoryClient.Reservation(inv.getArgument(0), UUID.randomUUID(), "HELD", Instant.now(), Instant.now().plusSeconds(60), null,
                List.of())).when(inventory).extend(any(), any());

        MvcResult result = take(alice(), order, UUID.randomUUID(), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(result)).isEqualTo("hold-too-short");
        assertThat(row(order).get("provider_payment_id")).isNull();
    }

    @Test
    @DisplayName("a hold that is gone at inventory: 409 hold-expired and the order EXPIRES; an attempt the order still carries when its pieces lapse does not stop that")
    void aHoldThatIsGone() throws Exception {
        UUID order = heldOrder();
        doThrow(SaleStubs.feign(409, "{\"type\":\"https://oriah.dev/problems/reservation-expired\",\"status\":409}")).when(inventory).extend(any(), any());

        MvcResult result = take(alice(), order, UUID.randomUUID(), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(result)).isEqualTo("hold-expired");
        assertThat(row(order).get("status")).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("an attempt that ends while its hold was never stretched does not leave the order outside the hold job: it is tidied and can lapse like any other")
    void anAttemptThatEndsWithItsHoldStillPending() throws Exception {
        UUID order = heldOrder();
        UUID attempt = UUID.randomUUID();
        doThrow(SaleStubs.feign(503, null)).when(inventory).extend(any(), any());
        take(alice(), order, attempt, null);

        providerUpdate(order, attempt, "FAILED", "NOT_SENT");

        assertThat(row(order).get("provider_payment_id")).isNull();
        assertThat(row(order).get("hold_extension_pending")).as("nothing is waiting to be stretched any more").isEqualTo(false);
    }
}
