package com.oriah.payment.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.gateway.PaymentGateway;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two requests at the same moment never take the money twice.
 *
 * <p>The guards, in the order a request meets them in tx1: the
 * Idempotency-Key is reserved by an INSERT the database lets in once; the
 * intent moves PENDING -&gt; CHARGING under its version, which only one
 * transaction can do. Whoever loses either race is refused (409) before the
 * gateway is called. Each test counts the gateway's charge calls through a
 * spy: every call is a capture with the good card, so the count is the
 * number of times the shopper paid.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ConfirmConcurrencyTest {

    private static final String ALICE = "kc-alice";
    private static final String GOOD_CARD = "4242424242424242";
    private static final int ROUNDS = 8;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoSpyBean
    private PaymentGateway gateway;

    private ExecutorService pool;

    @BeforeEach
    void reset() {
        idempotencyKeys.deleteAll();
        intents.deleteAll();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopThreads() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("two confirms of one intent at once: one capture, one 409")
    void concurrentConfirmsOfOneIntent() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            PaymentIntent intent = pendingFor(ALICE);

            List<Integer> statuses = together(
                    confirm(intent.id(), null),
                    confirm(intent.id(), null));

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 409);
            assertThat(intents.findById(intent.id()).orElseThrow().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        }
        verify(gateway, times(ROUNDS)).charge(any(), any(), any(), any());
    }

    @Test
    @DisplayName("one Idempotency-Key on two intents at once: one capture, the other intent untouched")
    void oneKeyOnTwoIntentsAtOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            PaymentIntent first = pendingFor(ALICE);
            PaymentIntent second = pendingFor(ALICE);
            String key = "shared-" + UUID.randomUUID();

            List<Integer> statuses = together(
                    confirm(first.id(), key),
                    confirm(second.id(), key));

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 409);
            assertThat(List.of(statusOf(first), statusOf(second)))
                    .as("round %d: exactly one of the two was charged", round)
                    .containsExactlyInAnyOrder(PaymentStatus.SUCCEEDED, PaymentStatus.PENDING);
        }
        verify(gateway, times(ROUNDS)).charge(any(), any(), any(), any());
    }

    @Test
    @DisplayName("one Idempotency-Key, one intent, twice at once: one capture")
    void oneKeyOneIntentTwiceAtOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            PaymentIntent intent = pendingFor(ALICE);
            String key = "twice-" + UUID.randomUUID();

            List<Integer> statuses = together(
                    confirm(intent.id(), key),
                    confirm(intent.id(), key));

            // The second either finds the key still RESERVED (409) or, if the
            // first finished in between, COMPLETED and is served its response
            // (200). Nothing else: isSubsetOf refuses a 500, which
            // containsAnyOf(409, 200) let through once one 200 was there.
            assertThat(statuses).as("round %d", round).contains(200).isSubsetOf(200, 409);
            assertThat(statusOf(intent)).isEqualTo(PaymentStatus.SUCCEEDED);
        }
        verify(gateway, times(ROUNDS)).charge(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a repeat while the first is still at the gateway: 409 in progress; afterwards the same response")
    void aRepeatDuringTheChargeWaitsForTheFirst() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);
        String key = "held-" + UUID.randomUUID();
        CountDownLatch atTheGateway = new CountDownLatch(1);
        CountDownLatch letItAnswer = new CountDownLatch(1);
        doAnswer(call -> {
            atTheGateway.countDown();
            assertThat(letItAnswer.await(30, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(gateway).charge(any(), any(), any(), any());

        Future<MvcResult> first = pool.submit(() -> mvc.perform(confirm(intent.id(), key)).andReturn());
        assertThat(atTheGateway.await(30, TimeUnit.SECONDS)).isTrue();

        mvc.perform(confirm(intent.id(), key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(ConfirmChargeFlowTest.inProgress()));
        mvc.perform(confirm(intent.id(), null))
                .andExpect(status().isConflict());

        letItAnswer.countDown();
        MvcResult firstResult = first.get(30, TimeUnit.SECONDS);
        assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);

        String replayed = mvc.perform(confirm(intent.id(), key))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replayed).isEqualTo(firstResult.getResponse().getContentAsString());

        verify(gateway, times(1)).charge(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------

    /** Sends both requests from two threads released at the same instant. */
    private List<Integer> together(MockHttpServletRequestBuilder a, MockHttpServletRequestBuilder b)
            throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        List<Future<Integer>> sent = new ArrayList<>();
        for (MockHttpServletRequestBuilder request : List.of(a, b)) {
            sent.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(request).andReturn().getResponse().getStatus();
            }));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> status : sent) {
            statuses.add(status.get(60, TimeUnit.SECONDS));
        }
        return statuses;
    }

    private PaymentIntent pendingFor(String owner) {
        return intents.saveAndFlush(new PaymentIntent(UUID.randomUUID(), owner, new BigDecimal("4500.00"), "ZMW"));
    }

    private PaymentStatus statusOf(PaymentIntent intent) {
        return intents.findById(intent.id()).orElseThrow().status();
    }

    private static MockHttpServletRequestBuilder confirm(UUID intentId, String key) {
        MockHttpServletRequestBuilder request = post("/api/v1/payments/intents/{id}/confirm", intentId)
                .with(as(ALICE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardNumber\":\"" + GOOD_CARD + "\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return request;
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject(subject));
    }
}
