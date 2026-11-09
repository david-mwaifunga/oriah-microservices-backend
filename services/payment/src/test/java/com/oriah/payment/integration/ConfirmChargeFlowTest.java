package com.oriah.payment.integration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.gateway.PaymentGateway;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;
import com.oriah.payment.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirm charges the card with no database transaction held across the
 * gateway call -- the fix for "a failure after the charge rolls back to
 * PENDING and the next confirm charges again".
 *
 * <pre>
 *   tx1   owner check; reserve the Idempotency-Key; PENDING -&gt; CHARGING with a
 *         new charge reference (version-checked); COMMIT
 *   ---   gateway.charge(reference, ...)   -- no transaction open
 *   tx2   CAPTURED -&gt; SUCCEEDED, DECLINED -&gt; FAILED, with the gateway's
 *         reference/reason and its time; append the event; complete the key
 * </pre>
 *
 * Once tx1 commits, the intent is CHARGING and nothing can charge it again.
 * If the gateway's answer never arrives (a timeout, an exception) or tx2
 * cannot record it, the intent simply stays CHARGING: the caller is told the
 * outcome is not known yet (503), every retry is refused (409), and the
 * reconciler (Phase 1 stage B) settles it by asking the gateway about the
 * reference.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ConfirmChargeFlowTest {

    private static final String ALICE = "kc-alice";
    private static final String GOOD_CARD = "4242424242424242";
    private static final String DECLINED_CARD = "4000000000000002";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;
    @Autowired
    private PaymentService service;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /** A spy: the real fake gateway decides, the test counts and can intercept the calls. */
    @MockitoSpyBean
    private PaymentGateway gateway;

    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc = new JdbcTemplate(dataSource);
        idempotencyKeys.deleteAll();
        intents.deleteAll();
    }

    // ------------------------------------------------------------------
    // The order of things
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the gateway is called with no transaction open, after CHARGING and its reference are committed")
    void theChargeHappensOutsideAnyTransaction() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);
        Map<String, Object> seenDuringTheCharge = Collections.synchronizedMap(new HashMap<>());
        doAnswer(call -> {
            seenDuringTheCharge.put("transactionOpen", TransactionSynchronizationManager.isActualTransactionActive());
            seenDuringTheCharge.put("reference", call.getArgument(0));
            // A connection of its own, outside anything this thread holds:
            // it sees only what has been committed.
            try (Connection other = dataSource.getConnection();
                 PreparedStatement read = other.prepareStatement(
                         "SELECT status, charge_reference FROM payment_intents WHERE id = ?")) {
                read.setObject(1, intent.id());
                try (ResultSet row = read.executeQuery()) {
                    row.next();
                    seenDuringTheCharge.put("committedStatus", row.getString("status"));
                    seenDuringTheCharge.put("committedReference", row.getObject("charge_reference", UUID.class));
                }
            }
            return call.callRealMethod();
        }).when(gateway).charge(any(), any(), any(), any());

        confirm(intent.id(), null, GOOD_CARD).andExpect(status().isOk());

        assertThat(seenDuringTheCharge)
                .containsEntry("transactionOpen", false)
                .containsEntry("committedStatus", "CHARGING")
                .containsEntry("committedReference", seenDuringTheCharge.get("reference"));
    }

    @Test
    @DisplayName("a capture settles SUCCEEDED with the gateway's own reference and decision time")
    void aCaptureRecordsTheGatewaysFacts() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);

        confirm(intent.id(), null, GOOD_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        PaymentIntent after = intents.findById(intent.id()).orElseThrow();
        Map<String, Object> decided = gatewayRow(after.chargeReference());
        assertThat(after.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(decided.get("outcome")).isEqualTo("CAPTURED");
        assertThat(after.gatewayReference()).isEqualTo(decided.get("gateway_reference"));
        // The gateway's time, read back from its own record -- not the moment
        // payment wrote the row.
        assertThat(after.confirmedAt()).isEqualTo(decided.get("decided_at"));
    }

    @Test
    @DisplayName("a decline settles FAILED with the gateway's reason and decision time")
    void aDeclineRecordsTheGatewaysFacts() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);

        confirm(intent.id(), null, DECLINED_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("card declined"));

        PaymentIntent after = intents.findById(intent.id()).orElseThrow();
        Map<String, Object> decided = gatewayRow(after.chargeReference());
        assertThat(after.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(after.failureReason()).isEqualTo("card declined");
        assertThat(after.confirmedAt()).isEqualTo(decided.get("decided_at"));
    }

    @Test
    @DisplayName("confirm refuses to run inside a caller's transaction, and charges nothing")
    void confirmRefusesAnOpenTransaction() {
        PaymentIntent intent = pendingFor(ALICE);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.confirm(intent.id(), ALICE, GOOD_CARD, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a transaction");

        verify(gateway, never()).charge(any(), any(), any(), any());
        assertThat(intents.findById(intent.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    }

    // ------------------------------------------------------------------
    // When the answer is not known
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a gateway timeout is not a decline: 503, still CHARGING, no event, and every retry 409")
    void aGatewayTimeoutLeavesTheIntentCharging() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);
        String key = "timeout-" + UUID.randomUUID();
        doThrow(new RuntimeException("gateway read timed out")).when(gateway).charge(any(), any(), any(), any());

        confirm(intent.id(), key, GOOD_CARD)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(outcomeUnknown(intent.id())));

        PaymentIntent after = intents.findById(intent.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(PaymentStatus.CHARGING);
        assertThat(after.chargeReference()).isNotNull();
        assertThat(eventsFor(intent.id())).isZero();
        assertThat(keyState(key)).isEqualTo("RESERVED");

        // The same key: the first request's outcome is still being settled.
        confirm(intent.id(), key, GOOD_CARD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(inProgress()));
        // No key at all: the intent is being charged, so it is not charged again.
        confirm(intent.id(), null, GOOD_CARD).andExpect(status().isConflict());

        verify(gateway, times(1)).charge(any(), any(), any(), any());
    }

    @Test
    @DisplayName("THE TRAP: recording fails after a capture -> CHARGING, one capture, retries 409, still one capture")
    void aFailedRecordingNeverLeadsToASecondCharge() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);
        String key = "trap-" + UUID.randomUUID();
        refuseToSettle(intent.id());
        try {
            confirm(intent.id(), key, GOOD_CARD)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value(outcomeUnknown(intent.id())));

            PaymentIntent after = intents.findById(intent.id()).orElseThrow();
            assertThat(after.status())
                    .as("tx2 rolled back, so the intent stays where tx1 committed it")
                    .isEqualTo(PaymentStatus.CHARGING);
            assertThat(gatewayRow(after.chargeReference()).get("outcome"))
                    .as("the money WAS taken, once")
                    .isEqualTo("CAPTURED");
            assertThat(eventsFor(intent.id())).isZero();

            confirm(intent.id(), key, GOOD_CARD).andExpect(status().isConflict());
            confirm(intent.id(), null, GOOD_CARD).andExpect(status().isConflict());
            confirm(intent.id(), "another-" + UUID.randomUUID(), GOOD_CARD).andExpect(status().isConflict());

            verify(gateway, times(1)).charge(any(), any(), any(), any());
            assertThat(capturedRowsFor(intent.id())).isEqualTo(1);
        } finally {
            allowSettling();
        }
    }

    @Test
    @DisplayName("an Idempotency-Key longer than the column is refused before anything is charged")
    void anOverlongKeyIsRefusedBeforeTheCharge() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);

        confirm(intent.id(), "k".repeat(129), GOOD_CARD).andExpect(status().isBadRequest());

        verify(gateway, never()).charge(any(), any(), any(), any());
        assertThat(intents.findById(intent.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    }

    // ------------------------------------------------------------------

    static String outcomeUnknown(UUID intentId) {
        return "The outcome of the charge for payment intent " + intentId + " is not known yet. "
                + "It will not be charged again; its status will be settled shortly.";
    }

    static String inProgress() {
        return "A confirmation with this Idempotency-Key is still in progress for this payment intent. "
                + "Repeat the request once it has finished to receive its result.";
    }

    private PaymentIntent pendingFor(String owner) {
        return intents.saveAndFlush(new PaymentIntent(UUID.randomUUID(), owner, new BigDecimal("4500.00"), "ZMW"));
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

    private long eventsFor(UUID intentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_type = 'payment_intent' AND aggregate_id = ?",
                Long.class, intentId.toString());
    }

    private String keyState(String key) {
        return jdbc.queryForObject("SELECT state FROM idempotency_keys WHERE key = ?", String.class, key);
    }

    /**
     * Captures the gateway recorded for the intent's charge reference. With
     * the spy's count of charge calls, it proves the money was taken once.
     */
    private long capturedRowsFor(UUID intentId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM fake_gateway_charge g
                JOIN payment_intents p ON p.charge_reference = g.charge_reference
                WHERE p.id = ? AND g.outcome = 'CAPTURED'""", Long.class, intentId);
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
}
