package com.oriah.payment.claim;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.payment.confirmation.PaymentMethod;
import com.oriah.payment.confirmation.ReferenceNumber;
import com.oriah.payment.confirmation.Staff;
import com.oriah.payment.events.ConfirmationEvents;
import com.oriah.payment.exception.PaymentProblem;

import lombok.extern.slf4j.Slf4j;

/**
 * What shoppers claim to have paid with (from order's {@code order.payment_submitted}), and staff rejecting a claim that is false (Phase 7).
 *
 * <h2>A claim is not money</h2>
 *
 * It is what a rejection is bound to. A rejection copies the order, method and reference from the claim, so a person cannot reject a payment
 * they typed; it posts nothing and does not use up the reference (money for it can still be confirmed afterwards). Two proofs: the service
 * checks {@code REFERENCE_ALREADY_USED} itself (the same reference is already confirmed for ANOTHER order; held money counts, a voided
 * confirmation does not), and {@code NOT_FOUND_AT_PROVIDER} is the person's word with their evidence. A claim whose reference already has money
 * recorded for ITS OWN order is not false and cannot be rejected, whatever the proof.
 *
 * <p>Rejecting and confirming the same reference are taken one after the other (an advisory lock on the reference), so a confirmation keyed at the
 * moment a claim is rejected is either seen by the rejection or comes after it, never half of each.
 */
@Service
@Slf4j
public class ClaimService {

    static final String SELECT = """
            SELECT c.id, c.order_id, c.method, c.reference, c.submitted_at, c.hold_expires_at, c.received_at, r.rejected_at, r.proof_type, r.rejected_by,
                   EXISTS (SELECT 1 FROM payment_confirmation pc WHERE pc.method = c.method AND pc.reference = c.reference
                              AND pc.reference_key IS NOT NULL AND pc.order_id = c.order_id) AS money_recorded,
                   EXISTS (SELECT 1 FROM payment_confirmation pc WHERE pc.method = c.method AND pc.reference = c.reference
                              AND pc.reference_key IS NOT NULL AND pc.order_id <> c.order_id) AS used_elsewhere
              FROM payment_claim c
              LEFT JOIN payment_rejection r ON r.claim_id = c.id""";

    private static final String NOT_FOUND = "NOT_FOUND_AT_PROVIDER";
    private static final String ALREADY_USED = "REFERENCE_ALREADY_USED";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ConfirmationEvents events;

