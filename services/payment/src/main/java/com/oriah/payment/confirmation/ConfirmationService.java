package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.money.Ngwee;
import com.oriah.payment.dto.ConfirmPaymentRequest;
import com.oriah.payment.events.ConfirmationEvents;
import com.oriah.payment.exception.PaymentProblem;

import lombok.extern.slf4j.Slf4j;

/**
 * Records the money staff confirm (Phase 7): the only road by which an order becomes paid.
 *
 * <h2>A record, and exactly once</h2>
 *
 * A confirmation says what reached Oriah (method, reference, gross, fee) and WHEN (the day the money arrived, not the day it was keyed), and
 * who recorded it. It is announced as {@code payment.confirmed} in the transaction that records it. It is never edited: what changes later is
 * only what became of the money (the outcome), set once when order answers.
 *
 * <p>Two things make a repeat harmless. The {@code Idempotency-Key}: the same key and the same request is the same confirmation (the first
 * answer is returned again), the same key with another request is refused. And the reference: one (method, reference) pair confirms money once,
 * whoever keys it and for whichever order, because the same transfer would otherwise be counted twice; cash has no such key. Payment never reads
 * an order: money for an unknown, cancelled or already paid order is recorded all the same and becomes held money, so nothing a customer paid is
 * ever lost to a keying mistake.
 */
@Service
@Slf4j
public class ConfirmationService {

    /** The confirmation, and whether this call made it (false: it was a repeat of an earlier call with the same Idempotency-Key). */
    public record Confirmed(ConfirmationView view, boolean created) {
    }

    static final String SELECT = """
            SELECT c.id, c.order_id, c.method, c.reference, c.gross, c.fee, c.net, c.currency, c.arrived_at, c.backdate_reason, c.confirmed_by,
                   c.confirmed_by_name, c.confirmed_at, c.outcome, c.outcome_reason, c.outcome_at, c.emit_attempts,
                   v.voided_at, v.reason AS void_reason, v.voided_by
              FROM payment_confirmation c
              LEFT JOIN payment_confirmation_void v ON v.confirmation_id = c.id""";

    private final JdbcTemplate jdbc;
    private final ConfirmationSettings settings;
    private final Clock clock;
    private final ConfirmationEvents events;
    private final SalesSettings sales;
    private final com.oriah.payment.events.ProviderPaymentEvents providerPaymentEvents;

    public ConfirmationService(JdbcTemplate jdbc, ConfirmationSettings settings, Clock clock, ConfirmationEvents events, SalesSettings sales,
                               com.oriah.payment.events.ProviderPaymentEvents providerPaymentEvents) {
        this.providerPaymentEvents = providerPaymentEvents;
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
        this.sales = sales;
    }

    /**
     * @throws IllegalArgumentException (400) for anything that makes no sense: a method, a reference, an amount, a fee over the amount, an arrival
     *                                  in the future, money older than 72 hours with no reason
     * @throws org.springframework.security.access.AccessDeniedException (403) for money older than 72 hours by someone who may not back-date
     * @throws PaymentProblem 409 {@code reference-already-used}, 409 {@code idempotency-key-reused}, 400 {@code unsupported-currency}
     */
    @Transactional
    public Confirmed confirm(ConfirmPaymentRequest request, String idempotencyKey, Staff staff) {
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 128 characters");
        }
        if (key.startsWith(ProviderConfirmations.KEY_PREFIX)) {
            throw new IllegalArgumentException("Idempotency-Key cannot begin with '" + ProviderConfirmations.KEY_PREFIX + "': those keys are the system's own");
        }
        PaymentMethod method = PaymentMethod.parse(request.method());
        String reference = method.requiresReference() || (request.reference() != null && !request.reference().isBlank())
                ? ReferenceNumber.normalise(request.reference()) : null;
        BigDecimal gross = Ngwee.require(request.gross(), "gross");
        BigDecimal fee = request.fee() == null ? BigDecimal.ZERO.setScale(2) : Ngwee.require(request.fee(), "fee");
        if (fee.compareTo(gross) > 0) {
            throw new IllegalArgumentException("the fee " + fee.toPlainString() + " cannot be more than the amount " + gross.toPlainString());
        }
        String currency = request.currency() == null ? settings.currency() : request.currency();
        if (!currency.equals(settings.currency())) {
            throw new PaymentProblem(HttpStatus.BAD_REQUEST, "unsupported-currency",
                    "payments are in " + settings.currency() + ": " + currency + " cannot be confirmed");
        }
        String fingerprint = fingerprint(request.orderId(), method, reference, gross, fee, currency, request.arrivedAt(), request.backdateReason());

