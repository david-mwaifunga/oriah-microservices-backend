package com.oriah.payment.integration;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.gateway.PaymentGateway;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gateway must be reached at most once per intent.
 *
 * <h2>The defect</h2>
 *
 * {@code PaymentIntent.markSucceeded()} and {@code markFailed()} both call
 * {@code requirePending()}, so a terminal intent cannot change state. But that
 * guard runs in {@code PaymentService.confirm} AFTER
 * {@code gateway.charge(...)}:
 *
 * <pre>
 *   var result = gateway.charge(cardNumber, intent.amount(), intent.currency());
 *   switch (result) {
 *       case Success s -&gt; intent.markSucceeded();   // throws here, if terminal
 * </pre>
 *
 * So confirming an already-settled intent charges the card, THEN throws, and
 * the transaction rolls back to a 409. With the fake gateway nothing is lost.
 * With a real one the shopper is charged a second time and the platform keeps
 * no record of it at all, because the row that would have recorded it was
 * rolled back.
 *
 * <p>No guessing or corrupted state is needed to reach it: the
 * {@code Idempotency-Key} header is optional, so two plain confirm calls do it.
 *
 * <p>This is the same shape as the authorization bug fixed alongside it -- an
 * irreversible action placed ahead of the check that governs it -- which is
 * why the fix is the same: decide first, charge second.
 *
 * <h2>What is deliberately NOT changed</h2>
 *
 * The response stays 409 for a terminal intent rather than becoming an
 * idempotent 200. A shopper retrying without an Idempotency-Key should not be
 * told "fine" about a charge this request did not make; the header is the
 * mechanism for a safe retry, and {@code ConfirmAuthorizationTest} covers it.
 * Only the charge is removed, not the status code, so no client contract moves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ConfirmChargesOnceTest {

    private static final SimpleGrantedAuthority ROLE_USER =
            new SimpleGrantedAuthority("ROLE_USER");
    private static final String ALICE = "kc-alice";

    private static final String GOOD_CARD = "4242424242424242";
    private static final String DECLINED_CARD = "4000000000000002";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /**
     * A spy, not a mock: the real FakePaymentGateway still decides the
     * outcome, and we only count the calls. A stub would let this test pass
     * against a gateway whose behaviour had changed underneath it.
     */
    @MockitoSpyBean
    private PaymentGateway gateway;

    @BeforeEach
    void reset() {
        idempotencyKeys.deleteAll();
        intents.deleteAll();
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject(subject));
    }

    private PaymentIntent pendingFor(String owner) {
        return intents.saveAndFlush(new PaymentIntent(
                UUID.randomUUID(), owner, new BigDecimal("4500.00"), "ZMW"));
    }

    private static String body(String card) {
        return "{\"cardNumber\":\"" + card + "\"}";
    }

    @Test
    @DisplayName("confirming a SUCCEEDED intent again does not reach the gateway")
    void secondConfirmDoesNotChargeAgain() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);

        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", intent.id())
                        .with(as(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD_CARD)))
                .andExpect(status().isOk());

        // Same owner, same intent, no Idempotency-Key -- the header is
        // optional, so this is an ordinary second request.
        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", intent.id())
                        .with(as(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD_CARD)))
                .andExpect(status().isConflict());

        // THE assertion. The 409 was always returned; what mattered is whether
        // the card was charged before it.
        verify(gateway, times(1)).charge(any(), any(), any());
        assertThat(intents.findById(intent.id()).orElseThrow().status())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("confirming a FAILED intent again does not reach the gateway either")
    void failedIntentIsNotChargedAgain() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);

        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", intent.id())
                        .with(as(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(DECLINED_CARD)))
                .andExpect(status().isOk());
        assertThat(intents.findById(intent.id()).orElseThrow().status())
                .isEqualTo(PaymentStatus.FAILED);

        // FAILED is terminal here, as requirePending() has always said. A
        // retry with a good card is NOT a re-confirm of this intent; it needs
        // a new intent. The point of this case is that a declined card
        // followed by a retry must not produce a SECOND gateway call against
        // a settled intent -- which is how a decline could become a charge.
        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", intent.id())
                        .with(as(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD_CARD)))
                .andExpect(status().isConflict());

        verify(gateway, times(1)).charge(any(), any(), any());
        assertThat(intents.findById(intent.id()).orElseThrow().status())
                .as("a terminal FAILED intent must not be flipped to SUCCEEDED by a retry")
                .isEqualTo(PaymentStatus.FAILED);
    }
}
