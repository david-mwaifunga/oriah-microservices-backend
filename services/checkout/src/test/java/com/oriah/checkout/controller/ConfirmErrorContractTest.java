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
import com.oriah.checkout.exception.PromotionCodeInvalidException;
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

    /** A valid body: validation fires before the handler, so this must pass it. */
    private static final String VALID_BODY = """
            {"shippingAddressId":"11111111-1111-1111-1111-111111111111",
             "destinationCountry":"NG",
             "cardNumber":"4242424242424242"}
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
    @DisplayName("an unusable promotion code is 422 and does not say why it was refused")
    void invalidPromoIsUnprocessable() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new PromotionCodeInvalidException("SPRING10"));

        confirm().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "promotion-code-invalid"))
                // promotion-service answers one 404 for unknown / expired /
                // disabled / exhausted so the response cannot be used to probe
                // which guard failed, nor to confirm a guessed code exists.
                // Translating the status must not undo that.
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("expired"))))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SPRING10"))));
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
                new PaymentConfirmationFailedException("the payment service did not answer", null));

        confirm().andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "payment-confirmation-failed"));
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