        // A repeat is answered first, before anything that depends on the time: a request sent again a day later is still the same request.
        Optional<UUID> prior = repeat(key, fingerprint);
        if (prior.isPresent()) {
            return new Confirmed(get(prior.get()), false);
        }

        // One writer at a time for an order's money, and the first lock taken (Phase 13): a payment through ZynlePay starting for this order at this
        // moment either sees this confirmation or is seen by it, never neither.
        ProviderConfirmations.lockOrder(jdbc, request.orderId());
        Optional<UUID> waitedForItself = repeat(key, fingerprint);
        if (waitedForItself.isPresent()) {
            return new Confirmed(get(waitedForItself.get()), false);
        }
        refuseWhileAProviderPaymentHoldsTheOrder(request.orderId());
        if (reference != null) {
            refuseAProvidersReference(reference);
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant arrivedAt = (request.arrivedAt() == null ? now : request.arrivedAt()).truncatedTo(ChronoUnit.MICROS);
        String backdateReason = ArrivalWindow.check(arrivedAt, now, sales.backdateWindow(), staff.mayBackdate(), request.backdateReason());

        String referenceKey = method.requiresReference() ? method.name() + ":" + reference : null;
        if (referenceKey != null) {
            // Taken one after the other with a rejection of a claim for the same reference (see ClaimService): never half of each.
            com.oriah.payment.claim.ClaimService.lockReference(jdbc, method.name(), reference);
            // Waiting for that lock can mean waiting for this very request sent a moment ago (a double click, a retry): it has committed by now, and
            // the reference it used is THIS request's, not someone else's. Under READ COMMITTED this statement sees it.
            Optional<UUID> sentTwice = repeat(key, fingerprint);
            if (sentTwice.isPresent()) {
                return new Confirmed(get(sentTwice.get()), false);
            }
            refuseIfUsed(referenceKey);
        }

        UUID id = UUID.randomUUID();
        // ON CONFLICT DO NOTHING: a unique violation would abort this transaction, and a race with the same key or the same reference is answered below.
        boolean inserted = !jdbc.query("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, backdate_reason,
                                                  confirmed_by, confirmed_by_name, confirmed_at, idempotency_key, request_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING id""", (rs, row) -> rs.getObject("id", UUID.class),
                id, request.orderId(), method.name(), reference, referenceKey, gross, fee, currency, Timestamp.from(arrivedAt), backdateReason,
                staff.actor(), staff.name(), Timestamp.from(now), key, fingerprint).isEmpty();
        if (!inserted) {
            Optional<UUID> raced = repeat(key, fingerprint);
            if (raced.isPresent()) {
                return new Confirmed(get(raced.get()), false);
            }
            if (referenceKey != null) {
                refuseIfUsed(referenceKey);
            }
            throw new IllegalStateException("the confirmation could not be recorded and nothing else claims its key or reference");
        }

        ConfirmationView view = get(id);
        events.confirmed(view.id(), view.orderId(), view.method(), view.reference(), view.gross(), view.fee(), view.net(), view.currency(), view.arrivedAt(),
                view.backdateReason(), view.confirmedBy(), view.confirmedAt(), 1, staff.actor(), null, null);
        log.info("Confirmation {} recorded: {} {} {} for order {} by {}", id, method, gross.toPlainString(), currency, request.orderId(), staff.actor());
        return new Confirmed(view, true);
    }

    /**
     * Takes back a confirmation keyed wrongly. Only one whose money has not paid a sale.
     *
     * @throws IllegalArgumentException (400) for a reason under ten characters
     * @throws PaymentProblem 404 {@code confirmation-not-found}; 409 {@code already-voided}; 409 {@code confirmation-settled}
     */
    @Transactional
    public ConfirmationView voidConfirmation(UUID id, String reason, Staff staff) {
        return voidConfirmation(id, reason, staff, false);
    }

    /**
     * @param ownerMayCancelProviderMoney whether the caller is the owner (PAYMENT_ADMIN). A record written from a ZynlePay payment rests on
     *                                    ZynlePay's own answer, not on a person's keying, so only the owner overrules it; the attempt then goes
     *                                    back to the owner to decide, and the machine never records it again.
     * @throws org.springframework.security.access.AccessDeniedException (403) for a ZynlePay record cancelled by anyone but the owner
     */
    @Transactional
    public ConfirmationView voidConfirmation(UUID id, String reason, Staff staff, boolean ownerMayCancelProviderMoney) {
        // What it is, read before any lock (neither ever changes), so that the locks are taken in the one order: order, attempt, then the record.
        record Origin(UUID orderId, UUID providerPaymentId) {
        }
        Origin origin = jdbc.query("SELECT order_id, provider_payment_id FROM payment_confirmation WHERE id = ?",
                        (rs, n) -> new Origin(rs.getObject("order_id", UUID.class), rs.getObject("provider_payment_id", UUID.class)), id)
                .stream().findFirst().orElseThrow(() -> new PaymentProblem(HttpStatus.NOT_FOUND, "confirmation-not-found", "there is no confirmation " + id));
        if (origin.providerPaymentId() != null && !ownerMayCancelProviderMoney) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "a payment recorded from ZynlePay is cancelled by the owner only: it rests on ZynlePay's answer, not on a person's keying");
        }
        ProviderConfirmations.lockOrder(jdbc, origin.orderId());
        if (origin.providerPaymentId() != null) {
            jdbc.query("SELECT id FROM provider_payment WHERE id = ? FOR UPDATE", rs -> {
            }, origin.providerPaymentId());
        }
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 10 || why.length() > 200) {
            throw new IllegalArgumentException("a void needs a reason of 10 to 200 characters, written for the person who reads the books");
        }
        // The row is locked first: two people voiding at once are taken one after the other, and the second finds it voided.
        record Locked(UUID orderId, String method, String reference, BigDecimal gross, BigDecimal fee, BigDecimal net, String currency, String outcome, Instant arrivedAt) {
        }
        Locked row = jdbc.query("SELECT order_id, method, reference, gross, fee, net, currency, outcome, arrived_at FROM payment_confirmation WHERE id = ? FOR UPDATE",
                        (rs, n) -> new Locked(rs.getObject("order_id", UUID.class), rs.getString("method"), rs.getString("reference"), rs.getBigDecimal("gross"),
                                rs.getBigDecimal("fee"), rs.getBigDecimal("net"), rs.getString("currency"), rs.getString("outcome"), rs.getTimestamp("arrived_at").toInstant()), id)
                .stream().findFirst().orElseThrow(() -> new PaymentProblem(HttpStatus.NOT_FOUND, "confirmation-not-found", "there is no confirmation " + id));
        if (jdbc.queryForObject("SELECT count(*) FROM payment_confirmation_void WHERE confirmation_id = ?", Long.class, id) > 0) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "already-voided", "confirmation " + id + " was already voided");
        }
        if ("SETTLED".equals(row.outcome())) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "confirmation-settled",
                    "confirmation " + id + " paid a sale and is the record that sale stands on: it is never voided (correcting it is Phase 12's)");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // Read again under the confirmation's lock: held money that was paid back is money that has LEFT. Cancelling its record now would close a debt that was
        // already paid out, and the books would show money returned that was never recorded as received.
        java.util.List<String> held = jdbc.query("SELECT status FROM held_money WHERE confirmation_id = ? FOR UPDATE", (rs, n) -> rs.getString(1), id);
        if (held.contains("REPAID")) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "held-money-repaid", "the money of confirmation " + id + " was held and has been paid back to the customer: "
                    + "cancel that payout first if it was recorded wrongly");
        }
        // A defence: a refund is only ever owed out of money that paid a sale, which is refused above already.
        if (jdbc.queryForObject("SELECT count(*) FROM refund_due WHERE confirmation_id = ?", Long.class, id) > 0) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "confirmation-settled", "confirmation " + id + " paid a sale that was since corrected: a refund stands on it");
        }
        jdbc.update("INSERT INTO payment_confirmation_void (id, confirmation_id, voided_by, voided_by_name, reason, voided_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, staff.actor(), staff.name(), why, Timestamp.from(now));
        // The reference is freed, so the corrected confirmation can use it. The money held for this confirmation, if any, is closed with it.
        jdbc.update("UPDATE payment_confirmation SET reference_key = NULL, provider_payment_key = NULL WHERE id = ?", id);
        if (origin.providerPaymentId() != null) {
            handBackToTheOwner(origin.providerPaymentId(), id, now, staff.actor());
        }
        jdbc.update("UPDATE held_money SET status = 'VOIDED', voided_at = ? WHERE confirmation_id = ? AND status = 'OWED'", Timestamp.from(now), id);
        events.voided(id, row.orderId(), row.method(), row.reference(), row.gross(), row.fee(), row.net(), row.currency(), row.outcome(), why, staff.actor(), now, row.arrivedAt());
        // Which record, who, and what it was. Never the reason: a person typed it, so it can hold a customer's name; it is in the row and the event.
        log.info("Confirmation {} voided by {} ({})", id, staff.actor(), row.outcome());
        return get(id);
    }

    /**
     * The attempt whose money record was just cancelled is the owner's to decide again: UNRESOLVED / CONFIRMATION_VOIDED, holding no order, and not
     * asked about any more (ZynlePay would only repeat the answer that is in doubt, and the machine's word is taken once).
     */
    private void handBackToTheOwner(UUID providerPaymentId, UUID confirmationId, Instant now, String actor) {
        int moved = jdbc.update("""
                UPDATE provider_payment
                   SET status = 'UNRESOLVED', unresolved_reason = 'CONFIRMATION_VOIDED', confirmation_id = NULL, unresolved_at = ?, next_check_at = NULL,
                       final_recheck_at = NULL, check_lease_until = NULL, version = version + 1
                 WHERE id = ? AND status = 'VERIFIED' AND confirmation_id = ?""", Timestamp.from(now), providerPaymentId, confirmationId);
        if (moved == 0) {
            return;     // not the attempt's standing record (an earlier, already replaced one): nothing about the attempt changes
        }
        jdbc.query("SELECT order_id, keycloak_id, channel, method, amount, currency, started_at FROM provider_payment WHERE id = ?", rs -> {
            providerPaymentEvents.updated(providerPaymentId, rs.getObject("order_id", UUID.class), rs.getString("keycloak_id"), rs.getString("channel"),
                    rs.getString("method"), "UNRESOLVED", null, "CONFIRMATION_VOIDED", rs.getBigDecimal("amount"), rs.getString("currency"), null,
                    rs.getTimestamp("started_at").toInstant(), now, actor);
        }, providerPaymentId);
    }

    /** No other money is recorded for an order while a ZynlePay payment for it is in flight: that payment may still come. */
    private void refuseWhileAProviderPaymentHoldsTheOrder(UUID orderId) {
        jdbc.query("SELECT id FROM provider_payment WHERE order_id = ? AND holds_order", (rs, row) -> {
            throw new PaymentProblem(HttpStatus.CONFLICT, "provider-payment-in-progress",
                    "a payment through ZynlePay is in progress for this order: wait for it to finish, or for the owner to settle it, before recording other money")
                    .with("providerPaymentId", rs.getObject("id", UUID.class));
        }, orderId);
    }

    /** Money ZynlePay collected is recorded from ZynlePay's own answer, never keyed: not under our reference, its transaction id or the network's. */
    private void refuseAProvidersReference(String reference) {
        jdbc.query("SELECT id, order_id FROM provider_payment WHERE reference_no = ? OR provider_transaction_key = ? OR operator_reference_key = ? LIMIT 1",
                (rs, row) -> {
                    throw new PaymentProblem(HttpStatus.CONFLICT, "provider-payment-reference",
                            "that reference belongs to a payment through ZynlePay: its money is recorded from ZynlePay's own answer, never keyed by hand. "
                                    + "Open Provider payments to see where it stands")
                            .with("providerPaymentId", rs.getObject("id", UUID.class)).with("orderId", rs.getObject("order_id", UUID.class));
                }, reference, reference, reference);
    }

    /** Confirmations order has not answered yet, oldest first, as of {@code now}. */
    @Transactional(readOnly = true)
    public java.util.List<PendingView> pending(Instant now) {
        return jdbc.query("""
                SELECT c.id, c.order_id, c.method, c.reference, c.gross, c.currency, c.arrived_at, c.confirmed_at, c.emit_attempts
                  FROM payment_confirmation c
                  LEFT JOIN payment_confirmation_void v ON v.confirmation_id = c.id
                 WHERE c.outcome = 'PENDING' AND v.id IS NULL
                 ORDER BY c.confirmed_at, c.id""", (rs, n) -> {
            Instant confirmedAt = rs.getTimestamp("confirmed_at").toInstant();
            Duration age = Duration.between(confirmedAt, now);
            return new PendingView(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("method"), rs.getString("reference"),
                    rs.getBigDecimal("gross"), rs.getString("currency"), rs.getTimestamp("arrived_at").toInstant(), confirmedAt, Math.max(0, age.toSeconds()),
                    rs.getInt("emit_attempts"), age.compareTo(sales.settlementStuckAfter()) > 0);
        });
    }

    @Transactional(readOnly = true)
    public ConfirmationView get(UUID id) {
        return jdbc.query(SELECT + " WHERE c.id = ?", this::view, id).stream().findFirst()
                .orElseThrow(() -> new PaymentProblem(HttpStatus.NOT_FOUND, "confirmation-not-found", "there is no confirmation " + id));
    }

    // ---------------------------------------------------------------- plumbing

    /** The confirmation this key already made, if the request is the same; a request that is not is refused. */
    private Optional<UUID> repeat(String key, String fingerprint) {
        record Prior(UUID id, String fingerprint) {
        }
        Optional<Prior> prior = jdbc.query("SELECT id, request_fingerprint FROM payment_confirmation WHERE idempotency_key = ?",
                (rs, row) -> new Prior(rs.getObject("id", UUID.class), rs.getString("request_fingerprint")), key).stream().findFirst();
        if (prior.isEmpty()) {
            return Optional.empty();
        }
        if (!prior.get().fingerprint().equals(fingerprint)) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "idempotency-key-reused",
                    "this Idempotency-Key was used for a different request: send a new key for a new confirmation").with("confirmationId", prior.get().id());
        }
        return Optional.of(prior.get().id());
    }

    private void refuseIfUsed(String referenceKey) {
        // A record that was cancelled (its reference freed) and then turned out to have paid a sale after all: that money is in the books as a sale, so the same
        // transfer can never be recorded a second time, or it would be counted, and could be paid back, twice.
        jdbc.query("SELECT id, order_id FROM payment_confirmation WHERE outcome_reason = 'SETTLED_AFTER_VOID' AND method || ':' || reference = ?", (rs, row) -> {
            throw new PaymentProblem(HttpStatus.CONFLICT, "reference-paid-a-sale",
                    "that reference belongs to a payment record that was cancelled and then paid a sale after all (confirmation " + rs.getObject("id", UUID.class)
                            + "): the same payment is not recorded twice")
                    .with("confirmationId", rs.getObject("id", UUID.class)).with("orderId", rs.getObject("order_id", UUID.class));
        }, referenceKey);
        jdbc.query("SELECT id, order_id FROM payment_confirmation WHERE reference_key = ?", (rs, row) -> {
            throw new PaymentProblem(HttpStatus.CONFLICT, "reference-already-used",
                    "that reference has already confirmed money (confirmation " + rs.getObject("id", UUID.class) + "): the same payment is not counted twice. "
                            + "If it was keyed wrongly, void that confirmation first")
                    .with("confirmationId", rs.getObject("id", UUID.class)).with("orderId", rs.getObject("order_id", UUID.class));
        }, referenceKey);
    }

    ConfirmationView view(ResultSet rs, int row) throws SQLException {
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        Timestamp outcomeAt = rs.getTimestamp("outcome_at");
        return new ConfirmationView(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("method"), rs.getString("reference"),
                rs.getBigDecimal("gross"), rs.getBigDecimal("fee"), rs.getBigDecimal("net"), rs.getString("currency"),
                rs.getTimestamp("arrived_at").toInstant(), rs.getString("backdate_reason"), rs.getString("confirmed_by"), rs.getString("confirmed_by_name"),
                rs.getTimestamp("confirmed_at").toInstant(), rs.getString("outcome"), rs.getString("outcome_reason"),
                outcomeAt == null ? null : outcomeAt.toInstant(), voidedAt != null, voidedAt == null ? null : voidedAt.toInstant(),
                rs.getString("void_reason"), rs.getString("voided_by"));
    }

    /** What makes a request "the same": everything the person sent, in the form it was understood. */
    private static String fingerprint(UUID orderId, PaymentMethod method, String reference, BigDecimal gross, BigDecimal fee, String currency, Instant arrivedAt,
                                      String backdateReason) {
        String canonical = String.join("|", String.valueOf(orderId), method.name(), String.valueOf(reference), gross.toPlainString(), fee.toPlainString(), currency,
                String.valueOf(arrivedAt), backdateReason == null ? "" : backdateReason.strip());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
