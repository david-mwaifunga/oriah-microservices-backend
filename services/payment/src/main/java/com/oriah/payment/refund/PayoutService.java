package com.oriah.payment.refund;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.money.Ngwee;
import com.oriah.payment.claim.ClaimService;
import com.oriah.payment.confirmation.ArrivalWindow;
import com.oriah.payment.confirmation.PaymentMethod;
import com.oriah.payment.confirmation.ReferenceNumber;
import com.oriah.payment.confirmation.SalesSettings;
import com.oriah.payment.confirmation.Staff;
import com.oriah.payment.dto.PayoutRequest;
import com.oriah.payment.events.PaidOutEvent;
import com.oriah.payment.events.PayoutVoidedEvent;
import com.oriah.payment.events.RefundEvents;
import com.oriah.payment.exception.PaymentProblem;

import lombok.extern.slf4j.Slf4j;

/**
 * Money paid back to a customer (Phase 12): a refund that is owed, or money held for them. The owner says how it was sent, what the provider charged Oriah to send
 * it, and how much of that the customer bore. The amount is the debt's own, copied from it; a request that carries one is refused.
 *
 * <p><b>Who bears the sending charge.</b> For a refund, the debt says (order decided it from why the sale was corrected). For held money, the reason it was held
 * decides ({@code oriah.refunds.bearer.held.*}). When Oriah bears it the customer receives the whole amount; when the customer bears it, what is typed as their
 * part is never more than the provider charged and never the whole amount.
 *
 * <p><b>Twice is refused three ways.</b> The same {@code Idempotency-Key} and request answers the same payout; a debt has one standing payout (a second finds it
 * paid); and one outgoing reference pays out once. A payout recorded wrongly is cancelled, once, with a reason: the debt is owed again and the reference is free.
 *
 * <p>One transaction each. Locks: the confirmation's row, then the debt's, then the payout's; the outbox last.
 */
@Service
@Slf4j
public class PayoutService {

    /** The payout, and whether this call made it (false: a repeat of an earlier call with the same Idempotency-Key). */
    public record Paid(PayoutView view, boolean created) {
    }

    private record Debt(String purpose, UUID id, UUID orderId, UUID confirmationId, BigDecimal owed, String currency, String status, String cause, String feeBearer,
                        Instant notBefore) {
    }

    private static final String SELECT = """
            SELECT m.id, m.purpose, m.refund_due_id, m.held_money_id, m.order_id, m.gross, m.customer_fee, m.net, m.send_charge, m.fee_bearer, m.currency, m.method,
                   m.reference, m.paid_at, m.paid_by, m.paid_by_name, m.recorded_at, v.voided_at, v.reason AS void_reason
              FROM money_out m LEFT JOIN money_out_void v ON v.money_out_id = m.id""";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RefundEvents events;
    private final RefundSettings settings;
    private final SalesSettings sales;

