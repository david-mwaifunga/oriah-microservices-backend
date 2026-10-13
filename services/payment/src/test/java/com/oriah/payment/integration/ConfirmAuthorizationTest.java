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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who is allowed to settle a payment intent, and whose money moves.
 *
 * <h2>Two live defects this class exists to pin</h2>
 *
 * Both were found while mapping the pricing paths, and both are about
 * AUTHORIZATION rather than about price.
 *
 * <p><b>The charge happened before the ownership check.</b> The confirm
 * handler read
 *
 * <pre>
 *   return service.confirm(id, body.cardNumber())            // charges here
 *           .filter(i -&gt; i.keycloakId().equals(jwt.getSubject()))  // checks here
 *           .orElseThrow(() -&gt; unknownIntent(id));
 * </pre>
 *
 * and {@code PaymentService.confirm} took no subject at all — it found the
 * intent by id, called the gateway, and marked the status. So one customer
 * confirming another's intent moved real money and settled the intent, and
 * the response was a 404: a charge that looks to the caller like nothing
 * happened, and to the victim like they paid.
 *
 * <p><b>The idempotency replay was scoped to nothing.</b> The cache was keyed
 * on the key STRING alone, with no check that the stored row belonged to the
 * intent in the path or to the caller. Two consequences: a known key replays
 * someone else's settled payment body, and — worse for the business — a
 * client reusing a key across two different intents gets the first intent's
 * SUCCEEDED response back and <em>the second intent is never charged</em>.
 *
 * <p>That second consequence is why this is being fixed before the pricing
 * work. Checkout is about to gain a 409 on a stale price, and the storefront
 * deliberately does not regenerate its Idempotency-Key on an error — so a
 * retry after that 409 would have replayed the earlier success and produced a
 * PAID order with no charge taken.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ConfirmAuthorizationTest {

    private static final SimpleGrantedAuthority ROLE_USER =
            new SimpleGrantedAuthority("ROLE_USER");

    private static final String ALICE = "kc-alice";
    private static final String BOB = "kc-bob";

    /** The fake gateway settles anything that is not this card. */
    private static final String GOOD_CARD = "4242424242424242";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PaymentIntentRepository intents;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

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

    private static String body() {
        return "{\"cardNumber\":\"" + GOOD_CARD + "\"}";
    }

    @Test
    @DisplayName("confirming someone else's intent does NOT charge it")
    void foreignConfirmDoesNotCharge() throws Exception {
        PaymentIntent alices = pendingFor(ALICE);

        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", alices.id())
                        .with(as(BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isNotFound());

        // THE assertion. A 404 was always returned; what mattered is whether
        // the money moved before it. Alice's intent must still be PENDING --
        // untouched, not settled by Bob.
        PaymentIntent after = intents.findById(alices.id()).orElseThrow();
        assertThat(after.status())
                .as("Bob must not be able to settle Alice's payment")
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("the owner can still confirm their own intent")
    void ownerCanConfirm() throws Exception {
        PaymentIntent alices = pendingFor(ALICE);

        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", alices.id())
                        .with(as(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(intents.findById(alices.id()).orElseThrow().status())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("an unknown intent id is 404 and settles nothing")
    void unknownIntentIs404() throws Exception {
        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", UUID.randomUUID())
                        .with(as(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an Idempotency-Key cannot be replayed by a DIFFERENT customer")
    void keyIsNotReplayableByAnotherCustomer() throws Exception {
        PaymentIntent alices = pendingFor(ALICE);
        String key = "shared-key-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", alices.id())
                        .with(as(ALICE))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk());

        // Bob presents Alice's key. Previously this replayed her stored
        // response verbatim, disclosing her intent id, amount and status.
        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", alices.id())
                        .with(as(BOB))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("replaying the owner's own key returns the same body without charging again")
    void ownerReplayIsIdempotent() throws Exception {
        PaymentIntent alices = pendingFor(ALICE);
        String key = "alice-key-" + UUID.randomUUID();

        String first = mvc.perform(post("/api/v1/payments/intents/{id}/confirm", alices.id())
                        .with(as(ALICE))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String replayed = mvc.perform(post("/api/v1/payments/intents/{id}/confirm", alices.id())
                        .with(as(ALICE))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The whole point of the mechanism, and it must keep working: the same
        // customer retrying with the same key is served the stored response
        // and the gateway is not called twice.
        assertThat(replayed).isEqualTo(first);
        assertThat(idempotencyKeys.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a key bound to one intent cannot settle a DIFFERENT intent for free")
    void keyIsNotReusableAcrossIntents() throws Exception {
        PaymentIntent first = pendingFor(ALICE);
        PaymentIntent second = pendingFor(ALICE);
        String key = "reused-key-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", first.id())
                        .with(as(ALICE))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk());

        // Same customer, same key, DIFFERENT intent. Previously the cache hit
        // on the key alone and replayed the first intent's SUCCEEDED body --
        // so this second intent was never charged and the caller was told it
        // had succeeded.
        //
        // This is the money-losing case, and it is why this fix lands before
        // checkout gains a pricing 409: the storefront does not regenerate its
        // Idempotency-Key on an error, so a retry after that 409 would have
        // taken this exact path.
        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", second.id())
                        .with(as(ALICE))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isConflict());

        PaymentIntent secondAfter = intents.findById(second.id()).orElseThrow();
        assertThat(secondAfter.status())
                .as("the second intent must not be reported settled without a charge")
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("confirm with no Idempotency-Key still works, since the header is optional")
    void keyIsOptional() throws Exception {
        PaymentIntent alices = pendingFor(ALICE);

        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", alices.id())
                        .with(as(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk());

        assertThat(idempotencyKeys.count()).isZero();
    }
}
