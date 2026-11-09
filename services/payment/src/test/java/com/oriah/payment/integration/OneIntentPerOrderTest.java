package com.oriah.payment.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * One order, one intent that can take its money.
 *
 * <p>An intent that is PENDING, CHARGING or SUCCEEDED is a live claim on the
 * order's money. A second one would be a second way to take the same money:
 * two confirms, on two intents, both capture. So creating one while another
 * is live is refused (409), and the database's partial unique index refuses
 * it too when two requests race past the check together. A FAILED intent
 * took nothing, so it leaves room for a new one -- that is how a shopper
 * whose card was declined tries again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class OneIntentPerOrderTest {

    private static final String ALICE = "kc-alice";

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

    @Test
    @DisplayName("a second intent while one is PENDING is refused, and nothing is written")
    void secondIntentBesidePendingIsRefused() throws Exception {
        UUID order = UUID.randomUUID();
        create(order).andExpect(status().isCreated());

        create(order)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Order " + order + " already has a payment intent that "
                        + "is pending, being charged or paid. A new one can be made only if that one fails."));

        assertThat(intentsFor(order)).hasSize(1);
    }

    @Test
    @DisplayName("a second intent while one is being charged or paid is refused")
    void secondIntentBesideChargingOrPaidIsRefused() throws Exception {
        UUID charging = UUID.randomUUID();
        PaymentIntent inFlight = new PaymentIntent(charging, ALICE, new BigDecimal("100.00"), "ZMW");
        inFlight.startCharging(UUID.randomUUID());
        intents.saveAndFlush(inFlight);

        UUID paid = UUID.randomUUID();
        PaymentIntent settled = new PaymentIntent(paid, ALICE, new BigDecimal("100.00"), "ZMW");
        settled.startCharging(UUID.randomUUID());
        settled.markSucceeded("fake_ch_paid", Instant.now());
        intents.saveAndFlush(settled);

        create(charging).andExpect(status().isConflict());
        create(paid).andExpect(status().isConflict());

        assertThat(intentsFor(charging)).hasSize(1);
        assertThat(intentsFor(paid)).hasSize(1);
    }

    @Test
    @DisplayName("after a FAILED intent a new one can be made, so the shopper can try again")
    void aFailedIntentAllowsAReplacement() throws Exception {
        UUID order = UUID.randomUUID();
        PaymentIntent declined = new PaymentIntent(order, ALICE, new BigDecimal("100.00"), "ZMW");
        declined.startCharging(UUID.randomUUID());
        declined.markFailed("card declined", Instant.now());
        intents.saveAndFlush(declined);

        create(order).andExpect(status().isCreated());

        assertThat(intentsFor(order)).extracting(PaymentIntent::status)
                .containsExactlyInAnyOrder(PaymentStatus.FAILED, PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("many requests for one order at the same moment: one intent, every other request 409")
    void concurrentCreationsMakeOneIntent() throws Exception {
        int callers = 6;
        for (int round = 0; round < 5; round++) {
            UUID order = UUID.randomUUID();
            CyclicBarrier start = new CyclicBarrier(callers);
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            try {
                List<Future<Integer>> statuses = new ArrayList<>();
                for (int i = 0; i < callers; i++) {
                    statuses.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return create(order).andReturn().getResponse().getStatus();
                    }));
                }
                List<Integer> answered = new ArrayList<>();
                for (Future<Integer> status : statuses) {
                    answered.add(status.get(60, TimeUnit.SECONDS));
                }

                assertThat(answered).as("round %d", round)
                        .containsOnly(201, 409)
                        .filteredOn(code -> code == 201).hasSize(1);
                assertThat(intentsFor(order)).hasSize(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions create(UUID order) throws Exception {
        return mvc.perform(post("/api/v1/payments/intents")
                .with(as(ALICE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + order + "\",\"amount\":4500.00,\"currency\":\"ZMW\"}"));
    }

    private List<PaymentIntent> intentsFor(UUID order) {
        return intents.findAll().stream().filter(i -> i.orderId().equals(order)).toList();
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject(subject));
    }
}
