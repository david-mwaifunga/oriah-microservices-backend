package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.payment.confirmation.ProviderConfirmations;
import com.oriah.payment.confirmation.ProviderConfirmations.ProviderMoney;
import com.oriah.payment.confirmation.ProviderConfirmations.Recorded;
import com.oriah.payment.confirmation.ProviderConfirmations.SameMoney;
import com.oriah.payment.events.ProviderPaymentEvents;
import com.oriah.payment.provider.AttemptState.FailureCode;
import com.oriah.payment.provider.AttemptState.Status;
import com.oriah.payment.provider.AttemptState.UnresolvedReason;
import com.oriah.payment.provider.PaymentProvider.Failed;
import com.oriah.payment.provider.PaymentProvider.NoAnswer;
import com.oriah.payment.provider.PaymentProvider.NotFound;
import com.oriah.payment.provider.PaymentProvider.Paid;
import com.oriah.payment.provider.PaymentProvider.Pending;
import com.oriah.payment.provider.PaymentProvider.StatusRefused;
import com.oriah.payment.provider.PaymentProvider.Unreadable;
import com.oriah.payment.provider.zynlepay.ZynlePaySetup;

import lombok.extern.slf4j.Slf4j;

/**
 * Asks ZynlePay what became of a payment, and acts on the answer (Phase 13). Run by the checker on its schedule, by a member of staff pressing
 * "check now", and by the owner's resolve before it takes the owner's word: all three go through {@link #checkOne}, one at a time per attempt.
 *
 * <h2>What an answer does</h2>
 * <ul>
 *   <li><b>Paid</b>, for the amount that was asked (or naming none): the attempt is VERIFIED and its money is recorded as a payment confirmation
 *       ({@link ProviderConfirmations}), in ONE transaction. This is the only way a payment becomes money without a person.</li>
 *   <li><b>Paid, for another amount or currency</b>: nothing is recorded; UNRESOLVED / AMOUNT_DIFFERS, for the owner.</li>
 *   <li><b>Paid, of an attempt that had FAILED</b>: never verified by the machine; UNRESOLVED / PAID_AFTER_FAILED, holding no order, for the owner.</li>
 *   <li><b>Paid, when staff already keyed that money</b> under the network's own reference: nothing is recorded; UNRESOLVED / SAME_MONEY_ON_RECORD.</li>
 *   <li><b>Failed</b>: FAILED / DECLINED; asked about once more later, to catch ZynlePay contradicting itself.</li>
 *   <li><b>Anything else</b> (still deciding, no such reference, a code nobody listed, Oriah turned away, no answer): nothing changes but when it is
 *       asked again. Once {@code give-up-after} has passed since the request went out, the attempt is UNRESOLVED / NO_ANSWER: the owner's, still
 *       holding its order, still asked about now and then, and <strong>never assumed failed</strong>.</li>
 * </ul>
 *
 * <p>ZynlePay is asked with no transaction open. The answer is applied under the order's lock and then the attempt's row lock (the one order
 * payment takes its locks in), so a staff confirmation, a start and this cannot pass each other.
 */
@Service
@Slf4j
public class ProviderPaymentChecks {

    /** How a "paid" time ZynlePay reports is believed: it cannot be before the request went out, nor ahead of now, by more than these. */
    private static final Duration BEFORE_SENT = Duration.ofMinutes(1);
    private static final Duration AHEAD_OF_NOW = Duration.ofSeconds(30);

    public enum Result {
        /** ZynlePay was asked and its answer applied. */
        ASKED,
        /** There is nothing to ask about: the attempt is over, was never sent, or is the owner's alone to decide. */
        NOT_ASKABLE,
        /** Somebody else is asking about it at this moment. */
        BUSY
    }

    private record Row(UUID id, String referenceNo, UUID orderId, String keycloakId, String channel, String method, BigDecimal amount, String currency,
                       BigDecimal feePercent, BigDecimal feeFixed, AttemptState state, Instant startedAt, Instant sentAt, Instant unresolvedAt,
                       UUID confirmationId, String transactionKey, String operatorKey) {

        boolean card() {
            return "CARD".equals(channel);
        }
    }

