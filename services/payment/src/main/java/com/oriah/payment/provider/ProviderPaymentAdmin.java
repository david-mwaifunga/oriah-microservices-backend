package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oriah.payment.confirmation.ArrivalWindow;
import com.oriah.payment.confirmation.ProviderConfirmations;
import com.oriah.payment.confirmation.ProviderConfirmations.ProviderMoney;
import com.oriah.payment.confirmation.ProviderConfirmations.Recorded;
import com.oriah.payment.confirmation.ProviderConfirmations.SameMoney;
import com.oriah.payment.confirmation.SalesSettings;
import com.oriah.payment.confirmation.Staff;
import com.oriah.payment.events.ProviderPaymentEvents;
import com.oriah.payment.exception.PaymentProblem;
import com.oriah.payment.provider.AttemptState.FailureCode;
import com.oriah.payment.provider.AttemptState.Status;
import com.oriah.payment.provider.AttemptState.UnresolvedReason;

import lombok.extern.slf4j.Slf4j;

/**
 * What staff and the owner do about payments through ZynlePay (Phase 13): see them, ask ZynlePay about one now, and (the owner alone) settle the
 * ones ZynlePay did not.
 *
 * <h2>The owner's decision</h2>
 * ZynlePay is asked once more first, through the same door as the checker, and its answer counts for more than a person's word: "paid" for the
 * amount asked is recorded as ZynlePay's; "failed" refuses the owner's "paid"; "still deciding" refuses "not paid" unless the owner insists.
 * Only three kinds of payment are decided without asking, because ZynlePay would only repeat the answer that is in doubt: one whose record the
 * owner cancelled, one ZynlePay called paid after it had failed, and one whose money staff already keyed.
 *
 * <p>"Paid" writes the same payment confirmation, under the owner's name. "Not paid" ends the attempt, and the order lets go. Either is written
 * down with the evidence ({@code provider_payment_resolution}), which is kept there and is in no event. The same key and decision is the same
 * answer.
 */
@Service
@Slf4j
public class ProviderPaymentAdmin {

    /** Kept on the money record when the owner dates it more than the usual window back. Never the evidence: this text is published. */
    static final String LATE = "recorded late by the owner from ZynlePay's records";

    /** One time ZynlePay was asked about a payment, and what it said. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckView(String source, String askedBy, Instant askedAt, String outcome, String providerCode, BigDecimal reportedAmount,
                            String reportedCurrency, Instant reportedPaidAt) {
    }

    /**
     * A payment as staff see it.
     *
     * @param payerPhone     masked, except for the owner, who may need the number to send a refund back to it
     * @param checkedJustNow on "check now" only: somebody else was already asking, so ZynlePay was not asked again
     * @param verifiedByCheck on a decision only: ZynlePay itself answered "paid" when it was asked once more, so the record is ZynlePay's
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminView(UUID id, UUID orderId, String channel, String method, String status, String failureCode, String unresolvedReason,
                            BigDecimal amount, String currency, String referenceNo, String providerTransactionId, String operatorReference,
                            String providerCode, String payerPhone, Instant startedAt, Instant sentAt, Instant lastCheckedAt, Instant nextCheckAt,
                            Integer checkCount, Integer callbackCount, Long ageSeconds, UUID confirmationId, BigDecimal fee, String feeBasis,
                            List<CheckView> checks, Boolean checkedJustNow, Boolean verifiedByCheck) {
    }

    /** The owner's decision, as sent. */
    public record Decision(String outcome, String evidence, Instant arrivedAt, BigDecimal gross, Boolean overridePending) {
    }

    private static final List<String> FILTERS = List.of("OPEN", "PENDING", "UNRESOLVED", "FAILED", "VERIFIED", "ALL");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ProviderPaymentChecks checks;
    private final ProviderConfirmations confirmations;
    private final ProviderPaymentEvents events;
    private final SalesSettings sales;
    private final Clock clock;