    public ClaimService(JdbcTemplate jdbc, Clock clock, ConfirmationEvents events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Records a claim; the same claim again (the event delivered twice) changes nothing.
     *
     * @throws IllegalArgumentException for a method that is not claimable (cash is paid in person) or a reference that is not one: a broken event
     */
    @Transactional
    public void record(UUID claimId, UUID orderId, String method, String reference, Instant submittedAt, Instant holdExpiresAt) {
        PaymentMethod claimed = PaymentMethod.parse(method);
        if (!claimed.requiresReference()) {
            throw new IllegalArgumentException("cash is paid in person and cannot be claimed online");
        }
        String normalised = ReferenceNumber.normalise(reference);
        int added = jdbc.update("""
                INSERT INTO payment_claim (id, order_id, method, reference, submitted_at, hold_expires_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING""", claimId, orderId, claimed.name(), normalised, Timestamp.from(submittedAt),
                holdExpiresAt == null ? null : Timestamp.from(holdExpiresAt), Timestamp.from(clock.instant()));
        if (added == 1) {
            log.info("Claim {} recorded: {} {} for order {}", claimId, claimed, normalised, orderId);
        }
    }

    @Transactional(readOnly = true)
    public ClaimView get(UUID claimId) {
        return jdbc.query(SELECT + " WHERE c.id = ?", this::view, claimId).stream().findFirst()
                .orElseThrow(() -> new PaymentProblem(HttpStatus.NOT_FOUND, "claim-not-found", "there is no claim " + claimId));
    }

    /** Claims newest first; {@code orderId} null means every order's. */
    @Transactional(readOnly = true)
    public List<ClaimView> list(UUID orderId) {
        return orderId == null
                ? jdbc.query(SELECT + " ORDER BY c.received_at DESC, c.id", this::view)
                : jdbc.query(SELECT + " WHERE c.order_id = ? ORDER BY c.received_at DESC, c.id", this::view, orderId);
    }

    /**
     * @throws IllegalArgumentException (400) for an unknown proof type, or {@code NOT_FOUND_AT_PROVIDER} without evidence of ten characters
     * @throws PaymentProblem 404 {@code claim-not-found}; 409 {@code already-rejected}, {@code money-recorded-for-claim},
     *                        {@code reference-not-used-elsewhere}
     */
    @Transactional
    public RejectionView reject(UUID claimId, String proofType, String evidence, Staff staff) {
        String proof = proofType == null ? "" : proofType.strip().toUpperCase(java.util.Locale.ROOT);
        if (!NOT_FOUND.equals(proof) && !ALREADY_USED.equals(proof)) {
            throw new IllegalArgumentException("proofType must be " + NOT_FOUND + " or " + ALREADY_USED + ", was '" + proofType + "'");
        }
        String said = evidence == null || evidence.isBlank() ? null : evidence.strip();
        if (NOT_FOUND.equals(proof) && (said == null || said.length() < 10)) {
            throw new IllegalArgumentException("a claim not found at the provider needs the evidence, at least ten characters: what was looked at, and where");
        }
        if (said != null && said.length() > 500) {
            throw new IllegalArgumentException("the evidence is at most 500 characters");
        }

        ClaimView first = get(claimId);
        lockReference(first.method(), first.reference());
        ClaimView claim = get(claimId);   // read again under the lock: a confirmation or a rejection may have landed while this waited
        if (claim.rejected()) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "already-rejected", "claim " + claimId + " was already rejected");
        }
        if (claim.moneyRecorded()) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "money-recorded-for-claim",
                    "money is already confirmed with that reference for the same order, so the claim is not false and cannot be rejected");
        }
        if (ALREADY_USED.equals(proof) && !claim.usedElsewhere()) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "reference-not-used-elsewhere",
                    "that reference has not been confirmed for another order (held money counts, a voided confirmation does not): "
                            + "reject it as NOT_FOUND_AT_PROVIDER with the evidence instead");
        }

        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO payment_rejection (id, claim_id, order_id, method, reference, proof_type, evidence, rejected_by, rejected_by_name, rejected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, claimId, claim.orderId(), claim.method(), claim.reference(), proof, said, staff.actor(), staff.name(),
                Timestamp.from(now));
        events.rejected(claimId, claim.orderId(), claim.method(), claim.reference(), proof, staff.actor(), now);
        log.info("Claim {} rejected by {} ({})", claimId, staff.actor(), proof);
        return new RejectionView(id, claimId, claim.orderId(), claim.method(), claim.reference(), proof, said, staff.actor(), staff.name(), now);
    }

    /** Taken by everything that decides something about one (method, reference): confirming money for it, and rejecting a claim for it. */
    public static void lockReference(JdbcTemplate jdbc, String method, String reference) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, "payment-reference:" + method + ":" + reference);
    }

    private void lockReference(String method, String reference) {
        lockReference(jdbc, method, reference);
    }

    private ClaimView view(ResultSet rs, int row) throws SQLException {
        Timestamp rejectedAt = rs.getTimestamp("rejected_at");
        Timestamp holdExpires = rs.getTimestamp("hold_expires_at");
        return new ClaimView(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("method"), rs.getString("reference"),
                rs.getTimestamp("submitted_at").toInstant(), holdExpires == null ? null : holdExpires.toInstant(), rs.getTimestamp("received_at").toInstant(),
                rejectedAt != null, rejectedAt == null ? null : rejectedAt.toInstant(), rs.getString("proof_type"), rs.getString("rejected_by"),
                rs.getBoolean("money_recorded"), rs.getBoolean("used_elsewhere"));
    }
}