    private static final String SELECT = """
            SELECT id, reference_no, order_id, keycloak_id, channel, method, amount, currency, fee_percent, fee_fixed, status, failure_code, unresolved_reason,
                   holds_order, started_at, sent_at, unresolved_at, confirmation_id, provider_transaction_key, operator_reference_key
              FROM provider_payment WHERE id = ?""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PaymentProvider provider;
    private final ZynlePaySetup setup;
    private final ProviderConfirmations confirmations;
    private final ProviderPaymentEvents events;

    public ProviderPaymentChecks(JdbcTemplate jdbc, TransactionTemplate tx, PaymentProvider provider, ZynlePaySetup setup,
                                 ProviderConfirmations confirmations, ProviderPaymentEvents events) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.provider = provider;
        this.setup = setup;
        this.confirmations = confirmations;
        this.events = events;
    }

    // ---------------------------------------------------------------- one pass of the checker

    /**
     * One pass: attempts that never got as far as being sent are failed as never sent; then the attempts that are due are asked about, at most
     * {@code poll.batch} of them, the longest-waiting first.
     *
     * @return how many attempts ZynlePay was asked about
     */
    public int poll(Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        sweepNeverSent(at);
        List<UUID> due = tx.execute(status -> {
            List<UUID> ids = jdbc.query("""
                    SELECT id FROM provider_payment
                     WHERE ((next_check_at IS NOT NULL AND next_check_at <= ?) OR (final_recheck_at IS NOT NULL AND final_recheck_at <= ?))
                       AND (check_lease_until IS NULL OR check_lease_until <= ?)
                     ORDER BY COALESCE(next_check_at, final_recheck_at), id
                     LIMIT ? FOR UPDATE SKIP LOCKED""", (rs, n) -> rs.getObject("id", UUID.class), Timestamp.from(at), Timestamp.from(at), Timestamp.from(at),
                    setup.settings().poll().batch());
            for (UUID id : ids) {
                // Its own for the length of the lease: a pass that dies leaves it to the next one. The one more question a failed attempt is owed
                // is NOT used up here: only an answer uses it up (see noFinalAnswer and failed).
                jdbc.update("UPDATE provider_payment SET check_lease_until = ? WHERE id = ?",
                        Timestamp.from(at.plus(setup.settings().poll().lease())), id);
            }
            return ids;
        });
        int asked = 0;
        for (UUID id : due) {
            try {
                if (ask(id, at, "POLL", ProviderPaymentService.ACTOR) == Result.ASKED) {
                    asked++;
                }
            } catch (RuntimeException e) {
                // One attempt's trouble never stops the pass; its lease runs out and it is asked about again.
                // The kind of failure only: a database message can carry a row's values, and a row holds a payer's number.
                log.warn("Provider payment {} could not be checked this pass ({})", id, e.getClass().getSimpleName());
            }
        }
        return asked;
    }

    /**
     * Asks about one attempt now, for a person: a member of staff's "check now", or the owner's resolve.
     *
     * @param source  {@code STAFF} or {@code RESOLVE}
     * @param askedBy {@code user:<subject>}
     */
    public Result checkOne(UUID id, Instant now, String source, String askedBy) {
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        Optional<Row> found = find(id);
        if (found.isEmpty() || !found.get().state().mayBeAsked()) {
            return Result.NOT_ASKABLE;
        }
        int mine = jdbc.update("UPDATE provider_payment SET check_lease_until = ? WHERE id = ? AND (check_lease_until IS NULL OR check_lease_until <= ?)",
                Timestamp.from(at.plus(setup.settings().poll().lease())), id, Timestamp.from(at));
        if (mine == 0) {
            return Result.BUSY;
        }
        return ask(id, at, source, askedBy);
    }

    /**
     * ZynlePay's callback named this reference. The callback is not signed, so this changes nothing about the payment: it only brings forward the
     * moment ZynlePay is asked, never to less than {@code callback-min-gap} after it was last asked, and never later than it was already due.
     *
     * <p>Only a payment there is something to ask about: one in flight, or one ZynlePay itself said failed or refused not long ago (it may
     * contradict itself). Never one that is recorded, was never sent, or was settled by the owner.
     *
     * @return whether there was such a payment
     */
    public boolean nudge(String referenceNo, Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        ZynlePaySetup zynlePay = setup;
        return jdbc.update("""
                UPDATE provider_payment
                   SET next_check_at = LEAST(COALESCE(next_check_at, 'infinity'::timestamptz),
                                             GREATEST(?::timestamptz, COALESCE(last_checked_at, '-infinity'::timestamptz) + (? * interval '1 second'))),
                       callback_count = callback_count + 1,
                       last_callback_at = ?
                 WHERE reference_no = ?
                   AND (status = 'PENDING'
                        OR (status = 'UNRESOLVED' AND unresolved_reason IN ('NO_ANSWER', 'AMOUNT_DIFFERS'))
                        OR (status = 'FAILED' AND failure_code IN ('PROVIDER_REFUSED', 'DECLINED') AND sent_at IS NOT NULL AND failed_at > ?))""",
                Timestamp.from(at), zynlePay.settings().poll().callbackMinGap().toSeconds(), Timestamp.from(at), referenceNo,
                Timestamp.from(at.minus(zynlePay.settings().poll().lateCheckWindow()))) > 0;
    }

    // ---------------------------------------------------------------- asking, and applying the answer

    /** The caller holds the attempt's check lease. */
    private Result ask(UUID id, Instant now, String source, String askedBy) {
        Row before = find(id).orElseThrow();
        if (!before.state().mayBeAsked()) {
            jdbc.update("UPDATE provider_payment SET check_lease_until = NULL, next_check_at = NULL, final_recheck_at = NULL WHERE id = ?", id);
            return Result.NOT_ASKABLE;
        }
        PaymentProvider.Status answer = provider.status(before.referenceNo());
        tx.executeWithoutResult(status -> {
            // The one order of locks: the order's money lock, then the attempt.
            ProviderConfirmations.lockOrder(jdbc, before.orderId());
            Row row = jdbc.query(SELECT + " FOR UPDATE", this::row, id).getFirst();
            String outcome;
            String code;
            BigDecimal reportedAmount = null;
            String reportedCurrency = null;
            Instant reportedPaidAt = null;
            switch (answer) {
                case Paid paid -> {
                    outcome = "PAID";
                    code = paid.code();
                    reportedAmount = paid.amount();
                    reportedCurrency = paid.currency();
                    reportedPaidAt = paid.paidAt();
                    paid(row, paid, now);
                }
                case Failed failed -> {
                    outcome = "FAILED";
                    code = failed.code();
                    failed(row, now);
                }
                case Pending pending -> {
                    outcome = "PENDING";
                    code = pending.code();
                    noFinalAnswer(row, now, true);
                }
                case NotFound notFound -> {
                    // It proves nothing about a request that may still be on its way: no final answer.
                    outcome = "NOT_FOUND";
                    code = notFound.code();
                    noFinalAnswer(row, now, false);
                }
                case StatusRefused refused -> {
                    outcome = "CREDENTIALS";
                    code = refused.code();
                    log.error("ZynlePay refused Oriah itself (code {}) when asked about provider payment {}", code, id);
                    noFinalAnswer(row, now, false);
                }
                case Unreadable unreadable -> {
                    outcome = "UNREADABLE";
                    code = unreadable.code();
                    noFinalAnswer(row, now, false);
                }
                case NoAnswer none -> {
                    outcome = "NO_ANSWER";
                    code = null;
                    noFinalAnswer(row, now, false);
                }
            }
            jdbc.update("UPDATE provider_payment SET last_checked_at = ?, check_count = check_count + 1, check_lease_until = NULL, "
                    + "provider_code = COALESCE(?, provider_code) WHERE id = ?", Timestamp.from(now), cut(code, 8), id);
            jdbc.update("""
                    INSERT INTO provider_payment_check (payment_id, source, asked_by, asked_at, answered_at, outcome, provider_code, reported_amount,
                                                        reported_currency, reported_paid_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, source, askedBy, Timestamp.from(now), Timestamp.from(now), outcome, cut(code, 8),
                    reportedAmount, cut(reportedCurrency, 8), reportedPaidAt == null ? null : Timestamp.from(reportedPaidAt));
        });
        return Result.ASKED;
    }

    private void paid(Row row, Paid paid, Instant now) {
        String transactionKey = Optional.ofNullable(ProviderPaymentService.referenceKey(paid.transactionId())).orElse(row.transactionKey());
        String operatorKey = Optional.ofNullable(ProviderPaymentService.referenceKey(paid.operatorReference())).orElse(row.operatorKey());
        if (paid.transactionId() != null || paid.operatorReference() != null) {
            jdbc.update("""
                    UPDATE provider_payment SET provider_transaction_id = COALESCE(?, provider_transaction_id), provider_transaction_key = ?,
                           operator_reference = COALESCE(?, operator_reference), operator_reference_key = ?, operator = COALESCE(?, operator) WHERE id = ?""",
                    cut(paid.transactionId(), 128), transactionKey, cut(paid.operatorReference(), 128), operatorKey, cut(paid.operator(), 16), row.id());
        }
        if (row.state().status() == Status.FAILED) {
            // ZynlePay contradicts itself. The machine's word was taken once: this goes to the owner, and another attempt may hold the order by now.
            move(row, row.state().paidAfterFailed(), now, "next_check_at = NULL, final_recheck_at = NULL");
            log.error("Provider payment {} had FAILED and ZynlePay now says it was paid: it is the owner's to decide (nothing was recorded)", row.id());
            return;
        }
        boolean anotherAmount = (paid.amount() != null && paid.amount().compareTo(row.amount()) != 0)
                || (paid.currency() != null && !paid.currency().equalsIgnoreCase(row.currency()));
        if (anotherAmount) {
            if (row.state().unresolvedReason() != UnresolvedReason.AMOUNT_DIFFERS) {
                move(row, row.state().unresolved(UnresolvedReason.AMOUNT_DIFFERS), now, "next_check_at = NULL");
                log.warn("Provider payment {}: ZynlePay says paid, for another amount or currency than was asked; it is the owner's to decide", row.id());
            } else {
                jdbc.update("UPDATE provider_payment SET next_check_at = NULL WHERE id = ?", row.id());
            }
            return;
        }
        // The day the money is dated: when ZynlePay says it was paid if that can be right, otherwise when Oriah saw it paid.
        Instant paidAt = paid.paidAt();
        boolean believed = paidAt != null && !paidAt.isBefore(row.sentAt().minus(BEFORE_SENT)) && !paidAt.isAfter(now.plus(AHEAD_OF_NOW));
        Instant arrivedAt = believed ? paidAt.truncatedTo(ChronoUnit.MICROS) : now;
        FeeTariff tariff = new FeeTariff(row.feePercent(), row.feeFixed());
        BigDecimal fee = tariff.fee(row.amount());
        int ordinal = 1 + jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE provider_payment_id = ?", Integer.class, row.id());
        ProviderConfirmations.Result result = confirmations.record(new ProviderMoney(row.id(), row.orderId(), row.method(), row.referenceNo(), row.amount(),
                fee, row.currency(), arrivedAt, null, ordinal, operatorKey, transactionKey), ProviderPaymentService.ACTOR, null);
        switch (result) {
            case Recorded recorded -> {
                AttemptState verified = row.state().verified(false);
                jdbc.update("""
                        UPDATE provider_payment
                           SET status = 'VERIFIED', unresolved_reason = NULL, holds_order = false, confirmation_id = ?, fee = ?, fee_basis = ?, verified_at = ?,
                               provider_paid_at = ?, next_check_at = NULL, final_recheck_at = NULL, version = version + 1
                         WHERE id = ?""", recorded.confirmationId(), fee, tariff.basis(), Timestamp.from(now), believed ? Timestamp.from(arrivedAt) : null,
                        row.id());
                announce(row, verified, recorded.confirmationId(), now);
                log.info("Provider payment {} VERIFIED: {} {} for order {}, recorded as confirmation {}", row.id(), row.amount().toPlainString(),
                        row.currency(), row.orderId(), recorded.confirmationId());
            }
            case SameMoney same -> {
                move(row, row.state().unresolved(UnresolvedReason.SAME_MONEY_ON_RECORD), now, "next_check_at = NULL");
                log.error("Provider payment {}: ZynlePay says paid, and confirmation {} already records that money under the network's own reference; "
                        + "it is the owner's to decide (nothing was recorded twice)", row.id(), same.confirmationId());
            }
        }
    }

    private void failed(Row row, Instant now) {
        if (row.state().status() == Status.FAILED) {
            // The one more question (or a callback's) said the same: it is simply done, and not asked about again by itself.
            jdbc.update("UPDATE provider_payment SET next_check_at = NULL, final_recheck_at = NULL WHERE id = ?", row.id());
            return;
        }
        AttemptState declined = row.state().failed(FailureCode.DECLINED);
        jdbc.update("""
                UPDATE provider_payment
                   SET status = 'FAILED', failure_code = 'DECLINED', unresolved_reason = NULL, holds_order = false, failed_at = ?, next_check_at = NULL,
                       final_recheck_at = ?, version = version + 1
                 WHERE id = ?""", Timestamp.from(now), Timestamp.from(setup.schedule().finalRecheck(now)), row.id());
        announce(row, declined, null, now);
        log.info("Provider payment {} FAILED: ZynlePay says it was declined", row.id());
    }

    /** No final answer: only when it is asked again changes; after {@code give-up-after} it becomes the owner's, and is never assumed failed. */
    private void noFinalAnswer(Row row, Instant now, boolean zynlePaySaidPending) {
        switch (row.state().status()) {
            case PENDING -> {
                if (setup.schedule().givenUp(row.sentAt(), now)) {
                    AttemptState unresolved = row.state().unresolved(UnresolvedReason.NO_ANSWER);
                    jdbc.update("UPDATE provider_payment SET status = 'UNRESOLVED', unresolved_reason = 'NO_ANSWER', unresolved_at = ?, next_check_at = ?, "
                            + "version = version + 1 WHERE id = ?", Timestamp.from(now), stamp(setup.schedule().afterUnresolved(now, now)), row.id());
                    announce(row, unresolved, null, now);
                    log.warn("Provider payment {} has had no answer from ZynlePay since {}: it is the owner's to decide, and is still asked about", row.id(),
                            row.sentAt());
                } else {
                    jdbc.update("UPDATE provider_payment SET next_check_at = ? WHERE id = ?",
                            Timestamp.from(setup.schedule().afterNoNews(row.card(), row.sentAt(), now, zynlePaySaidPending)), row.id());
                }
            }
            case UNRESOLVED -> jdbc.update("UPDATE provider_payment SET next_check_at = ? WHERE id = ?",
                    stamp(setup.schedule().afterUnresolved(row.unresolvedAt() == null ? now : row.unresolvedAt(), now)), row.id());
            // A failed attempt was asked about and ZynlePay gave no final answer. A question a callback brought is simply over. The ONE MORE
            // question a failed attempt is owed is not lost to a silent ZynlePay: it is asked again later, while the attempt is recent enough.
            default -> jdbc.update("""
                    UPDATE provider_payment
                       SET next_check_at = NULL,
                           final_recheck_at = CASE WHEN final_recheck_at IS NOT NULL AND failed_at > ? THEN ?::timestamptz ELSE NULL END
                     WHERE id = ?""", Timestamp.from(now.minus(setup.settings().poll().lateCheckWindow())),
                    Timestamp.from(setup.schedule().finalRecheck(now)), row.id());
        }
    }

    /** A move to UNRESOLVED, announced; {@code alsoSet} is the bookkeeping that goes with it. */
    private void move(Row row, AttemptState to, Instant now, String alsoSet) {
        jdbc.update("UPDATE provider_payment SET status = ?, failure_code = NULL, unresolved_reason = ?, holds_order = ?, unresolved_at = ?, " + alsoSet
                + ", version = version + 1 WHERE id = ?", to.status().name(), to.unresolvedReason().name(), to.holdsOrder(), Timestamp.from(now), row.id());
        announce(row, to, null, now);
    }

    private void announce(Row row, AttemptState to, UUID confirmationId, Instant now) {
        events.updated(row.id(), row.orderId(), row.keycloakId(), row.channel(), row.method(), to.status().name(),
                to.failureCode() == null ? null : to.failureCode().name(), to.unresolvedReason() == null ? null : to.unresolvedReason().name(), row.amount(),
                row.currency(), confirmationId, row.startedAt(), now, ProviderPaymentService.ACTOR);
    }

    // ---------------------------------------------------------------- attempts that never left

    /** An attempt still STARTING after {@code starting-grace} was never sent (the start died, or the order could not be asked): it is over. */
    private void sweepNeverSent(Instant now) {
        tx.executeWithoutResult(status -> {
            List<UUID> stale = jdbc.query("SELECT id FROM provider_payment WHERE status = 'STARTING' AND started_at < ? ORDER BY started_at LIMIT 50 "
                    + "FOR UPDATE SKIP LOCKED", (rs, n) -> rs.getObject("id", UUID.class), Timestamp.from(now.minus(setup.settings().poll().startingGrace())));
            for (UUID id : stale) {
                Row row = find(id).orElseThrow();
                AttemptState neverSent = row.state().failed(FailureCode.NOT_SENT);
                jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'NOT_SENT', holds_order = false, failed_at = ?, "
                        + "version = version + 1 WHERE id = ?", Timestamp.from(now), id);
                announce(row, neverSent, null, now);
                log.info("Provider payment {} was never sent: FAILED", id);
            }
        });
    }

    // ---------------------------------------------------------------- plumbing

    private Optional<Row> find(UUID id) {
        return jdbc.query(SELECT, this::row, id).stream().findFirst();
    }

    private Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        String failure = rs.getString("failure_code");
        String reason = rs.getString("unresolved_reason");
        Timestamp sentAt = rs.getTimestamp("sent_at");
        Timestamp unresolvedAt = rs.getTimestamp("unresolved_at");
        return new Row(rs.getObject("id", UUID.class), rs.getString("reference_no"), rs.getObject("order_id", UUID.class), rs.getString("keycloak_id"),
                rs.getString("channel"), rs.getString("method"), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getBigDecimal("fee_percent"),
                rs.getBigDecimal("fee_fixed"), new AttemptState(Status.valueOf(rs.getString("status")), failure == null ? null : FailureCode.valueOf(failure),
                reason == null ? null : UnresolvedReason.valueOf(reason), rs.getBoolean("holds_order")), rs.getTimestamp("started_at").toInstant(),
                sentAt == null ? null : sentAt.toInstant(), unresolvedAt == null ? null : unresolvedAt.toInstant(), rs.getObject("confirmation_id", UUID.class),
                rs.getString("provider_transaction_key"), rs.getString("operator_reference_key"));
    }

    private static Timestamp stamp(Optional<Instant> when) {
        return when.map(Timestamp::from).orElse(null);
    }

    private static String cut(String value, int most) {
        return value == null || value.length() <= most ? value : value.substring(0, most);
    }
}
