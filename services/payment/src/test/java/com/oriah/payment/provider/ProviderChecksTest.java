package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.client.OrderClient;
import com.oriah.payment.provider.PaymentProvider.Failed;
import com.oriah.payment.provider.PaymentProvider.NoAnswer;
import com.oriah.payment.provider.PaymentProvider.NotFound;
import com.oriah.payment.provider.PaymentProvider.Paid;
import com.oriah.payment.provider.PaymentProvider.Pending;
import com.oriah.payment.provider.PaymentProvider.Unreadable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checker: Oriah asks ZynlePay what became of each payment in flight, and acts on the answer (Phase 13).
 *
 * <p><b>This is the only place a payment becomes money without a person.</b> ZynlePay's own answer "paid" to Oriah's own question, for the amount
 * that was asked, writes the same payment confirmation staff write, in the same transaction that marks the attempt VERIFIED. Its answer "failed"
 * ends the attempt. Every other answer (still deciding, no such reference, a code nobody listed, no answer at all) changes nothing but when it is
 * asked again; after half an hour of that the attempt is the owner's, and it is never assumed failed.
 *
 * <p>Two answers are never acted on by the machine: "paid" for another amount than was asked, and "paid" of an attempt that had already failed.
 * Both go to the owner, with what ZynlePay said kept beside them.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ScriptedProvider.Config.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProviderChecksTest {

    /** When the request went to ZynlePay, in these examples. */
    private static final Instant SENT = Instant.parse("2031-03-03T10:00:00Z");

    @Autowired
    private ProviderPaymentChecks checks;
    @Autowired
    private ScriptedProvider zynlePay;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private OrderClient orders;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /** Attempts are never deleted, so what earlier tests left in flight is put out of the checker's reach: each test asks about its own only. */
    @BeforeEach
    void nothingElseIsDue() {
        jdbc.update("UPDATE provider_payment SET next_check_at = NULL, final_recheck_at = NULL WHERE next_check_at IS NOT NULL OR final_recheck_at IS NOT NULL");
        jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'NOT_SENT', holds_order = false WHERE status = 'STARTING'");
    }

    // ---------------------------------------------------------------- fixtures

    private static Instant after(Duration time) {
        return SENT.plus(time);
    }

    /** An MTN payment of K180.00 as it stands once ZynlePay has taken the request: PENDING, first asked about 15 seconds later. */
    private UUID pending(String method) {
        UUID id = UUID.randomUUID();
        boolean card = "CARD".equals(method);
        jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, amount, currency, fee_percent,
                                              fee_fixed, status, idempotency_key, request_fingerprint, started_at, sent_at, next_check_at, provider_code)
                VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', ?, ?, ?, 180.00, 'ZMW', ?, 0.00, 'PENDING', ?, 'fp', ?, ?, ?, '120')""",
                id, ProviderReference.mint(), UUID.randomUUID(), card ? "CARD" : "MOBILE_MONEY", method, card ? null : "+260961234567",
                new BigDecimal(card ? "3.5" : "2.5"), "key-" + id, Timestamp.from(SENT), Timestamp.from(SENT), Timestamp.from(after(Duration.ofSeconds(15))));
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM provider_payment WHERE id = ?", id);
    }

    private String reference(UUID id) {
        return (String) row(id).get("reference_no");
    }

    private static Instant at(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    private List<Map<String, Object>> confirmations(UUID attempt) {
        return jdbc.queryForList("SELECT * FROM payment_confirmation WHERE provider_payment_id = ? ORDER BY confirmed_at", attempt);
    }

    private List<String> updates(UUID attempt) {
        return jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_type = 'provider_payment' AND aggregate_id = ? ORDER BY seq", String.class,
                attempt.toString());
    }

    private List<String> asked(UUID attempt) {
        return jdbc.queryForList("SELECT source || ':' || outcome FROM provider_payment_check WHERE payment_id = ? ORDER BY id", String.class, attempt);
    }

    private static Paid paid(String amount, Instant paidAt) {
        return new Paid("100", amount == null ? null : new BigDecimal(amount), amount == null ? null : "ZMW", paidAt, "884213", "5512", "MTN");
    }

    // ---------------------------------------------------------------- paid

    @Test
    @DisplayName("ZynlePay says paid, for the K180.00 that was asked: the attempt is VERIFIED and its money is the record staff write (K180.00 in, K4.50 fee, K175.50 net), in one go")
    void aPaidAnswerWritesTheSameConfirmationStaffWrite() {
        UUID id = pending("MTN");
        Instant paidAt = after(Duration.ofSeconds(100));
        zynlePay.statusOf(reference(id), paid("180.00", paidAt));

        int askedAbout = checks.poll(after(Duration.ofMinutes(2)));

        assertThat(askedAbout).isEqualTo(1);
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("VERIFIED");
        assertThat(row.get("holds_order")).isEqualTo(false);
        assertThat(row.get("next_check_at")).isNull();
        assertThat(row.get("check_lease_until")).isNull();
        assertThat((BigDecimal) row.get("fee")).isEqualByComparingTo("4.50");
        assertThat(row.get("fee_basis")).isEqualTo("TARIFF 2.500% + 0.00");
        assertThat(at(row.get("verified_at"))).isEqualTo(after(Duration.ofMinutes(2)));
        assertThat(at(row.get("provider_paid_at"))).isEqualTo(paidAt);
        assertThat(row.get("provider_transaction_key")).isEqualTo("884213");
        assertThat(row.get("check_count")).isEqualTo(1);

        assertThat(confirmations(id)).singleElement().satisfies(money -> {
            assertThat(money.get("id")).isEqualTo(row.get("confirmation_id"));
            assertThat(money.get("order_id")).isEqualTo(row.get("order_id"));
            assertThat(money.get("method")).isEqualTo("MTN");
            assertThat(money.get("reference")).isEqualTo(row.get("reference_no"));
            assertThat((BigDecimal) money.get("gross")).isEqualByComparingTo("180.00");
            assertThat((BigDecimal) money.get("fee")).isEqualByComparingTo("4.50");
            assertThat((BigDecimal) money.get("net")).isEqualByComparingTo("175.50");
            assertThat(at(money.get("arrived_at"))).as("dated to when ZynlePay says it was paid").isEqualTo(paidAt);
            assertThat(money.get("confirmed_by")).isEqualTo("system:zynlepay");
            assertThat(money.get("provider")).isEqualTo("ZYNLEPAY");
            assertThat(money.get("outcome")).isEqualTo("PENDING");
        });
        assertThat(updates(id)).singleElement().satisfies(payload -> assertThat(payload).contains("\"status\":\"VERIFIED\"")
                .contains("\"confirmationId\":\"" + row.get("confirmation_id") + "\"").contains("\"amount\":180.00"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type = 'payment.confirmed' AND aggregate_id = ?", Long.class,
                row.get("confirmation_id").toString())).isEqualTo(1);
        assertThat(asked(id)).containsExactly("POLL:PAID");
        assertThat((BigDecimal) jdbc.queryForObject("SELECT reported_amount FROM provider_payment_check WHERE payment_id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("180.00");

        // Asked again by anyone, nothing more happens: it is not due, and its money is recorded once.
        checks.poll(after(Duration.ofMinutes(5)));
        checks.checkOne(id, after(Duration.ofMinutes(6)), "STAFF", "user:kc-sara");
        assertThat(confirmations(id)).hasSize(1);
        assertThat(zynlePay.statusCalls(reference(id))).isEqualTo(1);
    }

    @Test
    @DisplayName("a card payment of K180.00 is recorded under CARD at the card tariff (K6.30); a 'paid' that names no amount is taken as the amount asked, dated to when Oriah saw it")
    void aCardPaymentAndAPaidThatNamesNoAmount() {
        UUID card = pending("CARD");
        zynlePay.statusOf(reference(card), paid(null, null));

        checks.poll(after(Duration.ofMinutes(1)));

        assertThat(confirmations(card)).singleElement().satisfies(money -> {
            assertThat(money.get("method")).isEqualTo("CARD");
            assertThat((BigDecimal) money.get("gross")).isEqualByComparingTo("180.00");
            assertThat((BigDecimal) money.get("fee")).isEqualByComparingTo("6.30");
            assertThat(at(money.get("arrived_at"))).isEqualTo(after(Duration.ofMinutes(1)));
        });
    }

    @Test
    @DisplayName("a time ZynlePay gives that cannot be right (before the request was sent, or in the future) is not believed: the money is dated to when Oriah saw it paid")
    void aPaidTimeThatCannotBeRight() {
        UUID early = pending("MTN");
        zynlePay.statusOf(reference(early), paid("180.00", SENT.minus(Duration.ofHours(3))));
        UUID late = pending("MTN");
        zynlePay.statusOf(reference(late), paid("180.00", after(Duration.ofDays(2))));

        checks.poll(after(Duration.ofMinutes(1)));

        assertThat(at(confirmations(early).getFirst().get("arrived_at"))).isEqualTo(after(Duration.ofMinutes(1)));
        assertThat(at(confirmations(late).getFirst().get("arrived_at"))).isEqualTo(after(Duration.ofMinutes(1)));
    }

    // ---------------------------------------------------------------- what the machine does not act on

    @Test
    @DisplayName("paid, but for K181.00 when K180.00 was asked: nothing is recorded; the attempt goes to the owner with what ZynlePay said, still holding its order")
    void paidForAnotherAmount() {
        UUID id = pending("MTN");
        zynlePay.statusOf(reference(id), paid("181.00", after(Duration.ofSeconds(50))));

        checks.poll(after(Duration.ofMinutes(1)));

        assertThat(row(id).get("status")).isEqualTo("UNRESOLVED");
        assertThat(row(id).get("unresolved_reason")).isEqualTo("AMOUNT_DIFFERS");
        assertThat(row(id).get("holds_order")).isEqualTo(true);
        assertThat(confirmations(id)).isEmpty();
        assertThat(updates(id)).singleElement().satisfies(payload -> assertThat(payload).contains("\"status\":\"UNRESOLVED\"")
                .contains("\"unresolvedReason\":\"AMOUNT_DIFFERS\""));
        assertThat((BigDecimal) jdbc.queryForObject("SELECT reported_amount FROM provider_payment_check WHERE payment_id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("181.00");
    }

    @Test
    @DisplayName("an attempt ZynlePay called failed and later calls paid is never verified by the machine: it goes to the owner, holds no order, and is not asked about again")
    void paidAfterFailed() {
        UUID id = pending("MTN");
        zynlePay.statusOf(reference(id), new Failed("995"));
        checks.poll(after(Duration.ofMinutes(1)));
        assertThat(row(id).get("status")).isEqualTo("FAILED");
        assertThat(row(id).get("failure_code")).isEqualTo("DECLINED");
        assertThat(row(id).get("holds_order")).isEqualTo(false);
        assertThat(at(row(id).get("final_recheck_at"))).as("asked about once more, fifteen minutes later").isEqualTo(after(Duration.ofMinutes(16)));
        assertThat(updates(id)).singleElement().satisfies(payload -> assertThat(payload).contains("\"failureCode\":\"DECLINED\""));

        // Nothing is due before then; then it is asked once more, and ZynlePay has changed its answer.
        assertThat(checks.poll(after(Duration.ofMinutes(10)))).isZero();
        zynlePay.statusOf(reference(id), paid("180.00", after(Duration.ofMinutes(12))));
        assertThat(checks.poll(after(Duration.ofMinutes(16)))).isEqualTo(1);

        assertThat(row(id).get("status")).isEqualTo("UNRESOLVED");
        assertThat(row(id).get("unresolved_reason")).isEqualTo("PAID_AFTER_FAILED");
        assertThat(row(id).get("holds_order")).isEqualTo(false);
        assertThat(row(id).get("next_check_at")).isNull();
        assertThat(row(id).get("final_recheck_at")).isNull();
        assertThat(confirmations(id)).isEmpty();
        assertThat(updates(id)).hasSize(2).last().satisfies(payload -> assertThat(payload).contains("\"unresolvedReason\":\"PAID_AFTER_FAILED\""));
        assertThat(checks.poll(after(Duration.ofDays(1)))).isZero();

        // A failed attempt whose one more question also says failed is simply done.
        UUID other = pending("MTN");
        zynlePay.statusOf(reference(other), new Failed("995"));
        checks.poll(after(Duration.ofMinutes(1)));
        checks.poll(after(Duration.ofMinutes(17)));
        assertThat(row(other).get("status")).isEqualTo("FAILED");
        assertThat(row(other).get("final_recheck_at")).isNull();
        assertThat(updates(other)).hasSize(1);
        assertThat(checks.poll(after(Duration.ofDays(1)))).isZero();
    }

    @Test
    @DisplayName("money staff already keyed under the network's own reference is not recorded again: the attempt goes to the owner, naming nothing twice")
    void sameMoneyAlreadyOnRecord() {
        UUID id = pending("MTN");
        String theirs = "MP" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by, confirmed_at,
                                                  idempotency_key, request_fingerprint)
                VALUES (?, ?, 'MTN', ?, ?, 180.00, 0, 'ZMW', now(), 'user:kc-sara', now(), ?, 'fp')""", UUID.randomUUID(), UUID.randomUUID(), theirs,
                "MTN:" + theirs, "idem-" + UUID.randomUUID());
        zynlePay.statusOf(reference(id), new Paid("100", new BigDecimal("180.00"), "ZMW", null, null, theirs.toLowerCase(), "MTN"));

        checks.poll(after(Duration.ofMinutes(1)));

        assertThat(row(id).get("status")).isEqualTo("UNRESOLVED");
        assertThat(row(id).get("unresolved_reason")).isEqualTo("SAME_MONEY_ON_RECORD");
        assertThat(row(id).get("next_check_at")).isNull();
        assertThat(confirmations(id)).isEmpty();
    }

    // ---------------------------------------------------------------- no final answer

    @Test
    @DisplayName("no final answer changes nothing but when it is asked again: every 15 seconds at first, a minute after ZynlePay says 'still deciding', and 'no such reference' is not a failure")
    void noFinalAnswerOnlyReschedules() {
        UUID id = pending("MTN");
        String reference = reference(id);

        zynlePay.statusOf(reference, new NoAnswer("no answer: HttpTimeoutException"));
        checks.poll(after(Duration.ofSeconds(15)));
        assertThat(row(id).get("status")).isEqualTo("PENDING");
        assertThat(at(row(id).get("next_check_at"))).isEqualTo(after(Duration.ofSeconds(30)));

        zynlePay.statusOf(reference, new Pending("990"));
        checks.poll(after(Duration.ofSeconds(30)));
        assertThat(at(row(id).get("next_check_at"))).isEqualTo(after(Duration.ofSeconds(90)));

        zynlePay.statusOf(reference, new NotFound("404"));
        checks.poll(after(Duration.ofSeconds(90)));
        assertThat(row(id).get("status")).as("the request may still be on its way").isEqualTo("PENDING");

        zynlePay.statusOf(reference, new Unreadable("555", "code 555 is not a listed answer"));
        checks.poll(after(Duration.ofMinutes(6)));
        assertThat(row(id).get("status")).isEqualTo("PENDING");
        assertThat(at(row(id).get("next_check_at"))).as("past the first five minutes: every minute").isEqualTo(after(Duration.ofMinutes(7)));
        assertThat(row(id).get("provider_code")).isEqualTo("555");

        assertThat(row(id).get("holds_order")).isEqualTo(true);
        assertThat(updates(id)).isEmpty();
        assertThat(confirmations(id)).isEmpty();
        assertThat(asked(id)).containsExactly("POLL:NO_ANSWER", "POLL:PENDING", "POLL:NOT_FOUND", "POLL:UNREADABLE");
        assertThat(row(id).get("check_count")).isEqualTo(4);
    }

    @Test
    @DisplayName("half an hour with no answer: the attempt is the owner's, never assumed failed; it still holds its order, is asked about every hour, and a later 'paid' is recorded")
    void afterHalfAnHourItIsTheOwners() {
        UUID id = pending("MTN");

        checks.poll(after(Duration.ofMinutes(29)));
        assertThat(row(id).get("status")).isEqualTo("PENDING");

        checks.poll(after(Duration.ofMinutes(30)));
        assertThat(row(id).get("status")).isEqualTo("UNRESOLVED");
        assertThat(row(id).get("unresolved_reason")).isEqualTo("NO_ANSWER");
        assertThat(row(id).get("holds_order")).isEqualTo(true);
        assertThat(at(row(id).get("unresolved_at"))).isEqualTo(after(Duration.ofMinutes(30)));
        assertThat(at(row(id).get("next_check_at"))).isEqualTo(after(Duration.ofMinutes(90)));
        assertThat(updates(id)).singleElement().satisfies(payload -> assertThat(payload).contains("\"status\":\"UNRESOLVED\"")
                .contains("\"unresolvedReason\":\"NO_ANSWER\""));

        // Still nothing: asked again an hour later, and no second announcement of the same state.
        checks.poll(after(Duration.ofMinutes(90)));
        assertThat(at(row(id).get("next_check_at"))).isEqualTo(after(Duration.ofMinutes(150)));
        assertThat(updates(id)).hasSize(1);

        zynlePay.statusOf(reference(id), paid("180.00", after(Duration.ofMinutes(20))));
        checks.poll(after(Duration.ofMinutes(150)));
        assertThat(row(id).get("status")).isEqualTo("VERIFIED");
        assertThat(confirmations(id)).hasSize(1);
        assertThat(updates(id)).hasSize(2);

        // After two weeks an unanswered attempt is not asked about any more; it stays the owner's.
        UUID silent = pending("MTN");
        checks.poll(after(Duration.ofMinutes(30)));
        checks.poll(after(Duration.ofMinutes(30)).plus(Duration.ofDays(14)));
        assertThat(row(silent).get("status")).isEqualTo("UNRESOLVED");
        assertThat(row(silent).get("next_check_at")).isNull();
    }

    @Test
    @DisplayName("an attempt already the owner's for want of an answer, which ZynlePay then calls paid for another amount, stays the owner's and says why; nothing loops")
    void unansweredThenPaidForAnotherAmount() {
        UUID id = pending("MTN");
        checks.poll(after(Duration.ofMinutes(30)));
        assertThat(row(id).get("unresolved_reason")).isEqualTo("NO_ANSWER");

        zynlePay.statusOf(reference(id), paid("18000.00", null));
        checks.poll(after(Duration.ofMinutes(90)));

        assertThat(row(id).get("status")).isEqualTo("UNRESOLVED");
        assertThat(row(id).get("unresolved_reason")).isEqualTo("AMOUNT_DIFFERS");
        assertThat(row(id).get("holds_order")).isEqualTo(true);
        assertThat(row(id).get("check_lease_until")).as("the answer was applied, not thrown away to be asked for again").isNull();
        assertThat(confirmations(id)).isEmpty();
        assertThat(updates(id)).hasSize(2).last().satisfies(payload -> assertThat(payload).contains("\"unresolvedReason\":\"AMOUNT_DIFFERS\""));
        assertThat(asked(id)).last().isEqualTo("POLL:PAID");
    }

    // ---------------------------------------------------------------- one asker at a time, and what is never asked

    @Test
    @DisplayName("an attempt somebody is asking about is left to them; at most five are asked about in one pass, the longest-waiting first")
    void oneAskerAtATimeAndFiveAPass() {
        UUID leased = pending("MTN");
        jdbc.update("UPDATE provider_payment SET check_lease_until = ? WHERE id = ?", Timestamp.from(after(Duration.ofMinutes(3))), leased);
        assertThat(checks.poll(after(Duration.ofMinutes(1)))).isZero();
        assertThat(zynlePay.statusCalls(reference(leased))).isZero();
        // The lease has run out (whoever held it is gone): it is asked about.
        assertThat(checks.poll(after(Duration.ofMinutes(4)))).isEqualTo(1);

        nothingElseIsDue();
        UUID[] seven = new UUID[7];
        for (int i = 0; i < 7; i++) {
            seven[i] = pending("MTN");
            jdbc.update("UPDATE provider_payment SET next_check_at = ? WHERE id = ?", Timestamp.from(after(Duration.ofSeconds(15 + i))), seven[i]);
        }
        assertThat(checks.poll(after(Duration.ofMinutes(1)))).isEqualTo(5);
        assertThat(zynlePay.statusCalls(reference(seven[4]))).isEqualTo(1);
        assertThat(zynlePay.statusCalls(reference(seven[5]))).isZero();
        assertThat(checks.poll(after(Duration.ofMinutes(1)))).isEqualTo(2);
    }

    @Test
    @DisplayName("an attempt that never got as far as being sent is failed as never sent after two minutes, and announced; ZynlePay is not asked about it")
    void anAttemptThatWasNeverSent() {
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        for (UUID id : new UUID[] {stale, fresh}) {
            jdbc.update("""
                    INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, status, idempotency_key,
                                                  request_fingerprint, started_at)
                    VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', 'MOBILE_MONEY', 'MTN', '+260961234567', 'STARTING', ?, 'fp', ?)""", id, ProviderReference.mint(),
                    UUID.randomUUID(), "key-" + id, Timestamp.from(id.equals(stale) ? SENT : after(Duration.ofSeconds(90))));
        }

        checks.poll(after(Duration.ofSeconds(150)));

        assertThat(row(stale).get("status")).isEqualTo("FAILED");
        assertThat(row(stale).get("failure_code")).isEqualTo("NOT_SENT");
        assertThat(row(stale).get("holds_order")).isEqualTo(false);
        assertThat(updates(stale)).singleElement().satisfies(payload -> assertThat(payload).contains("\"failureCode\":\"NOT_SENT\"").doesNotContain("amount"));
        assertThat(row(fresh).get("status")).as("still within its two minutes").isEqualTo("STARTING");
        assertThat(zynlePay.statusCalls(reference(stale))).isZero();
        jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'NOT_SENT', holds_order = false WHERE id = ?", fresh);
    }
}
