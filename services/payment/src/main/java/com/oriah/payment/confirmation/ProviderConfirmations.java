package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.payment.claim.ClaimService;
import com.oriah.payment.events.ConfirmationEvents;

import lombok.extern.slf4j.Slf4j;

/**
 * Records money a payment provider collected as a payment confirmation: THE record of money, the one staff write (Phase 13). Nothing downstream
 * tells them apart except by asking: order settles the sale or holds the money, the watcher announces it again, a refund is owed against it.
 *
 * <p>Called only from inside the transaction that moves the attempt to VERIFIED (the machine, on ZynlePay's own "paid" to our question) or that
 * records the owner's decision: the attempt and its money commit together or not at all.
 *
 * <h2>One payment, counted once</h2>
 * <ul>
 *   <li>An attempt has ONE standing record ({@code provider_payment_key}, unique): recording it again answers the record it has.</li>
 *   <li>The record's reference is Oriah's own for the attempt, under the same key every confirmation uses ({@code METHOD:reference}), so it
 *       collides with itself like any reference.</li>
 *   <li>Staff may already have keyed the same money from the customer's message, under the network's own reference or ZynlePay's transaction
 *       id. Then nothing is written and the caller is told which record holds it ({@link SameMoney}): the attempt goes to the owner.</li>
 * </ul>
 *
 * <p>Locks, in the one order payment takes them: the caller holds the order's lock and the attempt's row; this takes the reference locks, sorted.
 */
@Service
@Slf4j
public class ProviderConfirmations {

    public static final String PROVIDER = "ZYNLEPAY";
    /** Keys beginning with this are the system's own; a person's Idempotency-Key may not. */
    public static final String KEY_PREFIX = "zynlepay:";

    /**
     * The money of one attempt.
     *
     * @param ordinal        which record of this attempt this is: 1, or more when an earlier one was cancelled by the owner
     * @param operatorKey    the network's own reference in the one spelling references are kept in, or null
     * @param transactionKey ZynlePay's transaction id in that spelling, or null
     */
    public record ProviderMoney(UUID providerPaymentId, UUID orderId, String method, String referenceNo, BigDecimal gross, BigDecimal fee, String currency,
                                Instant arrivedAt, String backdateReason, int ordinal, String operatorKey, String transactionKey) {
    }

    public sealed interface Result permits Recorded, SameMoney {
    }

    public record Recorded(UUID confirmationId) implements Result {
    }

    /** Nothing was written: this standing record already holds the same money. */
    public record SameMoney(UUID confirmationId) implements Result {
    }

    private final JdbcTemplate jdbc;
    private final ConfirmationEvents events;
    private final Clock clock;

    public ProviderConfirmations(JdbcTemplate jdbc, ConfirmationEvents events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    /** One writer at a time for everything that records money for an order or starts collecting it: taken FIRST, before any other lock. */
    public static void lockOrder(JdbcTemplate jdbc, UUID orderId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, "payment-order:" + orderId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result record(ProviderMoney money, String actor, String actorName) {
        List<UUID> standing = standing(money.providerPaymentId());
        if (!standing.isEmpty()) {
            return new Recorded(standing.getFirst());
        }
        TreeSet<String> references = new TreeSet<>();
        references.add(money.referenceNo());
        if (money.operatorKey() != null) {
            references.add(money.operatorKey());
        }
        if (money.transactionKey() != null) {
            references.add(money.transactionKey());
        }
        references.forEach(reference -> ClaimService.lockReference(jdbc, money.method(), reference));

        for (String theirs : new String[] {money.operatorKey(), money.transactionKey()}) {
            if (theirs == null) {
                continue;
            }
            // A standing record under that reference, or one that was cancelled and then paid a sale after all (its money is in the books).
            List<UUID> same = jdbc.query("""
                    SELECT id FROM payment_confirmation
                     WHERE (reference_key = ? OR (outcome_reason = 'SETTLED_AFTER_VOID' AND method || ':' || reference = ?))
                       AND provider_payment_id IS DISTINCT FROM ?
                     ORDER BY confirmed_at LIMIT 1""", (rs, n) -> rs.getObject("id", UUID.class), money.method() + ":" + theirs, money.method() + ":" + theirs,
                    money.providerPaymentId());
            if (!same.isEmpty()) {
                log.warn("Provider payment {} reports money that confirmation {} already records under the network's own reference: not recorded twice",
                        money.providerPaymentId(), same.getFirst());
                return new SameMoney(same.getFirst());
            }
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant arrivedAt = money.arrivedAt().truncatedTo(ChronoUnit.MICROS);
        String key = KEY_PREFIX + money.referenceNo() + (money.ordinal() > 1 ? ":" + money.ordinal() : "");
        UUID id = UUID.randomUUID();
        boolean inserted = !jdbc.query("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, backdate_reason,
                                                  confirmed_by, confirmed_by_name, confirmed_at, idempotency_key, request_fingerprint, provider,
                                                  provider_payment_id, provider_payment_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING id""", (rs, n) -> rs.getObject("id", UUID.class),
                id, money.orderId(), money.method(), money.referenceNo(), money.method() + ":" + money.referenceNo(), money.gross(), money.fee(), money.currency(),
                Timestamp.from(arrivedAt), money.backdateReason(), actor, actorName, Timestamp.from(now), key, fingerprint(money.providerPaymentId(), money.ordinal()),
                PROVIDER, money.providerPaymentId(), money.providerPaymentId()).isEmpty();
        if (!inserted) {
            // Under the attempt's row lock nothing else records it; if its reference or key is taken all the same, that is a fault to be seen.
            throw new IllegalStateException("the money of provider payment " + money.providerPaymentId() + " could not be recorded: its reference or key is taken");
        }
        events.confirmed(id, money.orderId(), money.method(), money.referenceNo(), money.gross(), money.fee(), money.gross().subtract(money.fee()),
                money.currency(), arrivedAt, money.backdateReason(), actor, now, 1, actor, PROVIDER, money.providerPaymentId());
        log.info("Confirmation {} recorded from provider payment {}: {} {} {} for order {} by {}", id, money.providerPaymentId(), money.method(),
                money.gross().toPlainString(), money.currency(), money.orderId(), actor);
        return new Recorded(id);
    }

    private List<UUID> standing(UUID providerPaymentId) {
        return jdbc.query("SELECT id FROM payment_confirmation WHERE provider_payment_key = ?", (rs, n) -> rs.getObject("id", UUID.class), providerPaymentId);
    }

    private static String fingerprint(UUID providerPaymentId, int ordinal) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("zynlepay|" + Objects.requireNonNull(providerPaymentId) + "|" + ordinal).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
