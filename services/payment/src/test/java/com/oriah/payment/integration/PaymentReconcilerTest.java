package com.oriah.payment.integration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

import com.oriah.payment.MovableClock;
import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.config.ReconcilerSchedule;
import com.oriah.payment.gateway.FakePaymentGateway;
import com.oriah.payment.gateway.PaymentGateway;
import com.oriah.payment.gateway.PaymentGateway.Unknown;
import com.oriah.payment.gateway.PaymentGateway.Voided;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;
import com.oriah.payment.service.PaymentReconciler;
import com.oriah.payment.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reconciler: how an intent left CHARGING -- its answer lost to a
 * timeout, a crash, or a failure to record it -- is settled, by asking the
 * gateway about its charge reference.
 *
 * <pre>
 *   CHARGING for longer than the age -&gt; lookup(reference)
 *     CAPTURED  -&gt; SUCCEEDED (+ payment.succeeded)
 *     DECLINED  -&gt; FAILED    (+ payment.failed)
 *     NOT_FOUND -&gt; voidUnsettled(reference)
 *                    VOIDED   -&gt; FAILED "charge not completed" (+ payment.failed, at the void's time)
 *                    CAPTURED -&gt; SUCCEEDED (+ payment.succeeded)
 *                    UNKNOWN  -&gt; stays CHARGING, ERROR for staff
 *     UNKNOWN   -&gt; stays CHARGING, ERROR for staff
 * </pre>
 *
 * In the same transaction as the outcome, the intent's RESERVED
 * Idempotency-Key is completed with the response confirm would have given,
 * so the shopper's retry is served the real answer. The event is written by
 * {@code system:payment-reconciler} and carries the gateway's decision time,
 * however late the reconciler learns it.
 *
 * <p>The clock is payment's own {@code Clock} bean, replaced here by one the
 * test moves: the gateway's decision times, the intents' audit times and the
 * reconciler's "how long has it been CHARGING" all read it. The outbox's
 * recorded_at is the database's own now(), which the test cannot move -- so
 * "days later" sets the clock back three days for the charge and returns it
 * to the present for the reconciliation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false", "oriah.payment.reconciler.charging-age=2m"})
@ExtendWith(OutputCaptureExtension.class)
class PaymentReconcilerTest {

    private static final String ALICE = "kc-alice";
    private static final String GOOD_CARD = "4242424242424242";
    private static final String DECLINED_CARD = "4000000000000002";
    private static final BigDecimal AMOUNT = new BigDecimal("4500.00");

    /** One second past the age configured above: old enough to be reconciled. */
    private static final Duration PAST_THE_AGE = Duration.ofMinutes(2).plusSeconds(1);

    private static final MovableClock CLOCK = new MovableClock(Instant.now());

    @TestBean(methodName = "movableClock")
    @SuppressWarnings("unused")
    private Clock clock;

    static Clock movableClock() {
        return CLOCK;
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;
    @Autowired
    private PaymentService service;
    @Autowired
    private PaymentReconciler reconciler;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationContext context;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /** A spy: the real fake gateway decides; a test can lose its answers or make it unsure. */
    @MockitoSpyBean
    private PaymentGateway gateway;

    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc = new JdbcTemplate(dataSource);
        CLOCK.set(Instant.now());
        idempotencyKeys.deleteAll();
        intents.deleteAll();
    }

    @Test
    @DisplayName("the schedule is off in tests: only a test's own calls run the reconciler")
    void theScheduleIsOffInTests() {
        // Every assertion below on a CHARGING intent relies on this: a
        // scheduled pass could otherwise settle it mid-test.
        assertThat(context.getBeanProvider(ReconcilerSchedule.class).getIfAvailable()).isNull();
    }

    // ------------------------------------------------------------------
    // The trap, and the other ways an answer is lost
    // ------------------------------------------------------------------

    @Test
    @DisplayName("THE TRAP, settled: recording fails after a capture; retries 409; the reconciler settles "
            + "SUCCEEDED with one event; the key then replays the settled response, charging nothing")
    void theTrapIsSettledOnceAndTheKeyReplaysIt() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);
        String key = "trap-" + UUID.randomUUID();
        refuseToSettle(intent.id());
        try {
            confirm(intent.id(), key, GOOD_CARD).andExpect(status().isServiceUnavailable());
        } finally {
            allowSettling();
        }
        UUID reference = chargeReferenceOf(intent.id());
        assertThat(statusOf(intent.id())).isEqualTo(PaymentStatus.CHARGING);
        assertThat(capturedRowsFor(intent.id())).as("the money WAS taken, once").isEqualTo(1);

        confirm(intent.id(), key, GOOD_CARD).andExpect(status().isConflict());
        confirm(intent.id(), null, GOOD_CARD).andExpect(status().isConflict());
        verify(gateway, times(1)).charge(any(), any(), any(), any());

        CLOCK.advance(PAST_THE_AGE);
        reconciler.reconcile();

        PaymentIntent settled = intents.findById(intent.id()).orElseThrow();
        Map<String, Object> decided = gatewayRow(reference);
        assertThat(settled.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(settled.gatewayReference()).isEqualTo(decided.get("gateway_reference"));
        assertThat(settled.confirmedAt()).isEqualTo(decided.get("decided_at"));
        List<Map<String, Object>> events = eventsFor(intent.id());
        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .containsEntry("event_type", "payment.succeeded")
                .containsEntry("actor", PaymentReconciler.ACTOR)
                .containsEntry("occurred_at", decided.get("decided_at"));
        assertThat(keyState(key)).isEqualTo("COMPLETED");

        // The shopper's retry with the same key now gets the real answer: the
        // body confirm would have returned, which is the intent as it stands.
        String replayed = confirm(intent.id(), key, GOOD_CARD)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String asItStands = mvc.perform(get("/api/v1/payments/intents/{id}", intent.id()).with(as(ALICE)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replayed).isEqualTo(asItStands).contains("\"status\":\"SUCCEEDED\"");

        verify(gateway, times(1)).charge(any(), any(), any(), any());
        assertThat(capturedRowsFor(intent.id())).isEqualTo(1);

        // A later pass finds nothing left to do.
        reconciler.reconcile();
        assertThat(eventsFor(intent.id())).hasSize(1);
    }

    @Test
    @DisplayName("a gateway timeout after the capture: 503 and CHARGING, then the reconciler finds the capture "
            + "-> SUCCEEDED, without voiding")
    void aLostAnswerToACaptureIsSettledSucceeded() throws Exception {
        loseTheAnswerAfterTheCharge();
        PaymentIntent intent = pendingFor(ALICE);

        confirm(intent.id(), null, GOOD_CARD).andExpect(status().isServiceUnavailable());
        UUID reference = chargeReferenceOf(intent.id());
        assertThat(statusOf(intent.id())).isEqualTo(PaymentStatus.CHARGING);
        assertThat(eventsFor(intent.id())).isEmpty();

        CLOCK.advance(PAST_THE_AGE);
        reconciler.reconcile();

        PaymentIntent settled = intents.findById(intent.id()).orElseThrow();
        Map<String, Object> decided = gatewayRow(reference);
        assertThat(settled.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(settled.gatewayReference()).isEqualTo(decided.get("gateway_reference"));
        assertThat(settled.confirmedAt()).isEqualTo(decided.get("decided_at"));
        assertThat(eventsFor(intent.id())).singleElement()
                .satisfies(event -> assertThat(event)
                        .containsEntry("event_type", "payment.succeeded")
                        .containsEntry("actor", PaymentReconciler.ACTOR)
                        .containsEntry("occurred_at", decided.get("decided_at")));
        verify(gateway, never()).voidUnsettled(any());
        verify(gateway, times(1)).charge(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a lost answer to a decline is settled FAILED with the gateway's reason")
    void aLostAnswerToADeclineIsSettledFailed() throws Exception {
        loseTheAnswerAfterTheCharge();
        PaymentIntent intent = pendingFor(ALICE);
        confirm(intent.id(), null, DECLINED_CARD).andExpect(status().isServiceUnavailable());
        UUID reference = chargeReferenceOf(intent.id());

        CLOCK.advance(PAST_THE_AGE);
        reconciler.reconcile();

        PaymentIntent settled = intents.findById(intent.id()).orElseThrow();
        Map<String, Object> decided = gatewayRow(reference);
        assertThat(settled.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(settled.failureReason()).isEqualTo("card declined");
        assertThat(settled.confirmedAt()).isEqualTo(decided.get("decided_at"));
        assertThat(eventsFor(intent.id())).singleElement()
                .satisfies(event -> assertThat(event)
                        .containsEntry("event_type", "payment.failed")
                        .containsEntry("actor", PaymentReconciler.ACTOR)
                        .containsEntry("occurred_at", decided.get("decided_at")));
    }

    @Test
    @DisplayName("NOT_FOUND: a charge the gateway never saw is voided -> FAILED \"charge not completed\" at the "
            + "void's time; the lost request can never capture afterwards")
    void aChargeTheGatewayNeverSawIsVoidedAndFailed() throws Exception {
        neverReachTheGateway();
        PaymentIntent intent = pendingFor(ALICE);
        String key = "lost-" + UUID.randomUUID();
        confirm(intent.id(), key, GOOD_CARD).andExpect(status().isServiceUnavailable());
        UUID reference = chargeReferenceOf(intent.id());

        CLOCK.advance(PAST_THE_AGE);
        reconciler.reconcile();

        PaymentIntent settled = intents.findById(intent.id()).orElseThrow();
        Map<String, Object> voided = gatewayRow(reference);
        assertThat(voided.get("outcome")).isEqualTo("VOIDED");
        assertThat(voided.get("decided_at")).isEqualTo(CLOCK.instant());
        assertThat(settled.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(settled.failureReason()).isEqualTo("charge not completed");
        assertThat(settled.confirmedAt()).isEqualTo(voided.get("decided_at"));
        assertThat(eventsFor(intent.id())).singleElement()
                .satisfies(event -> {
                    assertThat(event)
                            .containsEntry("event_type", "payment.failed")
                            .containsEntry("actor", PaymentReconciler.ACTOR)
                            .containsEntry("occurred_at", voided.get("decided_at"));
                    assertThat((String) event.get("payload")).contains("\"reason\":\"charge not completed\"");
                });
        assertThat(keyState(key)).isEqualTo("COMPLETED");

        // The request that went astray arrives at the gateway at last: the
        // reference is closed, so it takes nothing.
        PaymentGateway sameGateway = new FakePaymentGateway(dataSource, transactionManager, CLOCK);
        assertThat(sameGateway.charge(reference, GOOD_CARD, AMOUNT, "ZMW")).isInstanceOf(Voided.class);
        assertThat(capturedRowsFor(intent.id())).isZero();
    }

    @Test
    @DisplayName("a void that finds the charge already captured settles SUCCEEDED with the capture's facts")
    void aVoidThatFindsACaptureSettlesSucceeded() throws Exception {
        neverReachTheGateway();
        PaymentIntent intent = pendingFor(ALICE);
        confirm(intent.id(), null, GOOD_CARD).andExpect(status().isServiceUnavailable());
        UUID reference = chargeReferenceOf(intent.id());
        // Between the reconciler's lookup (nothing there) and its void, the
        // delayed charge lands at the gateway and is captured. The void then
        // finds the capture and answers with it: a void never undoes one.
        PaymentGateway sameGateway = new FakePaymentGateway(dataSource, transactionManager, CLOCK);
        doAnswer(call -> {
            sameGateway.charge(reference, GOOD_CARD, AMOUNT, "ZMW");
            return call.callRealMethod();
        }).when(gateway).voidUnsettled(reference);

        CLOCK.advance(PAST_THE_AGE);
        reconciler.reconcile();

        PaymentIntent settled = intents.findById(intent.id()).orElseThrow();
        Map<String, Object> decided = gatewayRow(reference);
        assertThat(decided.get("outcome")).isEqualTo("CAPTURED");
        assertThat(settled.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(settled.gatewayReference()).isEqualTo(decided.get("gateway_reference"));
        assertThat(settled.confirmedAt()).isEqualTo(decided.get("decided_at"));
        assertThat(eventsFor(intent.id())).singleElement()
                .satisfies(event -> assertThat(event)
                        .containsEntry("event_type", "payment.succeeded")
                        .containsEntry("occurred_at", decided.get("decided_at")));
        verify(gateway, times(1)).voidUnsettled(reference);
    }

    @Test
    @DisplayName("lookup UNKNOWN: stays CHARGING, nothing voided, no event, the key still reserved, an ERROR for staff")
    void anUnknownLookupLeavesTheIntentCharging(CapturedOutput output) throws Exception {
        loseTheAnswerAfterTheCharge();
        PaymentIntent intent = pendingFor(ALICE);
        String key = "unknown-" + UUID.randomUUID();
        confirm(intent.id(), key, GOOD_CARD).andExpect(status().isServiceUnavailable());
        doReturn(new Unknown("status API unreachable")).when(gateway).lookup(any());

        CLOCK.advance(PAST_THE_AGE);
        reconciler.reconcile();

        assertThat(statusOf(intent.id())).isEqualTo(PaymentStatus.CHARGING);
        assertThat(eventsFor(intent.id())).isEmpty();
        assertThat(keyState(key)).isEqualTo("RESERVED");
        verify(gateway, never()).voidUnsettled(any());
        assertThat(errorLinesAbout(output, intent.id()))
                .as("staff are told, at ERROR, which intent is stuck and why")
                .anySatisfy(line -> assertThat(line).contains("status API unreachable"));
    }

    @Test
    @DisplayName("NOT_FOUND, then the void UNKNOWN: stays CHARGING, no event, an ERROR for staff")
    void anUnknownVoidLeavesTheIntentCharging(CapturedOutput output) throws Exception {
        neverReachTheGateway();
        PaymentIntent intent = pendingFor(ALICE);
        confirm(intent.id(), null, GOOD_CARD).andExpect(status().isServiceUnavailable());
        doReturn(new Unknown("void timed out")).when(gateway).voidUnsettled(any());

        CLOCK.advance(PAST_THE_AGE);
        reconciler.reconcile();

        assertThat(statusOf(intent.id())).isEqualTo(PaymentStatus.CHARGING);
        assertThat(eventsFor(intent.id())).isEmpty();
        assertThat(errorLinesAbout(output, intent.id()))
                .anySatisfy(line -> assertThat(line).contains("void timed out"));
    }

    @Test
    @DisplayName("a crash between the charge and tx2 is the trap again: CHARGING, one capture, 409s, "
            + "then settled SUCCEEDED once and the key replays it")
    void aCrashBetweenTheChargeAndItsRecordingIsSettledLikeTheTrap() throws Exception {
        // The gateway captures, then the process dies before tx2: an Error,
        // which confirm does not catch, so nothing after the charge runs --
        // no tx2, no 503, the key left RESERVED.
        doAnswer(call -> {
            call.callRealMethod();
            throw new SimulatedCrash();
        }).when(gateway).charge(any(), any(), any(), any());
        PaymentIntent intent = pendingFor(ALICE);
        String key = "crash-" + UUID.randomUUID();

        assertThatThrownBy(() -> service.confirm(intent.id(), ALICE, GOOD_CARD, key))
                .isInstanceOf(SimulatedCrash.class);

        assertThat(statusOf(intent.id())).isEqualTo(PaymentStatus.CHARGING);
        assertThat(capturedRowsFor(intent.id())).isEqualTo(1);
        assertThat(keyState(key)).isEqualTo("RESERVED");
        assertThat(eventsFor(intent.id())).isEmpty();
        confirm(intent.id(), key, GOOD_CARD).andExpect(status().isConflict());
        confirm(intent.id(), null, GOOD_CARD).andExpect(status().isConflict());

        CLOCK.advance(PAST_THE_AGE);
        reconciler.reconcile();

        assertThat(statusOf(intent.id())).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(eventsFor(intent.id())).singleElement()
                .satisfies(event -> assertThat(event).containsEntry("event_type", "payment.succeeded"));
        confirm(intent.id(), key, GOOD_CARD).andExpect(status().isOk());
        verify(gateway, times(1)).charge(any(), any(), any(), any());
        assertThat(capturedRowsFor(intent.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Time
    // ------------------------------------------------------------------

    @Test
    @DisplayName("settled days later: occurred_at and confirmed_at stay the capture's time; recorded_at is now")
    void aReconciliationDaysLaterKeepsTheCaptureTime() throws Exception {
        CLOCK.set(Instant.now().minus(Duration.ofDays(3)));
        loseTheAnswerAfterTheCharge();
        PaymentIntent intent = pendingFor(ALICE);
        confirm(intent.id(), null, GOOD_CARD).andExpect(status().isServiceUnavailable());
        Instant capturedAt = (Instant) gatewayRow(chargeReferenceOf(intent.id())).get("decided_at");
        assertThat(capturedAt).isEqualTo(CLOCK.instant());

        // Three days on, the reconciler runs.
        CLOCK.set(Instant.now());
        reconciler.reconcile();

        PaymentIntent settled = intents.findById(intent.id()).orElseThrow();
        assertThat(settled.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(settled.confirmedAt()).isEqualTo(capturedAt);
        Map<String, Object> event = eventsFor(intent.id()).get(0);
        assertThat(event.get("occurred_at")).isEqualTo(capturedAt);
        assertThat((Instant) event.get("recorded_at"))
                .as("recorded_at is when the row was written: now, not the capture")
                .isBetween(Instant.now().minus(Duration.ofMinutes(1)), Instant.now().plusSeconds(5));
        assertThat((String) event.get("payload")).contains("\"confirmedAt\":\"" + capturedAt + "\"");
    }

    @Test
    @DisplayName("an intent CHARGING for less than the age is left alone: its confirm may still be at the gateway")
    void anIntentYoungerThanTheAgeIsLeftAlone() throws Exception {
        loseTheAnswerAfterTheCharge();
        PaymentIntent intent = pendingFor(ALICE);
        confirm(intent.id(), null, GOOD_CARD).andExpect(status().isServiceUnavailable());

        CLOCK.advance(PAST_THE_AGE.minusSeconds(2));
        reconciler.reconcile();

        assertThat(statusOf(intent.id())).isEqualTo(PaymentStatus.CHARGING);
        verify(gateway, never()).lookup(any());
        verify(gateway, never()).voidUnsettled(any());

        CLOCK.advance(Duration.ofSeconds(2));
        reconciler.reconcile();

        assertThat(statusOf(intent.id())).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("a CHARGING intent with no recorded time (written by hand) is reconciled, not stranded")
    void aChargingIntentWithNoTimeIsReconciled() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_intents (id, order_id, keycloak_id, amount, currency, status, charge_reference,
                                             version, is_deleted)
                VALUES (?, ?, 'kc-by-hand', 100.00, 'ZMW', 'CHARGING', ?, 0, false)""",
                id, UUID.randomUUID(), UUID.randomUUID());

        reconciler.reconcile();

        assertThat(statusOf(id)).isEqualTo(PaymentStatus.FAILED);
        assertThat(eventsFor(id)).singleElement()
                .satisfies(event -> assertThat(event).containsEntry("event_type", "payment.failed"));
    }

    // ------------------------------------------------------------------
    // More than one at a time
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two reconciler runs at once, both past the gateway: settled once, one event")
    void twoRunsAtOnceSettleOnce() throws Exception {
        loseTheAnswerAfterTheCharge();
        PaymentIntent intent = pendingFor(ALICE);
        confirm(intent.id(), null, GOOD_CARD).andExpect(status().isServiceUnavailable());
        // Neither run goes on to record until both have asked the gateway,
        // so both try to settle the same intent at the same moment.
        CyclicBarrier bothAsked = new CyclicBarrier(2);
        doAnswer(call -> {
            Object answer = call.callRealMethod();
            bothAsked.await(10, TimeUnit.SECONDS);
            return answer;
        }).when(gateway).lookup(any());
        CLOCK.advance(PAST_THE_AGE);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(reconciler::reconcile);
            Future<?> second = pool.submit(reconciler::reconcile);
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        verify(gateway, times(2)).lookup(any());
        assertThat(statusOf(intent.id())).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(eventsFor(intent.id())).hasSize(1);
    }

    @Test
    @DisplayName("confirm's tx2 loses the race to the reconciler: 200 with the settled intent, not 503 "
            + "'outcome unknown'; one event, one charge, and the key replays the same answer")
    void aConfirmThatLosesTheRaceToTheReconcilerAnswersWithTheSettledIntent() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);
        String key = "race-" + UUID.randomUUID();
        // The gateway captures, then its answer is slow to reach confirm --
        // slower than the charging age, so the reconciler asks meanwhile and
        // learns the same capture.
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch answerArrives = new CountDownLatch(1);
        doAnswer(call -> {
            Object answer = call.callRealMethod();
            captured.countDown();
            assertThat(answerArrives.await(30, TimeUnit.SECONDS)).isTrue();
            return answer;
        }).when(gateway).charge(any(), any(), any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection gate = dataSource.getConnection();
        try {
            holdSettlingAtTheGate(intent.id());
            closeTheGate(gate);

            Future<MvcResult> confirming = pool.submit(() -> confirm(intent.id(), key, GOOD_CARD).andReturn());
            assertThat(captured.await(30, TimeUnit.SECONDS)).isTrue();

            // 1. The reconciler records the capture. Its update of the intent
            //    is made but not committed: it waits at the gate, holding the row.
            CLOCK.advance(PAST_THE_AGE);
            Future<?> reconciling = pool.submit(reconciler::reconcile);
            awaitASessionWaitingOn("advisory");

            // 2. Confirm's answer arrives. tx2 reads the intent -- still
            //    CHARGING, because the reconciler has not committed -- and its
            //    update queues behind the reconciler's.
            answerArrives.countDown();
            awaitASessionWaitingOn("transactionid");

            // 3. The reconciler commits first, so confirm's version check fails.
            openTheGate(gate);
            reconciling.get(30, TimeUnit.SECONDS);
            MvcResult result = confirming.get(30, TimeUnit.SECONDS);

            String answered = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus())
                    .as("the intent is settled, so the answer is not 'outcome unknown': %s", answered)
                    .isEqualTo(200);
            String asItStands = mvc.perform(get("/api/v1/payments/intents/{id}", intent.id()).with(as(ALICE)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(answered).isEqualTo(asItStands).contains("\"status\":\"SUCCEEDED\"");

            // Recorded once, by the reconciler; confirm wrote nothing of its own.
            assertThat(eventsFor(intent.id())).singleElement()
                    .satisfies(event -> assertThat(event)
                            .containsEntry("event_type", "payment.succeeded")
                            .containsEntry("actor", PaymentReconciler.ACTOR));
            assertThat(keyState(key)).isEqualTo("COMPLETED");
            String replayed = confirm(intent.id(), key, GOOD_CARD)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(replayed).as("a repeat with the key is told the same thing").isEqualTo(answered);

            verify(gateway, times(1)).charge(any(), any(), any(), any());
            assertThat(capturedRowsFor(intent.id())).isEqualTo(1);
        } finally {
            // Whatever failed above: open the gate before its connection goes
            // back to the pool (a session lock outlives the borrow), let
            // confirm go on, then remove the trigger once both have finished.
            openTheGate(gate);
            gate.close();
            answerArrives.countDown();
            pool.shutdownNow();
            stopHoldingSettling();
        }
    }

    @Test
    @DisplayName("a gateway error on one intent does not stop the others being settled")
    void oneFailureDoesNotStopTheRest(CapturedOutput output) throws Exception {
        loseTheAnswerAfterTheCharge();
        PaymentIntent troubled = pendingFor(ALICE);
        confirm(troubled.id(), null, GOOD_CARD).andExpect(status().isServiceUnavailable());
        PaymentIntent fine = pendingFor(ALICE);
        confirm(fine.id(), null, GOOD_CARD).andExpect(status().isServiceUnavailable());
        UUID troubledReference = chargeReferenceOf(troubled.id());
        doThrow(new RuntimeException("gateway answered 500")).when(gateway).lookup(eq(troubledReference));

        CLOCK.advance(PAST_THE_AGE);
        reconciler.reconcile();

        assertThat(statusOf(troubled.id())).isEqualTo(PaymentStatus.CHARGING);
        assertThat(statusOf(fine.id())).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(errorLinesAbout(output, troubled.id())).isNotEmpty();
    }

    // ------------------------------------------------------------------

    /** The process dying mid-request: an Error, which nothing on the way out catches. */
    private static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("test: the process died between the charge and recording it");
        }
    }

    /** The gateway takes the money, then its answer is lost on the way back. */
    private void loseTheAnswerAfterTheCharge() {
        doAnswer(call -> {
            call.callRealMethod();
            throw new RuntimeException("test: gateway read timed out");
        }).when(gateway).charge(any(), any(), any(), any());
    }

    /** The request never reaches the gateway. */
    private void neverReachTheGateway() {
        doThrow(new RuntimeException("test: connect timed out")).when(gateway).charge(any(), any(), any(), any());
    }

    private PaymentIntent pendingFor(String owner) {
        return intents.saveAndFlush(new PaymentIntent(UUID.randomUUID(), owner, AMOUNT, "ZMW"));
    }

    private ResultActions confirm(UUID intentId, String key, String card) throws Exception {
        var request = post("/api/v1/payments/intents/{id}/confirm", intentId)
                .with(as(ALICE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardNumber\":\"" + card + "\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject(subject));
    }

    private PaymentStatus statusOf(UUID intentId) {
        return PaymentStatus.valueOf(
                jdbc.queryForObject("SELECT status FROM payment_intents WHERE id = ?", String.class, intentId));
    }

    private UUID chargeReferenceOf(UUID intentId) {
        return jdbc.queryForObject("SELECT charge_reference FROM payment_intents WHERE id = ?", UUID.class, intentId);
    }

    /** The gateway's record of a charge; decided_at as an Instant. */
    private Map<String, Object> gatewayRow(UUID chargeReference) {
        return jdbc.queryForObject("""
                SELECT outcome, gateway_reference, decided_at FROM fake_gateway_charge WHERE charge_reference = ?""",
                (rs, n) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("outcome", rs.getString("outcome"));
                    row.put("gateway_reference", rs.getString("gateway_reference"));
                    row.put("decided_at", rs.getObject("decided_at", OffsetDateTime.class).toInstant());
                    return row;
                }, chargeReference);
    }

    /** The outbox rows for one intent, oldest first; times as Instants, the payload as its stored text. */
    private List<Map<String, Object>> eventsFor(UUID intentId) {
        return jdbc.query("""
                SELECT event_type, actor, occurred_at, recorded_at, payload::text AS payload FROM outbox
                WHERE aggregate_type = 'payment_intent' AND aggregate_id = ? ORDER BY outbox_id""",
                (rs, n) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("event_type", rs.getString("event_type"));
                    row.put("actor", rs.getString("actor"));
                    row.put("occurred_at", rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
                    row.put("recorded_at", rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
                    row.put("payload", rs.getString("payload"));
                    return row;
                }, intentId.toString());
    }

    private String keyState(String key) {
        return jdbc.queryForObject("SELECT state FROM idempotency_keys WHERE key = ?", String.class, key);
    }

    /** Captures the gateway recorded for the intent's charge reference. */
    private long capturedRowsFor(UUID intentId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM fake_gateway_charge g
                JOIN payment_intents p ON p.charge_reference = g.charge_reference
                WHERE p.id = ? AND g.outcome = 'CAPTURED'""", Long.class, intentId);
    }

    private static List<String> errorLinesAbout(CapturedOutput output, UUID intentId) {
        return output.getAll().lines()
                .filter(line -> line.contains("ERROR") && line.contains(intentId.toString()))
                .toList();
    }

    /**
     * Makes tx2 fail for this one intent, the way a database outage or a bug
     * would: any update that settles it is refused, so tx2 rolls back after
     * the gateway has already captured.
     */
    private void refuseToSettle(UUID intentId) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_refuse_settling() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id = '%s' AND NEW.status IN ('SUCCEEDED', 'FAILED') THEN
                        RAISE EXCEPTION 'test: recording the outcome failed';
                    END IF;
                    RETURN NEW;
                END
                $$""".formatted(intentId));
        jdbc.execute("""
                CREATE TRIGGER test_refuse_settling BEFORE UPDATE ON payment_intents
                FOR EACH ROW EXECUTE FUNCTION test_refuse_settling()""");
    }

    private void allowSettling() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_refuse_settling ON payment_intents");
        jdbc.execute("DROP FUNCTION IF EXISTS test_refuse_settling()");
    }

    /**
     * The gate: an advisory lock the test holds on its own connection. The
     * two-number form, so it can never be the outbox's lock, which is one
     * number.
     */
    private static final int GATE = 4711;

    /**
     * Makes whoever settles this intent wait at the gate just after its
     * update -- the row changed and locked, nothing committed -- until the
     * test opens it.
     */
    private void holdSettlingAtTheGate(UUID intentId) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_hold_settling() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id = '%s' AND NEW.status IN ('SUCCEEDED', 'FAILED') THEN
                        PERFORM pg_advisory_xact_lock(%d, %d);
                    END IF;
                    RETURN NEW;
                END
                $$""".formatted(intentId, GATE, GATE));
        jdbc.execute("""
                CREATE TRIGGER test_hold_settling AFTER UPDATE ON payment_intents
                FOR EACH ROW EXECUTE FUNCTION test_hold_settling()""");
    }

    private void stopHoldingSettling() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_hold_settling ON payment_intents");
        jdbc.execute("DROP FUNCTION IF EXISTS test_hold_settling()");
    }

    private static void closeTheGate(Connection gate) throws SQLException {
        try (Statement statement = gate.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + GATE + ", " + GATE + ")");
        }
    }

    /** Releases every advisory lock of the gate's session; it goes back to the pool clean. */
    private static void openTheGate(Connection gate) throws SQLException {
        try (Statement statement = gate.createStatement()) {
            statement.execute("SELECT pg_advisory_unlock_all()");
        }
    }

    /** Waits until some session of this database is blocked on a lock of this kind (pg_stat_activity). */
    private void awaitASessionWaitingOn(String lockKind) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock' AND wait_event = ?""",
                    Long.class, lockKind);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no session began waiting on a " + lockKind + " lock within 15 s");
    }
}