    public ProviderPaymentAdmin(JdbcTemplate jdbc, TransactionTemplate tx, ProviderPaymentChecks checks, ProviderConfirmations confirmations,
                                ProviderPaymentEvents events, SalesSettings sales, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.checks = checks;
        this.confirmations = confirmations;
        this.events = events;
        this.sales = sales;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- seeing

    /**
     * @param status OPEN (the default: not yet sent, in flight, or the owner's), PENDING, UNRESOLVED, FAILED, VERIFIED or ALL
     * @param owner  whether the caller is the owner: then the payer's number is shown whole
     */
    public List<AdminView> list(String status, UUID orderId, boolean owner, String actor) {
        String wanted = status == null ? "OPEN" : status.strip().toUpperCase(Locale.ROOT);
        if (!FILTERS.contains(wanted)) {
            throw new IllegalArgumentException("status must be one of " + String.join(", ", FILTERS) + ", was '" + status + "'");
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM provider_payment WHERE true");
        List<Object> args = new ArrayList<>();
        if (wanted.equals("OPEN")) {
            sql.append(" AND status IN ('STARTING', 'PENDING', 'UNRESOLVED')");
        } else if (!wanted.equals("ALL")) {
            sql.append(" AND status = ?");
            args.add(wanted);
        }
        if (orderId != null) {
            sql.append(" AND order_id = ?");
            args.add(orderId);
        }
        // What is open is worked oldest first; what is over is looked up newest first.
        sql.append(wanted.equals("OPEN") ? " ORDER BY started_at, id" : " ORDER BY started_at DESC, id").append(" LIMIT 200");
        Instant now = clock.instant();
        List<AdminView> views = jdbc.queryForList(sql.toString(), args.toArray()).stream().map(row -> view(row, owner, now, null, null, null)).toList();
        if (owner && !views.isEmpty()) {
            // Who read customers' numbers, and how many: never the numbers.
            log.info("{} read {} provider payments with the payers' numbers shown", actor, views.size());
        }
        return views;
    }

    /** @throws PaymentProblem 404 {@code provider-payment-not-found} */
    public AdminView get(UUID id, boolean owner, String actor) {
        Map<String, Object> row = row(id);
        if (owner) {
            log.info("{} read provider payment {} with the payer's number shown", actor, id);
        }
        return view(row, owner, clock.instant(), checksOf(id), null, null);
    }

    // ---------------------------------------------------------------- check now

    /**
     * Asks ZynlePay about one payment now, exactly as the checker does.
     *
     * @throws PaymentProblem 404 {@code provider-payment-not-found}; 409 {@code provider-payment-final}: it is over, was never sent, or is the
     *                        owner's alone to decide
     */
    public AdminView check(UUID id, boolean owner, String actor) {
        row(id);
        ProviderPaymentChecks.Result result = checks.checkOne(id, clock.instant(), "STAFF", actor);
        if (result == ProviderPaymentChecks.Result.NOT_ASKABLE) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "provider-payment-final",
                    "there is nothing to ask ZynlePay about this payment: it is recorded, was never sent, or is the owner's to decide");
        }
        return view(row(id), owner, clock.instant(), checksOf(id), result == ProviderPaymentChecks.Result.BUSY ? Boolean.TRUE : null, null);
    }

    // ---------------------------------------------------------------- the owner decides

