package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.confirmation.ProviderConfirmations.ProviderMoney;
import com.oriah.payment.confirmation.ProviderConfirmations.Recorded;
import com.oriah.payment.confirmation.ProviderConfirmations.SameMoney;
import com.oriah.payment.dto.ConfirmPaymentRequest;
import com.oriah.payment.exception.PaymentProblem;
import com.oriah.payment.provider.ProviderReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money ZynlePay collected is recorded as the SAME payment confirmation staff write (Phase 13): the same table, the same reference key, the same
 * announcement, so settling a sale, holding money and refunding it work unchanged. What is different is who confirmed it (the system, on
 * ZynlePay's own answer to our question) and that it names its provider and the attempt it came from.
 *
 * <p>And the guards that keep one payment from being counted twice: staff cannot key money under a ZynlePay reference, nor for an order a
 * ZynlePay payment is in flight for; the machine does not record money staff already keyed under the network's own reference; and a ZynlePay
 * record is cancelled by the owner alone, which hands the attempt back to the owner to decide.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProviderConfirmationsTest {

    private static final Instant SENT = Instant.parse("2026-11-17T10:00:00Z");
    private static final Instant PAID_AT = Instant.parse("2026-11-17T10:02:11Z");
    private static final Staff SARA = new Staff("user:kc-sara", "Sara Zulu", false);
    private static final Staff OLIVE = new Staff("user:kc-olive", "Olive Mwale", true);

    @Autowired
    private ProviderConfirmations providerConfirmations;
    @Autowired
    private ConfirmationService confirmations;
    @Autowired
    private SettlementWatcher watcher;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ---------------------------------------------------------------- fixtures

    /** An attempt as it stands once the request has gone to ZynlePay: PENDING, holding its order. */
    private UUID pendingAttempt(UUID order, String method, String reference) {
        UUID id = UUID.randomUUID();
        boolean card = "CARD".equals(method);
        jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, amount, currency, fee_percent,
                                              fee_fixed, status, idempotency_key, request_fingerprint, started_at, sent_at)
                VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', ?, ?, ?, 180.00, 'ZMW', 2.500, 0.00, 'PENDING', ?, 'fp', ?, ?)""",
                id, reference, order, card ? "CARD" : "MOBILE_MONEY", method, card ? null : "+260961234567", "key-" + id, Timestamp.from(SENT), Timestamp.from(SENT));
        return id;
    }

    private static ProviderMoney money(UUID attempt, UUID order, String method, String reference, int ordinal, String operatorKey, String transactionKey) {
        return new ProviderMoney(attempt, order, method, reference, new BigDecimal("180.00"), new BigDecimal("4.50"), "ZMW", PAID_AT, null, ordinal, operatorKey,
                transactionKey);
    }

    private ProviderConfirmations.Result record(ProviderMoney money) {
        return tx.execute(status -> providerConfirmations.record(money, "system:zynlepay", null));
    }

    private UUID recorded(ProviderMoney money) {
        return ((Recorded) record(money)).confirmationId();
    }

    private UUID staffConfirm(Staff staff, UUID order, String method, String reference, String key) {
        return confirmations.confirm(new ConfirmPaymentRequest(order, method, reference, new BigDecimal("180.00"), BigDecimal.ZERO, null, null, null, null),
                key, staff).view().id();
    }

    private Map<String, Object> confirmation(UUID id) {
        return jdbc.queryForMap("SELECT * FROM payment_confirmation WHERE id = ?", id);
    }

    private List<String> announcements(UUID confirmation) {
        return jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_type = 'payment_confirmation' AND aggregate_id = ? "
                + "AND event_type = 'payment.confirmed' ORDER BY seq", String.class, confirmation.toString());
    }


    // ---------------------------------------------------------------- the record

    @Test
    @DisplayName("a verified MTN payment of K180.00 is the same record staff write: K180.00 in, K4.50 fee, K175.50 net, confirmed by the system, marked ZynlePay")
    void aVerifiedPaymentIsTheSameRecordStaffWrite() {
        UUID order = UUID.randomUUID();
        String reference = ProviderReference.mint();
        UUID attempt = pendingAttempt(order, "MTN", reference);

        UUID id = recorded(money(attempt, order, "MTN", reference, 1, "5512", "884213"));

        Map<String, Object> row = confirmation(id);
        assertThat(row.get("order_id")).isEqualTo(order);
        assertThat(row.get("method")).isEqualTo("MTN");
        assertThat(row.get("reference")).isEqualTo(reference);
        assertThat(row.get("reference_key")).isEqualTo("MTN:" + reference);
        assertThat((BigDecimal) row.get("gross")).isEqualByComparingTo("180.00");
        assertThat((BigDecimal) row.get("fee")).isEqualByComparingTo("4.50");
        assertThat((BigDecimal) row.get("net")).isEqualByComparingTo("175.50");
        assertThat(row.get("currency")).isEqualTo("ZMW");
        assertThat(((Timestamp) row.get("arrived_at")).toInstant()).isEqualTo(PAID_AT);
        assertThat(row.get("backdate_reason")).isNull();
        assertThat(row.get("confirmed_by")).isEqualTo("system:zynlepay");
        assertThat(row.get("confirmed_by_name")).isNull();
        assertThat(row.get("idempotency_key")).isEqualTo("zynlepay:" + reference);
        assertThat(row.get("provider")).isEqualTo("ZYNLEPAY");
        assertThat(row.get("provider_payment_id")).isEqualTo(attempt);
        assertThat(row.get("provider_payment_key")).isEqualTo(attempt);
        assertThat(row.get("outcome")).as("order has not answered yet, as for any confirmation").isEqualTo("PENDING");

        assertThat(announcements(id)).singleElement().satisfies(payload -> assertThat(payload)
                .contains("\"confirmationId\":\"" + id + "\"").contains("\"gross\":180.00").contains("\"fee\":4.50").contains("\"net\":175.50")
                .contains("\"confirmedBy\":\"system:zynlepay\"").contains("\"attempt\":1")
                .endsWith("\"provider\":\"ZYNLEPAY\",\"providerPaymentId\":\"" + attempt + "\"}"));
    }

    @Test
    @DisplayName("a card payment is recorded under CARD, which only a provider can record")
    void aCardPayment() {
        UUID order = UUID.randomUUID();
        String reference = ProviderReference.mint();
        UUID attempt = pendingAttempt(order, "CARD", reference);

        Map<String, Object> row = confirmation(recorded(money(attempt, order, "CARD", reference, 1, null, "T-77")));

        assertThat(row.get("method")).isEqualTo("CARD");
        assertThat(row.get("reference_key")).isEqualTo("CARD:" + reference);
    }

    @Test
    @DisplayName("an attempt has one standing record: recording it again writes nothing new and answers the same record")
    void recordedOnce() {
        UUID order = UUID.randomUUID();
        String reference = ProviderReference.mint();
        UUID attempt = pendingAttempt(order, "MTN", reference);
        ProviderMoney money = money(attempt, order, "MTN", reference, 1, null, null);

        UUID first = recorded(money);
        UUID again = recorded(money);

        assertThat(again).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE provider_payment_id = ?", Long.class, attempt)).isEqualTo(1);
        assertThat(announcements(first)).hasSize(1);
    }

    @Test
    @DisplayName("while order has not answered, the watcher announces a ZynlePay record again in the same shape: provider and attempt are on every announcement")
    void theWatcherRepeatsTheSameShape() {
        UUID order = UUID.randomUUID();
        String reference = ProviderReference.mint();
        UUID attempt = pendingAttempt(order, "MTN", reference);
        UUID id = recorded(money(attempt, order, "MTN", reference, 1, null, null));

        watcher.reemitDue(Instant.now().plus(Duration.ofMinutes(6)));

        assertThat(announcements(id)).hasSize(2).last().satisfies(payload -> assertThat(payload).contains("\"attempt\":2")
                .endsWith("\"provider\":\"ZYNLEPAY\",\"providerPaymentId\":\"" + attempt + "\"}"));
    }

    // ---------------------------------------------------------------- the same money, twice

    @Test
    @DisplayName("money staff already keyed under the network's own reference is not recorded a second time by the machine")
    void sameMoneyKeyedByStaffIsNotRecordedTwice() {
        UUID order = UUID.randomUUID();
        String reference = ProviderReference.mint();
        UUID attempt = pendingAttempt(order, "MTN", reference);
        jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'DECLINED', holds_order = false WHERE id = ?", attempt);
        String operatorReference = "MP" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        // The customer phoned with the network's message; Sara keyed it, typed loosely.
        UUID staffs = staffConfirm(SARA, order, "MTN", " " + operatorReference.toLowerCase() + " ", "k-" + UUID.randomUUID());

        ProviderConfirmations.Result result = record(money(attempt, order, "MTN", reference, 1, operatorReference, null));

        assertThat(result).isEqualTo(new SameMoney(staffs));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE order_id = ?", Long.class, order)).isEqualTo(1);
        // Under ZynlePay's own transaction id too; and a cancelled staff record no longer stands in the way.
        String transactionId = "TX" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        UUID order2 = UUID.randomUUID();
        String reference2 = ProviderReference.mint();
        UUID attempt2 = pendingAttempt(order2, "AIRTEL", reference2);
        jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'DECLINED', holds_order = false WHERE id = ?", attempt2);
        UUID keyed = staffConfirm(SARA, order2, "AIRTEL", transactionId, "k-" + UUID.randomUUID());
        assertThat(record(money(attempt2, order2, "AIRTEL", reference2, 1, null, transactionId))).isEqualTo(new SameMoney(keyed));
        confirmations.voidConfirmation(keyed, "keyed against the wrong record", SARA);
        assertThat(record(money(attempt2, order2, "AIRTEL", reference2, 1, null, transactionId))).isInstanceOf(Recorded.class);
    }

    @Test
    @DisplayName("staff cannot key money under a ZynlePay reference: not ours, not ZynlePay's transaction id, not the network's reference")
    void staffCannotKeyUnderAProviderReference() {
        UUID order = UUID.randomUUID();
        String reference = ProviderReference.mint();
        UUID attempt = pendingAttempt(order, "MTN", reference);
        String operatorReference = "OP" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        String transactionId = "TX" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'DECLINED', holds_order = false, operator_reference = ?, "
                + "operator_reference_key = ?, provider_transaction_id = ?, provider_transaction_key = ? WHERE id = ?", operatorReference, operatorReference,
                transactionId, transactionId, attempt);

        for (String typed : List.of(reference, reference.toLowerCase(), operatorReference, " " + transactionId.toLowerCase())) {
            assertThatThrownBy(() -> staffConfirm(SARA, UUID.randomUUID(), "MTN", typed, "k-" + UUID.randomUUID())).as(typed)
                    .isInstanceOfSatisfying(PaymentProblem.class, p -> {
                        assertThat(p.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(p.code()).isEqualTo("provider-payment-reference");
                    });
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE reference IN (?, ?, ?)", Long.class, reference, operatorReference,
                transactionId)).isZero();
    }

    @Test
    @DisplayName("while a ZynlePay payment holds an order, staff record no other money for it; once it has failed, they may")
    void staffRecordNothingWhileAnAttemptHoldsTheOrder() {
        UUID order = UUID.randomUUID();
        UUID attempt = pendingAttempt(order, "MTN", ProviderReference.mint());

        assertThatThrownBy(() -> staffConfirm(SARA, order, "CASH", null, "k-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(PaymentProblem.class, p -> assertThat(p.code()).isEqualTo("provider-payment-in-progress"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE order_id = ?", Long.class, order)).isZero();

        jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'DECLINED', holds_order = false WHERE id = ?", attempt);
        assertThatCode(() -> staffConfirm(SARA, order, "CASH", null, "k-" + UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("staff never key a card payment, nor use the keys the system keeps for ZynlePay; a claim and a payout by card are refused too")
    void whatStaffCannotKey() {
        assertThatThrownBy(() -> staffConfirm(SARA, UUID.randomUUID(), "CARD", "CARD12345", "k-" + UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CARD");
        assertThatThrownBy(() -> staffConfirm(SARA, UUID.randomUUID(), "CASH", null, "zynlepay:OZ7K2M9Q4T8X1B5D"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> PaymentMethod.parse("card")).isInstanceOf(IllegalArgumentException.class);
        assertThat(PaymentMethod.CARD.requiresReference()).isTrue();
    }

    // ---------------------------------------------------------------- cancelling a ZynlePay record

    @Test
    @DisplayName("a ZynlePay record is cancelled by the owner alone; the attempt then goes back to the owner, the machine never records it again, and the owner may record it anew")
    void theOwnerCancelsAProviderRecord() {
        UUID order = UUID.randomUUID();
        String reference = ProviderReference.mint();
        UUID attempt = pendingAttempt(order, "MTN", reference);
        UUID id = recorded(money(attempt, order, "MTN", reference, 1, null, null));
        jdbc.update("UPDATE provider_payment SET status = 'VERIFIED', holds_order = false, confirmation_id = ?, fee = 4.50, fee_basis = 'TARIFF 2.500% + 0.00', "
                + "verified_at = now(), next_check_at = now() WHERE id = ?", id, attempt);

        assertThatThrownBy(() -> confirmations.voidConfirmation(id, "ZynlePay's statement does not show it", SARA, false))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_confirmation_void WHERE confirmation_id = ?", Long.class, id)).isZero();

        confirmations.voidConfirmation(id, "ZynlePay's statement does not show it", OLIVE, true);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM provider_payment WHERE id = ?", attempt);
        assertThat(row.get("status")).isEqualTo("UNRESOLVED");
        assertThat(row.get("unresolved_reason")).isEqualTo("CONFIRMATION_VOIDED");
        assertThat(row.get("confirmation_id")).isNull();
        assertThat(row.get("holds_order")).isEqualTo(false);
        assertThat(row.get("next_check_at")).as("ZynlePay is not asked again: it would only repeat the answer in doubt").isNull();
        assertThat(confirmation(id).get("provider_payment_key")).isNull();
        assertThat(confirmation(id).get("reference_key")).isNull();
        assertThat(jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_type = 'provider_payment' AND aggregate_id = ? ORDER BY seq", String.class,
                attempt.toString())).singleElement().satisfies(payload -> assertThat(payload).contains("\"status\":\"UNRESOLVED\"")
                .contains("\"unresolvedReason\":\"CONFIRMATION_VOIDED\"").doesNotContain("260961234567").doesNotContain(reference));

        // The owner records it anew (the second record of this attempt): its own key, the reference free again.
        UUID second = recorded(money(attempt, order, "MTN", reference, 2, null, null));
        assertThat(second).isNotEqualTo(id);
        assertThat(confirmation(second).get("idempotency_key")).isEqualTo("zynlepay:" + reference + ":2");
        assertThat(confirmation(second).get("provider_payment_key")).isEqualTo(attempt);
    }
}
