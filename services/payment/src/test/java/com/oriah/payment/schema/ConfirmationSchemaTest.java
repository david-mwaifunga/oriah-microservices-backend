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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the database itself holds true about staff-confirmed money (Phase 7, V6): a confirmation is a record of money that reached Oriah, so its
 * amounts and who confirmed it never change; the one (method, reference) pair is used once (cash has no key); a void, a rejection and a claim are
 * facts added and never edited; held money is owed once per confirmation. The service checks all of it first and says why in plain words; this
 * is the floor under it that a bug or a hand-run statement cannot get through.
 *
 * <p>Rows of these tables are never deleted (the database refuses it), so every test makes its own references.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ConfirmationSchemaTest {

    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-11-10T08:00:00Z"));

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static String ref() {
        return "REF" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    /** A confirmation as the service writes it. {@code reference} null means none; the key is the method and reference for a non-cash payment. */
    private UUID confirm(String method, String reference, String gross, String fee) {
        UUID id = UUID.randomUUID();
        String key = reference == null || "CASH".equals(method) ? null : method + ":" + reference;
        jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by,
                                                  confirmed_at, idempotency_key, request_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ZMW', ?, 'user:kc-sam', ?, ?, 'fp')""",
                id, UUID.randomUUID(), method, reference, key, new BigDecimal(gross), new BigDecimal(fee), AT, AT, "idem-" + id);
        return id;
    }

    private UUID confirmed() {
        return confirm("MTN", ref(), "1000.00", "10.00");
    }

    // ---------------------------------------------------------------- the money

    @Test
    @DisplayName("net is worked out by the database as gross less fee and is never written: it is 990.00 here, and 0.00 when the fee is all of it")
    void netIsGrossLessFee() {
        UUID id = confirm("MTN", ref(), "1000.00", "10.00");
        UUID free = confirm("MTN", ref(), "50.00", "50.00");

        assertThat(jdbc.queryForObject("SELECT net FROM payment_confirmation WHERE id = ?", BigDecimal.class, id)).isEqualByComparingTo("990.00");
        assertThat(jdbc.queryForObject("SELECT net FROM payment_confirmation WHERE id = ?", BigDecimal.class, free)).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET net = 1 WHERE id = ?", id)).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("an amount is above zero, a fee is not negative and not more than the amount")
    void amounts() {
        assertThatThrownBy(() -> confirm("MTN", ref(), "0.00", "0.00")).hasMessageContaining("payment_confirmation_gross_positive");
        assertThatThrownBy(() -> confirm("MTN", ref(), "-5.00", "0.00")).as("negative: more than one rule refuses it, whichever the database names first").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> confirm("MTN", ref(), "100.00", "-0.01")).hasMessageContaining("payment_confirmation_fee_in_range");
        assertThatThrownBy(() -> confirm("MTN", ref(), "100.00", "100.01")).hasMessageContaining("payment_confirmation_fee_in_range");
    }

    @Test
    @DisplayName("a currency is three capital letters")
    void currencyShape() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by,
                                                  confirmed_at, idempotency_key, request_fingerprint)
                VALUES (?, ?, 'CASH', NULL, NULL, 10, 0, 'zmw', ?, 'user:kc-sam', ?, ?, 'fp')""", UUID.randomUUID(), UUID.randomUUID(), AT, AT, "i-" + UUID.randomUUID()))
                .hasMessageContaining("payment_confirmation_currency_shape");
    }

    // ---------------------------------------------------------------- the reference

    @Test
    @DisplayName("every method but cash needs a reference; cash needs none and never has a key")
    void referenceByMethod() {
        assertThatThrownBy(() -> confirm("MTN", null, "100.00", "0.00")).hasMessageContaining("payment_confirmation_reference_for_non_cash");
        assertThatCode(() -> confirm("CASH", null, "100.00", "0.00")).doesNotThrowAnyException();
        assertThatCode(() -> confirm("CASH", "RECEIPT-BOOK-17", "100.00", "0.00")).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by,
                                                  confirmed_at, idempotency_key, request_fingerprint)
                VALUES (?, ?, 'CASH', 'CASHREF1', 'CASH:CASHREF1', 10, 0, 'ZMW', ?, 'user:kc-sam', ?, ?, 'fp')""",
                UUID.randomUUID(), UUID.randomUUID(), AT, AT, "i-" + UUID.randomUUID())).hasMessageContaining("payment_confirmation_cash_has_no_key");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET method = 'AIRTEL' WHERE id = ?", confirmed())).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("the same method and reference confirms money once: a second is refused whoever keys it, for whichever order; the same reference by another method is another payment")
    void aReferenceIsUsedOnce() {
        String reference = ref();
        confirm("MTN", reference, "500.00", "5.00");

        assertThatThrownBy(() -> confirm("MTN", reference, "500.00", "5.00")).hasMessageContaining("uq_payment_confirmation_reference_key");
        assertThatCode(() -> confirm("AIRTEL", reference, "500.00", "5.00")).doesNotThrowAnyException();
        assertThatCode(() -> confirm("CASH", reference, "500.00", "0.00")).as("cash has no key").doesNotThrowAnyException();
        assertThatCode(() -> confirm("CASH", reference, "500.00", "0.00")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the key must be the method and the reference, and may only be RELEASED (set to null), which is how a void frees the reference for a corrected confirmation")
    void theKeyCanOnlyBeReleased() {
        String reference = ref();
        UUID id = confirm("MTN", reference, "500.00", "5.00");

        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET reference_key = 'MTN:OTHER' WHERE id = ?", id)).isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE payment_confirmation SET reference_key = NULL WHERE id = ?", id);

        assertThatCode(() -> confirm("MTN", reference, "500.00", "5.00")).as("the freed reference confirms again").doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET reference_key = ? WHERE id = ?", "MTN:" + reference, id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by,
                                                  confirmed_at, idempotency_key, request_fingerprint)
                VALUES (?, ?, 'MTN', 'ABC123', 'MTN:SOMETHING-ELSE', 10, 0, 'ZMW', ?, 'user:kc-sam', ?, ?, 'fp')""",
                UUID.randomUUID(), UUID.randomUUID(), AT, AT, "i-" + UUID.randomUUID())).hasMessageContaining("payment_confirmation_key_is_the_reference");
    }

    @Test
    @DisplayName("an idempotency key is used once: a repeated request is the same confirmation, never a second one")
    void idempotencyKeyIsUnique() {
        UUID id = confirmed();
        String key = jdbc.queryForObject("SELECT idempotency_key FROM payment_confirmation WHERE id = ?", String.class, id);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by,
                                                  confirmed_at, idempotency_key, request_fingerprint)
                VALUES (?, ?, 'CASH', NULL, NULL, 10, 0, 'ZMW', ?, 'user:kc-sam', ?, ?, 'fp')""", UUID.randomUUID(), UUID.randomUUID(), AT, AT, key))
                .hasMessageContaining("payment_confirmation_idempotency_key_key");
    }

    @Test
    @DisplayName("a back-dating reason, when there is one, is 5 to 200 characters once the spaces are gone")
    void backdateReasonLength() {
        UUID id = UUID.randomUUID();
        String insert = """
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by,
                                                  confirmed_at, idempotency_key, request_fingerprint, backdate_reason)
                VALUES (?, ?, 'CASH', NULL, NULL, 10, 0, 'ZMW', ?, 'user:kc-sam', ?, ?, 'fp', ?)""";

        assertThatThrownBy(() -> jdbc.update(insert, id, UUID.randomUUID(), AT, AT, "i-" + id, "  abc ")).hasMessageContaining("payment_confirmation_backdate_reason_length");
        assertThatCode(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), AT, AT, "i-" + UUID.randomUUID(), "paid Friday")).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- never edited, never deleted

    @Test
    @DisplayName("who confirmed what, when, for which order and how much can never be changed, and a confirmation is never deleted")
    void aConfirmationIsARecord() {
        UUID id = confirmed();

        for (String change : new String[] {"gross = 1", "fee = 0", "order_id = gen_random_uuid()", "arrived_at = now()", "confirmed_by = 'user:someone-else'",
                "confirmed_at = now()", "currency = 'USD'", "reference = 'CHANGED'", "idempotency_key = 'other'", "backdate_reason = 'a later excuse'"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET " + change + " WHERE id = ?", id)).as(change).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_confirmation WHERE id = ?", id)).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("no table of these records can be emptied: a truncate is refused (by the trigger, or by Postgres itself where other tables refer to it)")
    void nothingCanBeTruncated() {
        for (String table : new String[] {"payment_claim", "payment_confirmation", "payment_confirmation_void", "held_money", "payment_rejection"}) {
            assertThatThrownBy(() -> jdbc.execute("TRUNCATE " + table)).as(table).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE payment_rejection")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE held_money")).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("the outcome starts PENDING, is set once to SETTLED or HELD (a held one says why), and is final after that")
    void theOutcomeIsSetOnce() {
        UUID settled = confirmed();
        UUID held = confirmed();
        UUID another = confirmed();

        assertThat(jdbc.queryForObject("SELECT outcome FROM payment_confirmation WHERE id = ?", String.class, settled)).isEqualTo("PENDING");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET outcome = 'HELD', outcome_at = now() WHERE id = ?", held)).hasMessageContaining("payment_confirmation_held_has_reason");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET outcome = 'SETTLED' WHERE id = ?", settled)).hasMessageContaining("payment_confirmation_outcome_timed");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET outcome = 'PAID', outcome_at = now() WHERE id = ?", settled)).hasMessageContaining("payment_confirmation_outcome_known");

        jdbc.update("UPDATE payment_confirmation SET outcome = 'SETTLED', outcome_at = now() WHERE id = ?", settled);
        jdbc.update("UPDATE payment_confirmation SET outcome = 'HELD', outcome_reason = 'WRONG_AMOUNT', outcome_at = now() WHERE id = ?", held);

        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET outcome = 'HELD', outcome_reason = 'ALREADY_PAID' WHERE id = ?", settled)).hasMessageContaining("final");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation SET outcome = 'SETTLED', outcome_reason = NULL WHERE id = ?", held)).hasMessageContaining("final");
        assertThat(jdbc.queryForObject("SELECT outcome FROM payment_confirmation WHERE id = ?", String.class, another)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("how often a confirmation has been announced again, and when next, may change while it is PENDING (the watcher's bookkeeping), and nothing else about it")
    void emitBookkeeping() {
        UUID id = confirmed();

        jdbc.update("UPDATE payment_confirmation SET emit_attempts = 2, next_emit_at = now() + interval '5 minutes' WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT emit_attempts FROM payment_confirmation WHERE id = ?", Integer.class, id)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- a void

    @Test
    @DisplayName("a confirmation is voided once, with a reason of at least ten characters, and a void is never edited or deleted")
    void aVoid() {
        UUID id = confirmed();
        String insert = "INSERT INTO payment_confirmation_void (id, confirmation_id, voided_by, reason, voided_at) VALUES (?, ?, 'user:kc-sam', ?, now())";

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), id, "too short")).hasMessageContaining("payment_confirmation_void_reason_length");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), "keyed against the wrong order")).hasMessageContaining("payment_confirmation_void_confirmation_id_fkey");
        UUID voidId = UUID.randomUUID();
        jdbc.update(insert, voidId, id, "keyed against the wrong order");

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), id, "keyed against the wrong order")).hasMessageContaining("payment_confirmation_void_confirmation_id_key");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_confirmation_void SET reason = 'something else entirely' WHERE id = ?", voidId)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_confirmation_void WHERE id = ?", voidId)).hasMessageContaining("append-only");
    }

    // ---------------------------------------------------------------- held money

    @Test
    @DisplayName("money held for a customer is one record per confirmation, for a known reason, above zero; the only change it ever takes is to be voided")
    void heldMoney() {
        UUID id = confirmed();
        String insert = "INSERT INTO held_money (id, confirmation_id, order_id, reason, owed_gross, currency, status, created_at) VALUES (?, ?, ?, ?, ?, 'ZMW', 'OWED', now())";

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), id, UUID.randomUUID(), "BECAUSE", new BigDecimal("1000.00"))).hasMessageContaining("held_money_reason_known");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), id, UUID.randomUUID(), "WRONG_AMOUNT", BigDecimal.ZERO)).hasMessageContaining("held_money_owed_positive");
        UUID heldId = UUID.randomUUID();
        jdbc.update(insert, heldId, id, UUID.randomUUID(), "WRONG_AMOUNT", new BigDecimal("1000.00"));

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), id, UUID.randomUUID(), "ALREADY_PAID", new BigDecimal("1000.00"))).hasMessageContaining("held_money_confirmation_id_key");
        assertThatThrownBy(() -> jdbc.update("UPDATE held_money SET owed_gross = 1 WHERE id = ?", heldId)).hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> jdbc.update("UPDATE held_money SET status = 'OWED', voided_at = now() WHERE id = ?", heldId)).isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE held_money SET status = 'VOIDED', voided_at = now() WHERE id = ?", heldId);
        assertThatThrownBy(() -> jdbc.update("UPDATE held_money SET status = 'OWED', voided_at = NULL WHERE id = ?", heldId)).hasMessageContaining("final");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM held_money WHERE id = ?", heldId)).hasMessageContaining("append-only");
    }

    // ---------------------------------------------------------------- claims and rejections

    private UUID claim(String method, String reference) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_claim (id, order_id, method, reference, submitted_at) VALUES (?, ?, ?, ?, ?)", id, UUID.randomUUID(), method, reference, AT);
        return id;
    }

    @Test
    @DisplayName("a claim is what a shopper said they paid with: never cash, a reference in its one spelling, never edited, and the same claim twice is one")
    void aClaim() {
        UUID id = claim("MTN", ref());

        assertThatThrownBy(() -> claim("CASH", ref())).hasMessageContaining("payment_claim_method_known");
        assertThatThrownBy(() -> claim("MTN", "mp 2411")).hasMessageContaining("payment_claim_reference_spelled");
        assertThatThrownBy(() -> claim("MTN", "mp2411abc")).hasMessageContaining("payment_claim_reference_spelled");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO payment_claim (id, order_id, method, reference, submitted_at) VALUES (?, ?, 'MTN', 'ABC123', ?)", id, UUID.randomUUID(), AT))
                .hasMessageContaining("payment_claim_pkey");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_claim SET reference = 'CHANGED1' WHERE id = ?", id)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_claim WHERE id = ?", id)).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("a claim is rejected once, for a proof the database knows (a 'not found at the provider' one needs its evidence), and a rejection is never edited or deleted")
    void aRejection() {
        UUID claimId = claim("MTN", ref());
        String insert = """
                INSERT INTO payment_rejection (id, claim_id, order_id, method, reference, proof_type, evidence, rejected_by, rejected_at)
                VALUES (?, ?, ?, 'MTN', 'ABC123', ?, ?, 'user:kc-sam', now())""";

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), claimId, UUID.randomUUID(), "I_FEEL_LIKE_IT", null)).hasMessageContaining("payment_rejection_proof_known");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), claimId, UUID.randomUUID(), "NOT_FOUND_AT_PROVIDER", "short")).hasMessageContaining("payment_rejection_evidence_for_not_found");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "REFERENCE_ALREADY_USED", null)).hasMessageContaining("payment_rejection_claim_id_fkey");
        UUID rejectionId = UUID.randomUUID();
        jdbc.update(insert, rejectionId, claimId, UUID.randomUUID(), "NOT_FOUND_AT_PROVIDER", "checked the MTN agent portal: no such transaction");

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), claimId, UUID.randomUUID(), "REFERENCE_ALREADY_USED", null)).hasMessageContaining("payment_rejection_claim_id_key");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_rejection SET evidence = 'changed my mind later' WHERE id = ?", rejectionId)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_rejection WHERE id = ?", rejectionId)).hasMessageContaining("append-only");
    }
}