    public PayoutService(JdbcTemplate jdbc, Clock clock, RefundEvents events, RefundSettings settings, SalesSettings sales) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
        this.settings = settings;
        this.sales = sales;
    }

    /**
     * @throws IllegalArgumentException (400) for a method, a reference or an amount that makes no sense, or an old date with no reason
     * @throws org.springframework.security.access.AccessDeniedException (403) for a date more than 72 hours back by someone who may not back-date
     * @throws PaymentProblem 400 {@code amount-is-not-typed}, {@code invalid-send-charge}, {@code cash-has-no-charge}, {@code invalid-customer-fee},
     *                        {@code paid-before-it-was-owed}; 404 {@code refund-due-not-found}, {@code held-money-not-found}; 409 {@code idempotency-key-reused},
     *                        {@code already-paid-out}, {@code money-check-needed}, {@code refund-closed}, {@code held-money-voided}, {@code void-and-key-again},
     *                        {@code oriah-bears-this-charge}, {@code payout-reference-already-used}
     */
    @Transactional
    public Paid pay(PayoutRequest request, String idempotencyKey, Staff staff) {
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 128 characters");
        }
        if (request.gross() != null || request.net() != null) {
            throw new PaymentProblem(HttpStatus.BAD_REQUEST, "amount-is-not-typed",
                    "the amount paid back is what is owed, and what the customer receives is that less the charge they bore: neither is sent");
        }
        if ((request.refundDueId() == null) == (request.heldMoneyId() == null)) {
            throw new IllegalArgumentException("a payout pays one thing: name the refund owed (refundDueId) or the held money (heldMoneyId), not both and not neither");
        }
        boolean refund = request.refundDueId() != null;
        UUID debtId = refund ? request.refundDueId() : request.heldMoneyId();
        PaymentMethod method = PaymentMethod.parse(request.method());
        // One spelling of a reference: trimmed, upper-cased, no spaces. Cash carries none.
        String reference = method.requiresReference() ? ReferenceNumber.normalise(request.reference()) : null;
        BigDecimal customerFee = amount(request.customerFee(), "customerFee");
        BigDecimal sendCharge = amount(request.sendCharge(), "sendCharge");
        String fingerprint = fingerprint(debtId, method, reference, customerFee, sendCharge, request.paidAt(), request.backdateReason());

        // A repeat is answered first, before anything that depends on the time or on what the debt has become.
        Optional<UUID> prior = repeat(key, fingerprint);
        if (prior.isPresent()) {
            return new Paid(get(prior.get()), false);
        }
        UUID confirmationId = jdbc.query(refund ? "SELECT confirmation_id FROM refund_due WHERE id = ?" : "SELECT confirmation_id FROM held_money WHERE id = ?",
                (rs, n) -> rs.getObject(1, UUID.class), debtId).stream().findFirst().orElseThrow(() -> refund ? RefundDues.notFound(debtId)
                : new PaymentProblem(HttpStatus.NOT_FOUND, "held-money-not-found", "there is no held money under " + debtId));
        Instant arrivedAt = jdbc.queryForObject("SELECT arrived_at FROM payment_confirmation WHERE id = ? FOR UPDATE", (rs, n) -> rs.getTimestamp(1).toInstant(),
                confirmationId);
        // Waiting for that lock can mean waiting for this very request sent a moment ago: it has committed by now.
        Optional<UUID> sentTwice = repeat(key, fingerprint);
        if (sentTwice.isPresent()) {
            return new Paid(get(sentTwice.get()), false);
        }
        Debt debt = refund ? lockRefund(debtId) : lockHeld(debtId, arrivedAt);
        refuseUnlessOwed(debt);

        Instant now = now();
        Instant paidAt = (request.paidAt() == null ? now : request.paidAt()).truncatedTo(ChronoUnit.MICROS);
        String backdateReason = ArrivalWindow.check(paidAt, now, sales.backdateWindow(), staff.mayBackdate(), request.backdateReason());
        if (paidAt.isBefore(debt.notBefore())) {
            throw new PaymentProblem(HttpStatus.BAD_REQUEST, "paid-before-it-was-owed", "money cannot have been paid back at " + paidAt + ": it was owed only from "
                    + debt.notBefore());
        }
        if (sendCharge.compareTo(settings.maxSendCharge()) > 0) {
            throw new PaymentProblem(HttpStatus.BAD_REQUEST, "invalid-send-charge", "a charge of " + sendCharge.toPlainString() + " for sending money is more than "
                    + settings.maxSendCharge().toPlainString() + ": please check the figure");
        }
        if (method == PaymentMethod.CASH && sendCharge.signum() > 0) {
            throw new PaymentProblem(HttpStatus.BAD_REQUEST, "cash-has-no-charge", "cash handed over costs nothing to send: the charge must be 0.00");
        }
        if (customerFee.compareTo(sendCharge) > 0 || customerFee.compareTo(debt.owed()) >= 0) {
            throw new PaymentProblem(HttpStatus.BAD_REQUEST, "invalid-customer-fee", "the customer bears at most what the provider charged (" + sendCharge.toPlainString()
                    + ") and always receives something of the " + debt.owed().toPlainString() + " owed");
        }
        if ("ORIAH".equals(debt.feeBearer()) && customerFee.signum() > 0) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "oriah-bears-this-charge", "Oriah bears the charge for sending this money: the customer receives all of the "
                    + debt.owed().toPlainString() + ", so the customer's part must be 0.00");
        }
        String referenceKey = reference == null ? null : method.name() + ":" + reference;
        if (referenceKey != null) {
            // Two payouts under one reference are taken one after the other. (Its own name: an outgoing reference is not an incoming one.)
            ClaimService.lockReference(jdbc, "OUT-" + method.name(), reference);
            List<UUID> used = jdbc.query("SELECT id FROM money_out WHERE reference_key = ?", (rs, n) -> rs.getObject(1, UUID.class), referenceKey);
            if (!used.isEmpty()) {
                throw new PaymentProblem(HttpStatus.CONFLICT, "payout-reference-already-used", "that reference has already paid money out (payout " + used.get(0)
                        + "): the same transfer is not recorded twice. If it was recorded wrongly, cancel that payout first").with("payoutId", used.get(0));
            }
        }

        UUID id = UUID.randomUUID();
        // The database checks it once more, against the debt itself: it pays exactly an owed debt, as owed.
        jdbc.update("""
                INSERT INTO money_out (id, purpose, refund_due_id, held_money_id, live_for, order_id, confirmation_id, cause, gross, customer_fee, send_charge, fee_bearer,
                                       currency, method, reference, reference_key, paid_at, backdate_reason, paid_by, paid_by_name, recorded_at, idempotency_key,
                                       request_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, debt.purpose(), refund ? debtId : null, refund ? null : debtId, debtId, debt.orderId(), debt.confirmationId(), debt.cause(), debt.owed(), customerFee,
                sendCharge, debt.feeBearer(), debt.currency(), method.name(), reference, referenceKey, Timestamp.from(paidAt), backdateReason, staff.actor(), staff.name(),
                Timestamp.from(now), key, fingerprint);
        if (refund) {
            jdbc.update("UPDATE refund_due SET status = 'PAID', paid_money_out_id = ? WHERE id = ?", id, debtId);
        } else {
            jdbc.update("UPDATE held_money SET status = 'REPAID', repaid_at = ? WHERE id = ?", Timestamp.from(paidAt), debtId);
        }
        events.paidOut(new PaidOutEvent(id, debt.purpose(), debtId, debt.cause(), debt.orderId(), debt.confirmationId(), method.name(), debt.owed(), customerFee,
                debt.owed().subtract(customerFee), sendCharge, debt.feeBearer(), debt.currency(), paidAt, now), staff.actor());
        // Which payout, of what, by whom. Never the reference: it is in the row.
        log.info("Payout {} recorded: {} {} {} by {} for order {} ({}), by {}", id, debt.purpose(), debt.owed().toPlainString(), debt.currency(), method, debt.orderId(),
                debt.cause(), staff.actor());
        return new Paid(get(id), true);
    }

    /**
     * Cancels a payout recorded wrongly: the debt it paid is owed again, and its reference is free.
     *
     * @throws PaymentProblem 404 {@code payout-not-found}; 409 {@code payout-already-voided}
     */
    @Transactional
    public PayoutView voidPayout(UUID payoutId, String reason, Staff staff) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 10 || why.length() > 200) {
            throw new IllegalArgumentException("cancelling a payout needs a reason of 10 to 200 characters, written for the person who reads the books");
        }
        record Out(String purpose, UUID refundDueId, UUID heldMoneyId, UUID orderId, UUID confirmationId, String method, BigDecimal gross, BigDecimal customerFee,
                   BigDecimal net, BigDecimal sendCharge, String currency, Instant paidAt) {
        }
        org.springframework.jdbc.core.RowMapper<Out> mapper = (rs, n) -> new Out(rs.getString("purpose"), rs.getObject("refund_due_id", UUID.class),
                rs.getObject("held_money_id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("confirmation_id", UUID.class), rs.getString("method"),
                rs.getBigDecimal("gross"), rs.getBigDecimal("customer_fee"), rs.getBigDecimal("net"), rs.getBigDecimal("send_charge"), rs.getString("currency"),
                rs.getTimestamp("paid_at").toInstant());
        String columns = "SELECT purpose, refund_due_id, held_money_id, order_id, confirmation_id, method, gross, customer_fee, net, send_charge, currency, paid_at FROM money_out WHERE id = ?";
        Out out = jdbc.query(columns, mapper, payoutId).stream().findFirst()
                .orElseThrow(() -> new PaymentProblem(HttpStatus.NOT_FOUND, "payout-not-found", "there is no payout " + payoutId));
        boolean refund = out.refundDueId() != null;
        UUID debtId = refund ? out.refundDueId() : out.heldMoneyId();
        // The confirmation, then the debt, then the payout.
        jdbc.query("SELECT id FROM payment_confirmation WHERE id = ? FOR UPDATE", rs -> { }, out.confirmationId());
        jdbc.query(refund ? "SELECT id FROM refund_due WHERE id = ? FOR UPDATE" : "SELECT id FROM held_money WHERE id = ? FOR UPDATE", rs -> { }, debtId);
        jdbc.query("SELECT id FROM money_out WHERE id = ? FOR UPDATE", rs -> { }, payoutId);
        if (jdbc.queryForObject("SELECT count(*) FROM money_out_void WHERE money_out_id = ?", Long.class, payoutId) > 0) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "payout-already-voided", "payout " + payoutId + " was already cancelled");
        }
        Instant now = now();
        jdbc.update("INSERT INTO money_out_void (id, money_out_id, voided_by, voided_by_name, reason, voided_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), payoutId, staff.actor(), staff.name(), why, Timestamp.from(now));
        jdbc.update("UPDATE money_out SET live_for = NULL, reference_key = NULL WHERE id = ?", payoutId);
        if (refund) {
            jdbc.update("UPDATE refund_due SET status = 'OWED', paid_money_out_id = NULL WHERE id = ?", debtId);
        } else {
            jdbc.update("UPDATE held_money SET status = 'OWED', repaid_at = NULL WHERE id = ?", debtId);
        }
        events.payoutVoided(new PayoutVoidedEvent(payoutId, out.purpose(), debtId, out.orderId(), out.confirmationId(), out.method(), out.gross(), out.customerFee(),
                out.net(), out.sendCharge(), out.currency(), out.paidAt(), now), staff.actor());
        // Which payout, who. Never the reason: a person typed it, so it can hold a customer's name or number.
        log.info("Payout {} cancelled by {}: its debt is owed again", payoutId, staff.actor());
        return get(payoutId);
    }

    /** @param purpose REFUND, HELD_MONEY or null for both; newest first; {@code since} keeps those recorded from that moment on */
    @Transactional(readOnly = true)
    public List<PayoutView> list(String purpose, Instant since) {
        String wanted = purpose == null || purpose.isBlank() ? null : purpose.strip().toUpperCase(java.util.Locale.ROOT);
        if (wanted != null && !List.of("REFUND", "HELD_MONEY").contains(wanted)) {
            throw new IllegalArgumentException("purpose must be REFUND or HELD_MONEY, was '" + purpose + "'");
        }
        return jdbc.query(SELECT + " WHERE (?::text IS NULL OR m.purpose = ?) AND (?::timestamptz IS NULL OR m.recorded_at >= ?) ORDER BY m.recorded_at DESC, m.id LIMIT 200",
                this::view, wanted, wanted, since == null ? null : Timestamp.from(since), since == null ? null : Timestamp.from(since));
    }

    @Transactional(readOnly = true)
    public PayoutView get(UUID payoutId) {
        return jdbc.query(SELECT + " WHERE m.id = ?", this::view, payoutId).stream().findFirst()
                .orElseThrow(() -> new PaymentProblem(HttpStatus.NOT_FOUND, "payout-not-found", "there is no payout " + payoutId));
    }

    // ---------------------------------------------------------------- the debt

    private Debt lockRefund(UUID dueId) {
        return jdbc.queryForObject("SELECT id, order_id, confirmation_id, owed_gross, currency, status, kind, fee_bearer, owed_since FROM refund_due WHERE id = ? FOR UPDATE",
                (rs, n) -> new Debt("REFUND", rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("confirmation_id", UUID.class),
                        rs.getBigDecimal("owed_gross"), rs.getString("currency"), rs.getString("status"), rs.getString("kind"), rs.getString("fee_bearer"),
                        rs.getTimestamp("owed_since").toInstant()), dueId);
    }

    /** Held money is owed from the day the money arrived: the confirmation says when (the held record has no such date of its own). */
    private Debt lockHeld(UUID heldId, Instant arrivedAt) {
        return jdbc.queryForObject("SELECT id, order_id, confirmation_id, owed_gross, currency, status, reason FROM held_money WHERE id = ? FOR UPDATE",
                (rs, n) -> new Debt("HELD_MONEY", rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("confirmation_id", UUID.class),
                        rs.getBigDecimal("owed_gross"), rs.getString("currency"), rs.getString("status"), rs.getString("reason"),
                        settings.bearerOfHeld(rs.getString("reason")), arrivedAt), heldId);
    }

    private static void refuseUnlessOwed(Debt debt) {
        if ("HELD_MONEY".equals(debt.purpose()) && "WRONG_DATE".equals(debt.cause()) && "OWED".equals(debt.status())) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "void-and-key-again", "this money is held because it was recorded on another day than its sale: cancel the "
                    + "payment record and record it again with the right day. It is not paid back");
        }
        switch (debt.status()) {
            case "OWED" -> { }
            case "PAID", "REPAID" -> throw new PaymentProblem(HttpStatus.CONFLICT, "already-paid-out", "this has already been paid back: a debt is paid once. "
                    + "If that payout was recorded wrongly, cancel it first");
            case "IN_QUESTION" -> throw new PaymentProblem(HttpStatus.CONFLICT, "money-check-needed", "the payment record of this sale was cancelled after it paid the "
                    + "sale: say first whether the money arrived");
            case "CLOSED" -> throw new PaymentProblem(HttpStatus.CONFLICT, "refund-closed", "this refund was closed: the money it would have returned never arrived");
            case "VOIDED" -> throw new PaymentProblem(HttpStatus.CONFLICT, "held-money-voided", "the payment record of this held money was cancelled: nothing is owed");
            default -> throw new IllegalStateException("a debt in status " + debt.status());
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static BigDecimal amount(BigDecimal value, String name) {
        BigDecimal amount = Ngwee.require(value, name);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(name + " cannot be below nothing");
        }
        return amount.setScale(2);
    }

    /** The payout this key already made, if the request is the same; a request that is not is refused. */
    private Optional<UUID> repeat(String key, String fingerprint) {
        record Prior(UUID id, String fingerprint) {
        }
        Optional<Prior> prior = jdbc.query("SELECT id, request_fingerprint FROM money_out WHERE idempotency_key = ?",
                (rs, n) -> new Prior(rs.getObject("id", UUID.class), rs.getString("request_fingerprint")), key).stream().findFirst();
        if (prior.isEmpty()) {
            return Optional.empty();
        }
        if (!prior.get().fingerprint().equals(fingerprint)) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "idempotency-key-reused", "this Idempotency-Key was used for a different request: send a new key for a new payout")
                    .with("payoutId", prior.get().id());
        }
        return Optional.of(prior.get().id());
    }

    private PayoutView view(ResultSet rs, int row) throws SQLException {
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        return new PayoutView(rs.getObject("id", UUID.class), rs.getString("purpose"), rs.getObject("refund_due_id", UUID.class), rs.getObject("held_money_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getBigDecimal("gross"), rs.getBigDecimal("customer_fee"), rs.getBigDecimal("net"), rs.getBigDecimal("send_charge"),
                rs.getString("fee_bearer"), rs.getString("currency"), rs.getString("method"), rs.getString("reference"), rs.getTimestamp("paid_at").toInstant(),
                rs.getString("paid_by"), rs.getString("paid_by_name"), rs.getTimestamp("recorded_at").toInstant(), voidedAt != null,
                voidedAt == null ? null : voidedAt.toInstant(), rs.getString("void_reason"));
    }

    /** What makes a request "the same": everything the person sent, in the form it was understood. */
    private static String fingerprint(UUID debtId, PaymentMethod method, String reference, BigDecimal customerFee, BigDecimal sendCharge, Instant paidAt,
                                      String backdateReason) {
        String canonical = String.join("|", debtId.toString(), method.name(), String.valueOf(reference), customerFee.toPlainString(), sendCharge.toPlainString(),
                String.valueOf(paidAt), backdateReason == null ? "" : backdateReason.strip());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