    /**
     * @throws IllegalArgumentException 400 for a key, an outcome, evidence or a date that makes no sense
     * @throws org.springframework.security.access.AccessDeniedException 403 for a date further back than the caller may record
     * @throws PaymentProblem 400 {@code gross-not-on-record}; 404 {@code provider-payment-not-found}; 409 {@code not-resolvable},
     *                        {@code idempotency-key-reused}, {@code check-in-progress}, {@code provider-says-failed}, {@code provider-says-paid},
     *                        {@code provider-says-pending}, {@code same-money-on-record}, {@code currency-not-supported}; 503
     *                        {@code provider-unavailable}
     */
    public AdminView resolve(UUID id, String idempotencyKey, Decision decision, Staff owner) {
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 128 characters");
        }
        String outcome = decision.outcome() == null ? "" : decision.outcome().strip().toUpperCase(Locale.ROOT);
        if (!outcome.equals("PAID") && !outcome.equals("NOT_PAID")) {
            throw new IllegalArgumentException("outcome must be PAID or NOT_PAID, was '" + decision.outcome() + "'");
        }
        boolean paid = outcome.equals("PAID");
        String evidence = decision.evidence() == null ? "" : decision.evidence().strip();
        if (evidence.codePointCount(0, evidence.length()) < 10 || evidence.codePointCount(0, evidence.length()) > 200) {
            throw new IllegalArgumentException("a decision needs evidence of 10 to 200 characters: what was looked at in ZynlePay's own records");
        }
        boolean insists = Boolean.TRUE.equals(decision.overridePending());
        String fingerprint = fingerprint(id, outcome, evidence, decision.gross(), decision.arrivedAt(), insists);

        if (repeated(id, key, fingerprint)) {
            return view(row(id), true, clock.instant(), checksOf(id), null, false);
        }
        AttemptState before = state(row(id));
        requireResolvable(before, paid);

        // ZynlePay is asked once more, unless it would only repeat the answer that is in doubt.
        String checkOutcome = null;
        if (before.mayBeAsked()) {
            if (checks.checkOne(id, clock.instant(), "RESOLVE", owner.actor()) == ProviderPaymentChecks.Result.BUSY) {
                throw new PaymentProblem(HttpStatus.CONFLICT, "check-in-progress", "ZynlePay is being asked about this payment right now: try again in a moment");
            }
            checkOutcome = jdbc.query("SELECT outcome FROM provider_payment_check WHERE payment_id = ? ORDER BY id DESC LIMIT 1", (rs, n) -> rs.getString(1), id)
                    .stream().findFirst().orElse(null);
            AttemptState after = state(row(id));
            if (after.status() == Status.VERIFIED) {
                if (!paid) {
                    throw new PaymentProblem(HttpStatus.CONFLICT, "provider-says-paid",
                            "ZynlePay, asked just now, says this payment was paid: it has been recorded, and cannot be marked not paid");
                }
                final String byCheck = checkOutcome;
                tx.executeWithoutResult(s -> written(id, key, fingerprint, outcome, evidence, null, null, insists, byCheck, owner, "VERIFIED",
                        (UUID) row(id).get("confirmation_id")));
                return view(row(id), true, clock.instant(), checksOf(id), null, true);
            }
            if ("FAILED".equals(checkOutcome)) {
                if (paid) {
                    throw new PaymentProblem(HttpStatus.CONFLICT, "provider-says-failed",
                            "ZynlePay, asked just now, says this payment failed: it cannot be marked paid on that answer");
                }
                // "Not paid", and ZynlePay says the same: the checker's own rule has ended it.
                final String byCheck = checkOutcome;
                tx.executeWithoutResult(s -> written(id, key, fingerprint, outcome, evidence, null, null, insists, byCheck, owner, "FAILED", null));
                return view(row(id), true, clock.instant(), checksOf(id), null, false);
            }
            if (!paid) {
                if ("NO_ANSWER".equals(checkOutcome) || "CREDENTIALS".equals(checkOutcome)) {
                    throw new PaymentProblem(HttpStatus.SERVICE_UNAVAILABLE, "provider-unavailable",
                            "ZynlePay could not be asked just now, and a payment is not marked not paid without asking: try again shortly")
                            .with("retryAfterSeconds", 30);
                }
                if (!insists && !"PAID".equals(checkOutcome)) {
                    throw new PaymentProblem(HttpStatus.CONFLICT, "provider-says-pending",
                            "ZynlePay, asked just now, has not said this payment failed. If its own records show that no money came, send the decision "
                                    + "again with overridePending true");
                }
            }
        }

