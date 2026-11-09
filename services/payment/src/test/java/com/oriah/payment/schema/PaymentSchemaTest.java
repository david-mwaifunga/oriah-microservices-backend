package com.oriah.payment.schema;

import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.events.InboxSchemaGuard;
import com.oriah.events.OutboxSchemaGuard;
import com.oriah.payment.TestcontainersConfiguration;

import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment's V5, read straight off the migrated database: what the tables
 * themselves refuse, whatever the Java code above them does.
 *
 * <p>Each rule here is a backstop for one way money goes wrong. The Java
 * code checks the same things first and answers with a sentence; these make
 * sure that a bug in that code, or a second instance racing the first, still
 * cannot write the bad row.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class PaymentSchemaTest {

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------
    // The event log, copied from shared/oriah-events
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the outbox and its relay cursor are exactly the agreed shape")
    void outboxMatchesTheReferenceSql() {
        assertThat(OutboxSchemaGuard.problems(dataSource)).isEmpty();
    }

    @Test
    @DisplayName("processed_event (replay-request dedupe) is exactly the agreed shape")
    void processedEventMatchesTheReferenceSql() {
        assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT)).isEmpty();
    }

    // ------------------------------------------------------------------
    // payment_intents
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an order can have only one intent that is pending, being charged or paid")
    void oneLiveIntentPerOrder() {
        for (String live : new String[] {"PENDING", "CHARGING", "SUCCEEDED"}) {
            UUID order = UUID.randomUUID();
            insertIntent(order, "PENDING");
            assertThatThrownBy(() -> insertIntent(order, live))
                    .as("a second %s intent next to a PENDING one", live)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_payment_intents_one_live_per_order");
        }
    }

    @Test
    @DisplayName("a failed intent leaves room for a new one, so a shopper can try again")
    void aFailedIntentAllowsAReplacement() {
        UUID order = UUID.randomUUID();
        insertIntent(order, "FAILED");
        insertIntent(order, "FAILED");
        insertIntent(order, "PENDING");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_intents WHERE order_id = ?", Long.class, order))
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("an intent being charged always names the charge it is waiting on")
    void chargingNeedsAChargeReference() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_intents (id, order_id, keycloak_id, amount, currency, status)
                VALUES (?, ?, 'kc-schema', 100.00, 'ZMW', 'CHARGING')""", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_payment_intents_charging_has_reference");
    }

    @Test
    @DisplayName("one charge reference belongs to one intent: one capture can never settle two")
    void aChargeReferenceIsUnique() {
        UUID reference = UUID.randomUUID();
        insertCharging(reference);
        assertThatThrownBy(() -> insertCharging(reference))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_payment_intents_charge_reference");
    }

    @Test
    @DisplayName("status is one of the known states and nothing else")
    void statusIsAClosedList() {
        assertThatThrownBy(() -> insertIntent(UUID.randomUUID(), "AUTHORISED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_payment_intents_status");
    }

    @Test
    @DisplayName("a new intent starts at version 0, for the optimistic lock")
    void versionStartsAtZero() {
        UUID order = UUID.randomUUID();
        insertIntent(order, "PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM payment_intents WHERE order_id = ?", Long.class, order)).isZero();
    }

    // ------------------------------------------------------------------
    // idempotency_keys
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a key is reserved before the charge and completed after it, nothing else")
    void keyStateIsReservedOrCompleted() {
        jdbc.update("INSERT INTO idempotency_keys (key, intent_id, state) VALUES (?, ?, 'RESERVED')",
                "k-" + UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO idempotency_keys (key, intent_id, state, response_json) VALUES (?, ?, 'DONE', '{}')",
                "k-" + UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_idempotency_keys_state");
    }

    @Test
    @DisplayName("a completed key always has the response it replays")
    void aCompletedKeyCarriesItsResponse() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO idempotency_keys (key, intent_id, state) VALUES (?, ?, 'COMPLETED')",
                "k-" + UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_idempotency_keys_completed_has_response");
    }

    @Test
    @DisplayName("a key's state is always written, never assumed")
    void keyStateHasNoDefault() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO idempotency_keys (key, intent_id, response_json) VALUES (?, ?, '{}')",
                "k-" + UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("state");
    }

    // ------------------------------------------------------------------
    // fake_gateway_charge: the fake gateway's own memory
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a capture always has the gateway's reference")
    void aCaptureHasAGatewayReference() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO fake_gateway_charge (charge_reference, outcome, amount, currency, decided_at)
                VALUES (?, 'CAPTURED', 100.00, 'ZMW', ?)""", UUID.randomUUID(), OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_fake_gateway_charge_captured_has_reference");
    }

    @Test
    @DisplayName("a decline always has its reason")
    void aDeclineHasAReason() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO fake_gateway_charge (charge_reference, outcome, amount, currency, decided_at)
                VALUES (?, 'DECLINED', 100.00, 'ZMW', ?)""", UUID.randomUUID(), OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_fake_gateway_charge_declined_has_reason");
    }

    @Test
    @DisplayName("an outcome is captured, declined or voided, nothing else")
    void outcomeIsAClosedList() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO fake_gateway_charge (charge_reference, outcome, amount, currency, decided_at)
                VALUES (?, 'PENDING', 100.00, 'ZMW', ?)""", UUID.randomUUID(), OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_fake_gateway_charge_outcome");
    }

    @Test
    @DisplayName("an amount never travels without its currency")
    void anAmountHasACurrency() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO fake_gateway_charge (charge_reference, outcome, gateway_reference, amount, decided_at)
                VALUES (?, 'CAPTURED', 'fake_ch_x', 100.00, ?)""", UUID.randomUUID(), OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_fake_gateway_charge_amount_has_currency");
    }

    @Test
    @DisplayName("a decided outcome is never rewritten or removed: a voided charge stays voided")
    void anOutcomeIsFinal() {
        UUID reference = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fake_gateway_charge (charge_reference, outcome, decided_at)
                VALUES (?, 'VOIDED', ?)""", reference, OffsetDateTime.now());

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE fake_gateway_charge SET outcome = 'CAPTURED', gateway_reference = 'fake_ch_x',
                       amount = 100.00, currency = 'ZMW'
                WHERE charge_reference = ?""", reference))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM fake_gateway_charge WHERE charge_reference = ?", reference))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------

    /** An intent in {@code status}; one being charged carries its charge reference, as it must. */
    private void insertIntent(UUID order, String status) {
        jdbc.update("""
                INSERT INTO payment_intents (id, order_id, keycloak_id, amount, currency, status, charge_reference)
                VALUES (?, ?, 'kc-schema', 100.00, 'ZMW', ?, ?)""",
                UUID.randomUUID(), order, status, "CHARGING".equals(status) ? UUID.randomUUID() : null);
    }

    private void insertCharging(UUID reference) {
        jdbc.update("""
                INSERT INTO payment_intents (id, order_id, keycloak_id, amount, currency, status, charge_reference)
                VALUES (?, ?, 'kc-schema', 100.00, 'ZMW', 'CHARGING', ?)""",
                UUID.randomUUID(), UUID.randomUUID(), reference);
    }
}
