package com.oriah.payment.schema;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.provider.ProviderReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the database itself holds true about a payment collected through ZynlePay (Phase 13, V11). The service checks all of it first and says why
 * in plain words; this is the floor under it that a bug or a hand-run statement cannot get through: one attempt holds an order at a time; what
 * was asked for never changes; an attempt moves only along the allowed edges, and a failed one is never made "paid" directly; a verified attempt
 * has its money record; one attempt has one standing money record.
 *
 * <p>Rows of these tables are never deleted (the database refuses it), so every test makes its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProviderPaymentSchemaTest {

    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-11-17T10:00:00Z"));

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /** A new attempt, as the first transaction of a start writes it: nothing sent, the amount not yet known. */
    private UUID starting(UUID order) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, status, idempotency_key,
                                              request_fingerprint, started_at)
                VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', 'MOBILE_MONEY', 'MTN', '+260961234567', 'STARTING', ?, 'fp', ?)""",
                id, ProviderReference.mint(), order, "key-" + id, AT);
        return id;
    }

    /** The order agreed: the amount and the tariff are set, and it is PENDING before anything is sent. */
    private UUID pending(UUID order) {
        UUID id = starting(order);
        jdbc.update("UPDATE provider_payment SET status = 'PENDING', amount = 180.00, currency = 'ZMW', fee_percent = 2.500, fee_fixed = 0.00, sent_at = ? "
                + "WHERE id = ?", AT, id);
        return id;
    }

    private UUID pending() {
        return pending(UUID.randomUUID());
    }

    private void move(UUID id, String set) {
        jdbc.update("UPDATE provider_payment SET " + set + " WHERE id = ?", id);
    }

    private void failed(UUID id, String code) {
        move(id, "status = 'FAILED', failure_code = '" + code + "', holds_order = false");
    }

    /** The money record of a verified attempt, as the machine writes it. */
    private UUID confirmation(UUID attempt, UUID order, String method, String key) {
        UUID id = UUID.randomUUID();
        String reference = jdbc.queryForObject("SELECT reference_no FROM provider_payment WHERE id = ?", String.class, attempt);
        jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by,
                                                  confirmed_at, idempotency_key, request_fingerprint, provider, provider_payment_id, provider_payment_key)
                VALUES (?, ?, ?, ?, NULL, 180.00, 4.50, 'ZMW', ?, 'system:zynlepay', ?, ?, 'fp', 'ZYNLEPAY', ?, ?)""",
                id, order, method, reference + "-" + key, AT, AT, "zynlepay:" + reference + ":" + key, attempt, attempt);
        return id;
    }

    private void verify(UUID attempt) {
        UUID order = jdbc.queryForObject("SELECT order_id FROM provider_payment WHERE id = ?", UUID.class, attempt);
        // A record of its own each time: a move the database refuses leaves the record behind (these statements are not one transaction here).
        jdbc.update("UPDATE payment_confirmation SET provider_payment_key = NULL WHERE provider_payment_id = ?", attempt);
        UUID confirmation = confirmation(attempt, order, "MTN", UUID.randomUUID().toString().substring(0, 8));
        jdbc.update("UPDATE provider_payment SET status = 'VERIFIED', holds_order = false, confirmation_id = ?, fee = 4.50, fee_basis = 'TARIFF 2.500% + 0.00', "
                + "unresolved_reason = NULL WHERE id = ?", confirmation, attempt);
    }

    // ---------------------------------------------------------------- one attempt holds an order

    @Test
    @DisplayName("an order has one attempt holding it; once that attempt fails, another may start")
    void oneAttemptHoldsAnOrder() {
        UUID order = UUID.randomUUID();
        UUID first = pending(order);

        assertThatThrownBy(() -> starting(order)).isInstanceOf(DataAccessException.class).hasMessageContaining("uq_provider_payment_one_holding_order");

        failed(first, "DECLINED");
        assertThatCode(() -> starting(order)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an attempt that has let go of its order never holds it again, and a failed or verified one cannot be said to hold it")
    void holdingIsNeverRegained() {
        UUID id = pending();
        failed(id, "DECLINED");

        assertThatThrownBy(() -> move(id, "holds_order = true")).isInstanceOf(DataAccessException.class);
        move(id, "status = 'UNRESOLVED', failure_code = NULL, unresolved_reason = 'PAID_AFTER_FAILED'");
        assertThatThrownBy(() -> move(id, "holds_order = true")).isInstanceOf(DataAccessException.class).hasMessageContaining("never holds its order again");
    }

    // ---------------------------------------------------------------- what was asked for never changes

    @Test
    @DisplayName("who, for which order, by what and under which reference never change; the amount and the tariff are set once")
    void whatWasAskedForNeverChanges() {
        UUID id = pending();

        for (String change : new String[] {"order_id = gen_random_uuid()", "reference_no = 'OZ00000000000000'", "keycloak_id = 'kc-mallory'", "channel = 'CARD'",
                "method = 'AIRTEL'", "idempotency_key = 'other'", "started_at = now()", "amount = 1.00", "currency = 'USD'", "fee_percent = 0.000",
                "fee_fixed = 9.00", "payer_msisdn = '+260971234567'"}) {
            assertThatThrownBy(() -> move(id, change)).as(change).isInstanceOf(DataAccessException.class);
        }
        // The one change a payer's number can take: it is cleared when it is no longer needed.
        assertThatCode(() -> move(id, "payer_msisdn = NULL")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a reference is ours and unique; a number is +260 and nine digits; mobile money is a wallet and a card is a card")
    void theShapes() {
        UUID order = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, status, idempotency_key, request_fingerprint, started_at)
                VALUES (?, 'ZYNLEPAY', 'MP241105.0915', ?, 'kc-alice', 'CARD', 'CARD', 'STARTING', ?, 'fp', ?)""", UUID.randomUUID(), order, "k-" + UUID.randomUUID(), AT))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, status, idempotency_key, request_fingerprint, started_at)
                VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', 'CARD', 'MTN', 'STARTING', ?, 'fp', ?)""", UUID.randomUUID(), ProviderReference.mint(), order,
                "k-" + UUID.randomUUID(), AT)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, status, idempotency_key,
                                              request_fingerprint, started_at)
                VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', 'MOBILE_MONEY', 'MTN', '0961234567', 'STARTING', ?, 'fp', ?)""", UUID.randomUUID(), ProviderReference.mint(),
                order, "k-" + UUID.randomUUID(), AT)).isInstanceOf(DataAccessException.class);
        // The same key from the same shopper is one request.
        UUID id = starting(UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, status, idempotency_key,
                                              request_fingerprint, started_at)
                VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', 'MOBILE_MONEY', 'MTN', '+260961234567', 'STARTING', ?, 'fp', ?)""", UUID.randomUUID(), ProviderReference.mint(),
                UUID.randomUUID(), "key-" + id, AT)).isInstanceOf(DataAccessException.class).hasMessageContaining("uq_provider_payment_key");
    }

    @Test
    @DisplayName("an attempt that was sent has its amount and tariff; only one refused before it was sent may have none")
    void aSentAttemptHasItsAmount() {
        UUID noAmount = starting(UUID.randomUUID());
        assertThatThrownBy(() -> move(noAmount, "status = 'PENDING', sent_at = now()")).isInstanceOf(DataAccessException.class);
        assertThatCode(() -> failed(noAmount, "ORDER_REFUSED")).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- the moves

    @Test
    @DisplayName("a failed attempt is never made 'verified' directly: ZynlePay saying 'paid' afterwards makes it the owner's to decide")
    void aFailedAttemptIsNeverVerifiedDirectly() {
        UUID id = pending();
        failed(id, "DECLINED");

        assertThatThrownBy(() -> verify(id)).isInstanceOf(DataAccessException.class).hasMessageContaining("FAILED");
        assertThatCode(() -> move(id, "status = 'UNRESOLVED', failure_code = NULL, unresolved_reason = 'PAID_AFTER_FAILED'")).doesNotThrowAnyException();
        assertThatCode(() -> verify(id)).as("the owner's resolve").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an attempt that never reached ZynlePay is final: it cannot be reported paid")
    void anAttemptThatWasNeverSentIsFinal() {
        UUID id = starting(UUID.randomUUID());
        failed(id, "NOT_SENT");

        assertThatThrownBy(() -> move(id, "status = 'UNRESOLVED', failure_code = NULL, unresolved_reason = 'PAID_AFTER_FAILED'")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> move(id, "status = 'PENDING', failure_code = NULL")).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("the other moves that are not allowed are refused, and each status carries exactly its own code or reason")
    void illegalMoves() {
        UUID startingOne = starting(UUID.randomUUID());
        assertThatThrownBy(() -> failed(startingOne, "DECLINED")).as("nothing was sent, so nothing was declined").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> move(startingOne, "status = 'UNRESOLVED', unresolved_reason = 'NO_ANSWER'")).isInstanceOf(DataAccessException.class);

        UUID pendingOne = pending();
        assertThatThrownBy(() -> move(pendingOne, "status = 'UNRESOLVED', unresolved_reason = 'CONFIRMATION_VOIDED'")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> failed(pendingOne, "MARKED_NOT_PAID")).as("only an attempt that went to the owner").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> move(pendingOne, "status = 'FAILED', holds_order = false")).as("a failure has its code").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> move(pendingOne, "failure_code = 'DECLINED'")).as("a code belongs to a failure").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> move(pendingOne, "status = 'STARTING'")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> move(pendingOne, "status = 'VERIFIED', holds_order = false")).as("verified without its money record")
                .isInstanceOf(DataAccessException.class);

        move(pendingOne, "status = 'UNRESOLVED', unresolved_reason = 'NO_ANSWER'");
        assertThat(jdbc.queryForObject("SELECT holds_order FROM provider_payment WHERE id = ?", Boolean.class, pendingOne))
                .as("an unanswered attempt still holds its order").isTrue();
        assertThatCode(() -> move(pendingOne, "status = 'FAILED', unresolved_reason = NULL, failure_code = 'MARKED_NOT_PAID', holds_order = false"))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- the money record

    @Test
    @DisplayName("one attempt has one standing money record; a second is refused until the first is cancelled")
    void oneStandingMoneyRecordPerAttempt() {
        UUID order = UUID.randomUUID();
        UUID attempt = pending(order);
        UUID first = confirmation(attempt, order, "MTN", "1");

        assertThatThrownBy(() -> confirmation(attempt, order, "MTN", "2")).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_payment_confirmation_provider_payment_key");

        jdbc.update("INSERT INTO payment_confirmation_void (id, confirmation_id, voided_by, reason, voided_at) VALUES (?, ?, 'user:kc-olive', 'recorded wrongly: test', ?)",
                UUID.randomUUID(), first, AT);
        jdbc.update("UPDATE payment_confirmation SET provider_payment_key = NULL WHERE id = ?", first);
        assertThatCode(() -> confirmation(attempt, order, "MTN", "2")).doesNotThrowAnyException();
        // Released, never set again or pointed elsewhere; and which attempt it came from is kept.
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET provider_payment_key = ? WHERE id = ?", attempt, first))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET provider_payment_id = NULL, provider = NULL WHERE id = ?", first))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a card payment is always a provider's: staff cannot key CARD, and a provider record names its attempt")
    void cardMoneyIsAlwaysAProviders() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by, confirmed_at,
                                                  idempotency_key, request_fingerprint)
                VALUES (?, ?, 'CARD', 'CARD123', 'CARD:CARD123', 100.00, 0, 'ZMW', ?, 'user:kc-sam', ?, ?, 'fp')""", UUID.randomUUID(), UUID.randomUUID(), AT, AT,
                "idem-" + UUID.randomUUID())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by, confirmed_at,
                                                  idempotency_key, request_fingerprint, provider)
                VALUES (?, ?, 'MTN', 'X123456', 'MTN:X123456', 100.00, 0, 'ZMW', ?, 'system:zynlepay', ?, ?, 'fp', 'ZYNLEPAY')""", UUID.randomUUID(),
                UUID.randomUUID(), AT, AT, "idem-" + UUID.randomUUID())).as("a provider with no attempt").isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a verified attempt's link to its money record is let go only when that record was cancelled, and then the owner decides it again")
    void theLinkIsLetGoOnlyAfterAVoid() {
        UUID attempt = pending();
        verify(attempt);
        UUID confirmation = jdbc.queryForObject("SELECT confirmation_id FROM provider_payment WHERE id = ?", UUID.class, attempt);

        assertThatThrownBy(() -> move(attempt, "status = 'UNRESOLVED', unresolved_reason = 'CONFIRMATION_VOIDED', confirmation_id = NULL"))
                .as("its record still stands").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> move(attempt, "confirmation_id = gen_random_uuid()")).isInstanceOf(DataAccessException.class);

        jdbc.update("INSERT INTO payment_confirmation_void (id, confirmation_id, voided_by, reason, voided_at) VALUES (?, ?, 'user:kc-olive', 'recorded wrongly: test', ?)",
                UUID.randomUUID(), confirmation, AT);
        assertThatCode(() -> move(attempt, "status = 'UNRESOLVED', unresolved_reason = 'CONFIRMATION_VOIDED', confirmation_id = NULL")).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- records, never edited

    @Test
    @DisplayName("an attempt is never deleted; what ZynlePay was asked and the owner's decisions are added and never edited")
    void recordsAreKept() {
        UUID attempt = pending();
        jdbc.update("INSERT INTO provider_payment_check (payment_id, source, asked_by, asked_at, answered_at, outcome, provider_code, reported_amount, reported_currency) "
                + "VALUES (?, 'POLL', 'system:zynlepay', ?, ?, 'PAID', '100', ?, 'ZMW')", attempt, AT, AT, new BigDecimal("180.00"));
        jdbc.update("INSERT INTO provider_payment_resolution (id, payment_id, idempotency_key, request_fingerprint, outcome, evidence, override_pending, "
                + "decided_by, decided_at, resulting_status) VALUES (?, ?, ?, 'fp', 'NOT_PAID', 'ZynlePay dashboard shows no such payment', false, 'user:kc-olive', ?, 'FAILED')",
                UUID.randomUUID(), attempt, "res-" + attempt, AT);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM provider_payment WHERE id = ?", attempt)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE provider_payment_check SET outcome = 'FAILED' WHERE payment_id = ?", attempt))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM provider_payment_check WHERE payment_id = ?", attempt)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE provider_payment_resolution SET outcome = 'PAID' WHERE payment_id = ?", attempt))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO provider_payment_resolution (id, payment_id, idempotency_key, request_fingerprint, outcome, evidence, "
                + "override_pending, decided_by, decided_at, resulting_status) VALUES (?, ?, ?, 'fp', 'PAID', 'short', false, 'user:kc-olive', ?, 'VERIFIED')",
                UUID.randomUUID(), attempt, "res2-" + attempt, AT)).as("evidence under ten characters").isInstanceOf(DataAccessException.class);
    }
}