        final String asked = checkOutcome;
        tx.executeWithoutResult(s -> decide(id, key, fingerprint, paid, evidence, decision, insists, asked, owner));
        return view(row(id), true, clock.instant(), checksOf(id), null, false);
    }

    /** The decision, applied under the order's lock and the attempt's row lock, with what it is written down as. */
    private void decide(UUID id, String key, String fingerprint, boolean paid, String evidence, Decision decision, boolean insists, String checkOutcome,
                        Staff owner) {
        Map<String, Object> first = row(id);
        ProviderConfirmations.lockOrder(jdbc, (UUID) first.get("order_id"));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM provider_payment WHERE id = ? FOR UPDATE", id);
        AttemptState state = state(row);
        requireResolvable(state, paid);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID orderId = (UUID) row.get("order_id");
        BigDecimal amount = (BigDecimal) row.get("amount");

        if (!paid) {
            AttemptState notPaid = state.failed(FailureCode.MARKED_NOT_PAID);
            jdbc.update("""
                    UPDATE provider_payment
                       SET status = 'FAILED', failure_code = 'MARKED_NOT_PAID', unresolved_reason = NULL, holds_order = false, failed_at = ?,
                           next_check_at = NULL, final_recheck_at = NULL, version = version + 1
                     WHERE id = ?""", Timestamp.from(now), id);
            announce(row, notPaid, null, now, owner.actor());
            written(id, key, fingerprint, "NOT_PAID", evidence, null, null, insists, checkOutcome, owner, "FAILED", null);
            log.info("Provider payment {} marked NOT PAID by {}", id, owner.actor());
            return;
        }

        // The amount: what was asked, unless ZynlePay itself reported another one, and then only a figure ZynlePay reported.
        boolean anotherFigureReported = state.unresolvedReason() == UnresolvedReason.AMOUNT_DIFFERS
                || state.unresolvedReason() == UnresolvedReason.PAID_AFTER_FAILED;
        BigDecimal gross = amount;
        if (decision.gross() != null) {
            List<BigDecimal> reported = jdbc.queryForList("SELECT DISTINCT reported_amount FROM provider_payment_check WHERE payment_id = ? "
                    + "AND reported_amount IS NOT NULL", BigDecimal.class, id);
            boolean onRecord = decision.gross().compareTo(amount) == 0 || reported.stream().anyMatch(r -> r.compareTo(decision.gross()) == 0);
            if (!anotherFigureReported || !onRecord) {
                throw new PaymentProblem(HttpStatus.BAD_REQUEST, "gross-not-on-record",
                        "an amount is given only where ZynlePay reported another amount than was asked, and it is the amount asked or the one ZynlePay "
                                + "reported: no other figure is recorded");
            }
            gross = decision.gross().setScale(2, java.math.RoundingMode.UNNECESSARY);
        }
        List<String> currencies = jdbc.queryForList("SELECT reported_currency FROM provider_payment_check WHERE payment_id = ? AND reported_currency IS NOT NULL "
                + "ORDER BY id DESC LIMIT 1", String.class, id);
        if (!currencies.isEmpty() && !currencies.getFirst().equalsIgnoreCase((String) row.get("currency"))) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "currency-not-supported", "ZynlePay reported this payment in " + currencies.getFirst()
                    + ": it cannot be recorded here. Settle it with ZynlePay, then mark it not paid");
        }
        Instant startedAt = ((Timestamp) row.get("started_at")).toInstant();
        Instant arrivedAt = (decision.arrivedAt() == null ? now : decision.arrivedAt()).truncatedTo(ChronoUnit.MICROS);
        if (arrivedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("the money cannot have arrived before the payment was started (" + startedAt + ")");
        }
        String backdateReason = ArrivalWindow.check(arrivedAt, now, sales.backdateWindow(), owner.mayBackdate(), LATE);

        AttemptState resolvable = state;
        if (state.status() == Status.FAILED) {
            // A failed payment the owner knows was paid: the owner's to decide first, then decided.
            resolvable = state.paidAfterFailed();
            jdbc.update("UPDATE provider_payment SET status = 'UNRESOLVED', failure_code = NULL, unresolved_reason = 'PAID_AFTER_FAILED', unresolved_at = ?, "
                    + "version = version + 1 WHERE id = ?", Timestamp.from(now), id);
        }
        AttemptState verified = resolvable.verified(true);
        FeeTariff tariff = new FeeTariff((BigDecimal) row.get("fee_percent"), (BigDecimal) row.get("fee_fixed"));
        BigDecimal fee = tariff.fee(gross);
        int ordinal = 1 + jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE provider_payment_id = ?", Integer.class, id);
        ProviderConfirmations.Result result = confirmations.record(new ProviderMoney(id, orderId, (String) row.get("method"), (String) row.get("reference_no"),
                gross, fee, (String) row.get("currency"), arrivedAt, backdateReason, ordinal, (String) row.get("operator_reference_key"),
                (String) row.get("provider_transaction_key")), owner.actor(), owner.name());
        switch (result) {
            case SameMoney same -> throw new PaymentProblem(HttpStatus.CONFLICT, "same-money-on-record",
                    "this money is already recorded, under the network's own reference, as confirmation " + same.confirmationId()
                            + ": mark this payment not paid, or cancel that record first if it is the wrong one").with("confirmationId", same.confirmationId());
            case Recorded recorded -> {
                jdbc.update("""
                        UPDATE provider_payment
                           SET status = 'VERIFIED', unresolved_reason = NULL, holds_order = false, confirmation_id = ?, fee = ?, fee_basis = ?, verified_at = ?,
                               next_check_at = NULL, final_recheck_at = NULL, version = version + 1
                         WHERE id = ?""", recorded.confirmationId(), fee, tariff.basis(), Timestamp.from(now), id);
                announce(row, verified, recorded.confirmationId(), now, owner.actor());
                written(id, key, fingerprint, "PAID", evidence, decision.gross() == null ? null : gross, arrivedAt, insists, checkOutcome, owner, "VERIFIED",
                        recorded.confirmationId());
                log.info("Provider payment {} marked PAID by {}: {} recorded as confirmation {}", id, owner.actor(), gross.toPlainString(),
                        recorded.confirmationId());
            }
        }
    }

    /** UNRESOLVED is decided either way; a FAILED payment that reached ZynlePay may be marked paid; nothing else is the owner's to decide. */
    private static void requireResolvable(AttemptState state, boolean paid) {
        boolean resolvable = state.status() == Status.UNRESOLVED || (paid && state.status() == Status.FAILED && state.mayBeAsked());
        if (!resolvable) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "not-resolvable", "this payment is " + state.status()
                    + (state.failureCode() == null ? "" : " (" + state.failureCode() + ")") + ": "
                    + (paid ? "only one ZynlePay has not settled, or one that failed after reaching ZynlePay, is marked paid by the owner"
                    : "only one ZynlePay has not settled is marked not paid by the owner"));
        }
    }

    private void written(UUID id, String key, String fingerprint, String outcome, String evidence, BigDecimal gross, Instant arrivedAt, boolean insists,
                         String checkOutcome, Staff owner, String resultingStatus, UUID confirmationId) {
        jdbc.update("""
                INSERT INTO provider_payment_resolution (id, payment_id, idempotency_key, request_fingerprint, outcome, evidence, gross, arrived_at,
                                                         override_pending, check_outcome, decided_by, decided_by_name, decided_at, resulting_status,
                                                         confirmation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), id, key, fingerprint, outcome, evidence, gross,
                arrivedAt == null ? null : Timestamp.from(arrivedAt), insists, checkOutcome, owner.actor(), owner.name(),
                Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)), resultingStatus, confirmationId);
    }

    /** Whether this key already made this very decision; a key used for another is refused. */
    private boolean repeated(UUID id, String key, String fingerprint) {
        List<Map<String, Object>> prior = jdbc.queryForList("SELECT payment_id, request_fingerprint FROM provider_payment_resolution WHERE idempotency_key = ?",
                key);
        if (prior.isEmpty()) {
            return false;
        }
        if (!id.equals(prior.getFirst().get("payment_id")) || !fingerprint.equals(prior.getFirst().get("request_fingerprint"))) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "idempotency-key-reused", "this Idempotency-Key was used for a different decision: send a new key");
        }
        return true;
    }

    private void announce(Map<String, Object> row, AttemptState to, UUID confirmationId, Instant now, String actor) {
        events.updated((UUID) row.get("id"), (UUID) row.get("order_id"), (String) row.get("keycloak_id"), (String) row.get("channel"),
                (String) row.get("method"), to.status().name(), to.failureCode() == null ? null : to.failureCode().name(),
                to.unresolvedReason() == null ? null : to.unresolvedReason().name(), (BigDecimal) row.get("amount"), (String) row.get("currency"),
                confirmationId, ((Timestamp) row.get("started_at")).toInstant(), now, actor);
    }

    // ---------------------------------------------------------------- plumbing

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForList("SELECT * FROM provider_payment WHERE id = ?", id).stream().findFirst()
                .orElseThrow(() -> new PaymentProblem(HttpStatus.NOT_FOUND, "provider-payment-not-found", "there is no provider payment " + id));
    }

    private static AttemptState state(Map<String, Object> row) {
        String failure = (String) row.get("failure_code");
        String reason = (String) row.get("unresolved_reason");
        return new AttemptState(Status.valueOf((String) row.get("status")), failure == null ? null : FailureCode.valueOf(failure),
                reason == null ? null : UnresolvedReason.valueOf(reason), (Boolean) row.get("holds_order"));
    }

    private List<CheckView> checksOf(UUID id) {
        return jdbc.query("SELECT source, asked_by, asked_at, outcome, provider_code, reported_amount, reported_currency, reported_paid_at "
                + "FROM provider_payment_check WHERE payment_id = ? ORDER BY id", (rs, n) -> new CheckView(rs.getString("source"), rs.getString("asked_by"),
                rs.getTimestamp("asked_at").toInstant(), rs.getString("outcome"), rs.getString("provider_code"), rs.getBigDecimal("reported_amount"),
                rs.getString("reported_currency"), instant(rs.getTimestamp("reported_paid_at"))), id);
    }

    private static AdminView view(Map<String, Object> row, boolean owner, Instant now, List<CheckView> checks, Boolean checkedJustNow, Boolean verifiedByCheck) {
        String msisdn = (String) row.get("payer_msisdn");
        Instant startedAt = instant(row.get("started_at"));
        return new AdminView((UUID) row.get("id"), (UUID) row.get("order_id"), (String) row.get("channel"), (String) row.get("method"),
                (String) row.get("status"), (String) row.get("failure_code"), (String) row.get("unresolved_reason"), (BigDecimal) row.get("amount"),
                (String) row.get("currency"), (String) row.get("reference_no"), (String) row.get("provider_transaction_id"),
                (String) row.get("operator_reference"), (String) row.get("provider_code"), owner ? msisdn : Wallets.masked(msisdn), startedAt,
                instant(row.get("sent_at")), instant(row.get("last_checked_at")), instant(row.get("next_check_at")), (Integer) row.get("check_count"),
                (Integer) row.get("callback_count"), Math.max(0, Duration.between(startedAt, now).toSeconds()), (UUID) row.get("confirmation_id"),
                (BigDecimal) row.get("fee"), (String) row.get("fee_basis"), checks, checkedJustNow, verifiedByCheck);
    }

    private static Instant instant(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    private static String fingerprint(UUID id, String outcome, String evidence, BigDecimal gross, Instant arrivedAt, boolean insists) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((id + "|" + outcome + "|" + evidence + "|"
                    + (gross == null ? "" : gross.stripTrailingZeros().toPlainString()) + "|" + (arrivedAt == null ? "" : arrivedAt) + "|" + insists)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
