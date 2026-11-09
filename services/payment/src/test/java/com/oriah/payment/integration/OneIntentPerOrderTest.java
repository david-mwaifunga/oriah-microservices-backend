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

import com.oriah.payment.OrderAnswers;
import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.client.OrderClient;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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

    @MockitoBean
    private OrderClient orders;

    @BeforeEach
    void reset() {
        idempotencyKeys.deleteAll();
        intents.deleteAll();
        // Creating an intent asks order whose order it is. Here every order is
        // alice's, for K4,500.00 -- what create() asks for -- because this
        // class is about the one-live-intent rule; CreateIntentOwnershipTest
        // is where order says anything else. Set before any test thread
        // starts, so the concurrent test reads it without stubbing on threads.
        doAnswer(asked -> OrderAnswers.yours(asked.getArgument(0), "4500.00", "ZMW"))
                .when(orders).order(any());
    }

    @Test
    @DisplayName("the same create again -- its first answer lost -- gets the same intent back: 200, one row")
    void aRepeatOfTheSameCreateGetsTheSameIntent() throws Exception {
        // Checkout retries createIntent up to three times. If the first create
        // was saved and only its answer was lost, the retry must be given that
        // intent -- a 409 would leave checkout with a PENDING intent whose id
        // it never received, and every further create for the order refused.
        UUID order = UUID.randomUUID();
        String first = create(order)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstId = com.jayway.jsonpath.JsonPath.read(first, "$.id");

        create(order)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.amount").value(4500.00))
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(intentsFor(order)).hasSize(1);
    }

    @Test
    @DisplayName("a PENDING intent for a different amount is not handed back: 409, nothing written")
    void aPendingIntentForADifferentAmountIsRefused() throws Exception {
        // Made before payment checked the amount against the order's total. It
        // is not the intent this request asks for, so it is not handed back --
        // and a second one would be a second way to take the order's money.
        UUID order = UUID.randomUUID();
        intents.saveAndFlush(new PaymentIntent(order, ALICE, new BigDecimal("100.00"), "ZMW"));

        create(order)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Order " + order + " already has a payment intent that "
                        + "is pending, being charged or paid. A new one can be made only if that one fails."));

        assertThat(intentsFor(order)).hasSize(1);
    }

    @Test
    @DisplayName("a PENDING intent in a different currency is not handed back: 409, nothing written")
    void aPendingIntentInADifferentCurrencyIsRefused() throws Exception {
        UUID order = UUID.randomUUID();
        intents.saveAndFlush(new PaymentIntent(order, ALICE, new BigDecimal("4500.00"), "USD"));

        create(order).andExpect(status().isConflict());

        assertThat(intentsFor(order)).extracting(PaymentIntent::currency).containsExactly("USD");
    }

    @Test
    @DisplayName("somebody else's PENDING intent for the order is never handed to the owner: 409")
    void somebodyElsesPendingIntentIsNotHandedOver() throws Exception {
        // One made by a stranger before payment asked order whose order it
        // was. The owner cannot confirm it (confirm is owner-only), so handing
        // it over would only give the owner an intent they cannot pay -- and
        // tell them a stranger's intent id.
        UUID order = UUID.randomUUID();
        PaymentIntent strangers = intents.saveAndFlush(
                new PaymentIntent(order, "kc-bob", new BigDecimal("4500.00"), "ZMW"));

        String body = create(order)
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(strangers.id().toString());
        assertThat(intentsFor(order)).hasSize(1);
    }

    @Test
    @DisplayName("a second intent while one is being charged or paid is refused, even for the same amount")
    void secondIntentBesideChargingOrPaidIsRefused() throws Exception {
        // The same amount and currency as create() asks for, so only the
        // status can be what refuses it: a charge in flight, or already paid,
        // is never answered "here is your intent, go and confirm it".
        UUID charging = UUID.randomUUID();
        PaymentIntent inFlight = new PaymentIntent(charging, ALICE, new BigDecimal("4500.00"), "ZMW");
        inFlight.startCharging(UUID.randomUUID());
        intents.saveAndFlush(inFlight);

        UUID paid = UUID.randomUUID();
        PaymentIntent settled = new PaymentIntent(paid, ALICE, new BigDecimal("4500.00"), "ZMW");
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
    @DisplayName("many identical creates for one order at the same moment: one intent, one 201, every other 200 with it")
    void concurrentCreationsMakeOneIntent() throws Exception {
        // The database's unique index is what decides a true race: the losers'
        // inserts are refused. A loser asked for exactly the intent the winner
        // made, so it is given that intent (200), not a 409.
        int callers = 6;
        for (int round = 0; round < 5; round++) {
            UUID order = UUID.randomUUID();
            CyclicBarrier start = new CyclicBarrier(callers);
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            try {
                List<Future<org.springframework.mock.web.MockHttpServletResponse>> answers = new ArrayList<>();
                for (int i = 0; i < callers; i++) {
                    answers.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return create(order).andReturn().getResponse();
                    }));
                }
                List<org.springframework.mock.web.MockHttpServletResponse> responses = new ArrayList<>();
                for (Future<org.springframework.mock.web.MockHttpServletResponse> answer : answers) {
                    responses.add(answer.get(60, TimeUnit.SECONDS));
                }

                assertThat(responses).extracting(r -> r.getStatus()).as("round %d", round)
                        .containsOnly(201, 200)
                        .filteredOn(code -> code == 201).hasSize(1);
                List<String> ids = new ArrayList<>();
                for (var response : responses) {
                    ids.add(com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.id"));
                }
                assertThat(ids).as("round %d: every caller holds the same intent", round)
                        .containsOnly(intentsFor(order).get(0).id().toString());
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
