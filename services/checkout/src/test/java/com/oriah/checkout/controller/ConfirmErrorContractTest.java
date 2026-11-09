package com.oriah.checkout.controller;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.checkout.exception.CartEmptyException;
import com.oriah.checkout.exception.DownstreamRejectedException;
import com.oriah.checkout.exception.DownstreamUnavailableException;
import com.oriah.checkout.exception.PaymentCapturedWithoutOrderException;
import com.oriah.checkout.exception.PaymentConfirmationFailedException;
import com.oriah.checkout.exception.PaymentDeclinedException;
import com.oriah.checkout.exception.PromotionCodesNotAcceptedException;
import com.oriah.checkout.service.CheckoutService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP status and problem body each checkout failure produces.
 *
 * <h2>Why this exists alongside {@code ConfirmFailurePathsTest}</h2>
 *
 * <p>That test proves the saga <em>throws</em> the right exception. It cannot
 * prove the status a caller actually receives — an exception nothing maps still
 * reaches the client as a bare 500, and the status is the only part of this
 * contract the storefront branches on. So the two halves are pinned separately:
 * the saga's decisions there, the wire contract here.
 *
 * <p>It is also where the statuses get checked for being <em>usable</em>, not
 * merely non-200: 503 must carry {@code Retry-After} for the retry it invites
 * to be well-behaved, and the 500s must not echo internal topology back to a
 * shopper.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ConfirmErrorContractTest {

    private static final String PROBLEM_BASE = "https://oriah.dev/problems/";

    /**
     * A valid body: validation fires before the handler, so this must pass it.
     *
     * <p>deliveryOption and quoteDigest are REQUIRED, and omitting either turns
     * every test in this class into a 400 -- a red bar that reads as a
     * status-mapping regression when the real cause is a stale fixture. The
     * digest's value is irrelevant here because CheckoutService is mocked;
     * these tests are about the controller and the advice, not about pricing.
     */
    private static final String VALID_BODY = """
            {"shippingAddressId":"11111111-1111-1111-1111-111111111111",
             "deliveryOption":"SELF_COLLECT",
             "cardNumber":"4242424242424242",
             "quoteDigest":"0000000000000000000000000000000000000000000000000000000000000000"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CheckoutService service;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("an empty cart is 409 application/problem+json, not 200")
    void emptyCartIsConflict() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new CartEmptyException());

        confirm().andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "cart-empty"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    @DisplayName("a promotion code is 422 promotion-codes-not-accepted, in words the shopper can act on")
    void aPromotionCodeIsUnprocessable() throws Exception {
        // Codes are retired until Phase 7's scoped promotions. Refused out
        // loud, never silently ignored: a shopper whose code was dropped
        // without a word would believe the discount was applied.
        when(service.confirm(any(), any())).thenThrow(new PromotionCodesNotAcceptedException());

        confirm().andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "promotion-codes-not-accepted"))
                .andExpect(jsonPath("$.detail").value(
                        "Promotion codes are not accepted at the moment. Remove the code to continue."));
    }

    // ------------------------------------------- what confirm accepts (item 2, 4.4)

    private org.springframework.test.web.servlet.ResultActions confirmWith(String body) throws Exception {
        return mvc.perform(post("/api/v1/checkout/confirm")
                .with(authenticated())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static final String DIGEST = "\"quoteDigest\":\"" + "0".repeat(64) + "\"";

    @Test
    @DisplayName("the delivery option is required: confirm charges the option the shopper named")
    void theDeliveryOptionIsRequired() throws Exception {
        confirmWith("{\"cardNumber\":\"4242424242424242\"," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("deliveryOption")));
    }

    @Test
    @DisplayName("a delivery option that is not an option code is a 400")
    void aMalformedDeliveryOptionIsRefused() throws Exception {
        confirmWith("{\"deliveryOption\":\"self collect\",\"cardNumber\":\"4242424242424242\"," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("deliveryOption")));
    }

    @Test
    @DisplayName("a quote digest that is not 64 lower-case hex characters is a 400")
    void aMalformedQuoteDigestIsRefused() throws Exception {
        confirmWith("{\"deliveryOption\":\"SELF_COLLECT\",\"cardNumber\":\"4242424242424242\","
                + "\"quoteDigest\":\"any-non-blank-token\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("quoteDigest")));
    }

    @Test
    @DisplayName("self-collect needs no address")
    void selfCollectNeedsNoAddress() throws Exception {
        confirmWith("{\"deliveryOption\":\"SELF_COLLECT\",\"cardNumber\":\"4242424242424242\"," + DIGEST + "}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("any other option still needs somewhere to deliver to")
    void aDeliveryNeedsAnAddress() throws Exception {
        confirmWith("{\"deliveryOption\":\"OWN_DELIVERY\",\"cardNumber\":\"4242424242424242\"," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("shippingAddressId")));
    }

    @Test
    @DisplayName("a preview for an option that is not an option code is a 400")
    void aMalformedPreviewOptionIsRefused() throws Exception {
        mvc.perform(post("/api/v1/checkout/preview")
                        .with(authenticated())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryOption\":\"self collect\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("deliveryOption")));
    }

    @Test
    @DisplayName("a preview needs no country: delivery is within Zambia")
    void aPreviewNeedsNoCountry() throws Exception {
        mvc.perform(post("/api/v1/checkout/preview")
                        .with(authenticated())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a downstream outage is 503 WITH Retry-After, so the retry it invites is well-behaved")
    void downstreamOutageIsRetryableServiceUnavailable() throws Exception {
        when(service.confirm(any(), any())).thenThrow(
                new DownstreamUnavailableException("order", new RuntimeException("refused")));

        confirm().andExpect(status().isServiceUnavailable())
                // The header is the point of choosing 503. Without it a client
                // either gives up or hammers; this is also the one status the
                // gateway and Resilience4j will retry for us.
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "service-unavailable"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    @DisplayName("a declined card is 402 and carries the processor's reason")
    void declinedCardIsPaymentRequired() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new PaymentDeclinedException("card declined"));

        confirm().andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "payment-declined"))
                // The shopper needs to know whether to try another card, and
                // the processor's reason reveals nothing about our system.
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("card declined")));
    }

    @Test
    @DisplayName("an unconfirmable payment is 502, which is not the same answer as a decline")
    void unconfirmablePaymentIsBadGateway() throws Exception {
        when(service.confirm(any(), any())).thenThrow(
                new PaymentConfirmationFailedException("the payment service gave no usable answer", null));

        confirm().andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "payment-confirmation-failed"))
                // The truth, in the shopper's terms. By the time confirm runs
                // the order IS placed (CREATED, unpaid), and the card may or
                // may not have been charged. This used to say "Your order has
                // not been placed; check your payment method before retrying"
                // -- false on both counts, and an invitation to a second order.
                // Nor "check your orders before trying again": the order shows
                // as pending whether or not the charge went through, so that
                // check cannot tell the shopper anything, and trying again
                // can charge them twice.
                .andExpect(jsonPath("$.detail").value(
                        "Your order is recorded, but its payment could not be confirmed (the payment "
                                + "service gave no usable answer), so the charge may or may not have gone through. "
                                + "Please do not pay again: the order shows as pending until the payment has been "
                                + "checked. Contact us if you were charged."));
    }

    @Test
    @DisplayName("the API description of the 502 says the order is recorded, not that it stays unplaced")
    void theDocumented502SaysTheOrderIsRecorded() throws Exception {
        // The storefront's types and the Postman collection are generated from
        // this description, so it must be as true as the problem detail.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/checkout/confirm'].post.responses['502'].description").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("The order IS recorded"),
                                org.hamcrest.Matchers.containsString("Do not offer to pay again"),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Check the shopper's orders")),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unplaced")))));
    }

    @Test
    @DisplayName("money captured with no order is 500 and names the intent to reconcile")
    void capturedWithoutOrderIsInternalErrorNamingTheIntent() throws Exception {
        UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID intentId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(service.confirm(any(), any())).thenThrow(
                new PaymentCapturedWithoutOrderException(orderId, intentId, null));

        confirm().andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "payment-captured-without-order"))
                // The shopper has been charged. The one thing that makes this
                // recoverable for them is a reference support can act on, and
                // it is their own payment -- the happy path hands them the same
                // id in paymentIntentId.
                .andExpect(jsonPath("$.paymentIntentId").value(intentId.toString()))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("captured")));
    }

    @Test
    @DisplayName("a downstream refusing our request is a generic 500 that names no internal service")
    void rejectedRequestDoesNotLeakTopology() throws Exception {
        when(service.confirm(any(), any())).thenThrow(
                new DownstreamRejectedException("order", 400, new RuntimeException("bad payload")));

        confirm().andExpect(status().isInternalServerError())
                // Deliberately the shared catch-all's generic body. The
                // exception message names the service and its status for the
                // log; "the order service rejected ... with 400" is internal
                // topology and a shopper can do nothing with it.
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "internal-server-error"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("order"))));
    }


    @Test
    @DisplayName("the Idempotency-Key header is read off the request, not dropped")
    void idempotencyKeyIsReadFromTheHeader() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new CartEmptyException());

        mvc.perform(post("/api/v1/checkout/confirm")
                .with(authenticated())
                .header("Idempotency-Key", "idem-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY));

        org.mockito.Mockito.verify(service).confirm(any(), org.mockito.ArgumentMatchers.eq("idem-123"));
    }

    private org.springframework.test.web.servlet.ResultActions confirm() throws Exception {
        return mvc.perform(post("/api/v1/checkout/confirm")
                .with(authenticated())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY));
    }

    private static RequestPostProcessor authenticated() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
