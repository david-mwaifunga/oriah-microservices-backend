package com.oriah.payment.gateway;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * A stand-in card gateway that behaves like a real one where it matters for
 * money: it remembers what it decided, by charge reference.
 *
 * <p>Stripe's magic cards decide a charge:
 * <ul>
 *   <li><b>4242 4242 4242 4242</b> &mdash; captured;</li>
 *   <li><b>4000 0000 0000 0002</b> &mdash; declined: "card declined";</li>
 *   <li><b>4000 0000 0000 9995</b> &mdash; declined: "insufficient funds";</li>
 *   <li>anything else &mdash; declined: "invalid test card".</li>
 * </ul>
 * Whitespace is stripped first, so {@code "4242 4242 4242 4242"} works. The
 * card number itself is never stored.
 *
 * <h2>Its memory: fake_gateway_charge</h2>
 *
 * One row per reference, written in the gateway's OWN transaction
 * ({@code REQUIRES_NEW}), because a real gateway's records do not roll back
 * when its caller's do. Charge and void both {@code INSERT ... ON CONFLICT
 * (charge_reference) DO NOTHING} and then read the row back, so:
 * <ul>
 *   <li>a repeated charge of one reference gets the first answer and takes no
 *       more money;</li>
 *   <li>a charge and a void racing on one reference: the database lets
 *       exactly one insert in, and both callers read that one outcome;</li>
 *   <li>a charge of a reference already voided is answered VOIDED and never
 *       captures;</li>
 *   <li>a new instance -- a restart -- answers exactly as the old one did.</li>
 * </ul>
 * The table refuses UPDATE and DELETE, so a decided outcome never changes.
 */
@Component
@Slf4j
public class FakePaymentGateway implements PaymentGateway {

    private static final String CARD_SUCCESS = "4242424242424242";
    private static final String CARD_DECLINED = "4000000000000002";
    private static final String CARD_INSUFFICIENT = "4000000000009995";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;
    /**
     * Where its decision times come from: payment's clock, so a test can make
     * a charge happen days before the reconciler learns of it.
     */
    private final Clock clock;

    public FakePaymentGateway(DataSource dataSource, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Override
    public ChargeResult charge(UUID chargeReference, String cardNumber, BigDecimal amount, String currency) {
        requireReference(chargeReference);
        String normalized = cardNumber == null ? "" : cardNumber.replaceAll("\\s+", "");
        // The line starts "FakePaymentGateway.charge amount=<amount> " on purpose:
        // smoke-test.ps1 counts charges by exactly that text (Charge-Count), so
        // the reference goes at the end.
        log.info("FakePaymentGateway.charge amount={} {} cardLast4={} ref={}", amount, currency,
                normalized.length() < 4 ? "????" : normalized.substring(normalized.length() - 4), chargeReference);

        MapSqlParameterSource row = new MapSqlParameterSource()
                .addValue("ref", chargeReference)
                .addValue("amount", amount)
                .addValue("currency", currency)
                .addValue("decidedAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        switch (normalized) {
            case CARD_SUCCESS -> row.addValue("outcome", "CAPTURED")
                    .addValue("gatewayReference", "fake_ch_" + UUID.randomUUID())
                    .addValue("reason", null);
            case CARD_DECLINED -> declined(row, "card declined");
            case CARD_INSUFFICIENT -> declined(row, "insufficient funds");
            default -> declined(row, "invalid test card");
        }
        return decide(chargeReference, """
                INSERT INTO fake_gateway_charge (charge_reference, outcome, gateway_reference, reason,
                                                 amount, currency, decided_at)
                VALUES (:ref, :outcome, :gatewayReference, :reason, :amount, :currency, :decidedAt)
                ON CONFLICT (charge_reference) DO NOTHING""", row);
    }

    @Override
    public LookupResult lookup(UUID chargeReference) {
        requireReference(chargeReference);
        Optional<Outcome> outcome = ownTransaction.execute(status -> read(chargeReference));
        return outcome.isPresent() ? outcome.get() : new NotFound();
    }

    /**
     * Closes a reference that has no outcome yet, so a charge of it still in
     * flight can never be captured. If the reference already has an outcome,
     * that outcome is the answer -- a capture is never undone by a void.
     */
    @Override
    public VoidResult voidUnsettled(UUID chargeReference) {
        requireReference(chargeReference);
        log.info("FakePaymentGateway.voidUnsettled ref={}", chargeReference);
        return decide(chargeReference, """
                INSERT INTO fake_gateway_charge (charge_reference, outcome, decided_at)
                VALUES (:ref, 'VOIDED', :decidedAt)
                ON CONFLICT (charge_reference) DO NOTHING""",
                new MapSqlParameterSource("ref", chargeReference)
                        .addValue("decidedAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    /**
     * Offers an outcome for the reference and answers with whichever outcome
     * the reference holds afterwards: this one if it was first, the earlier
     * one otherwise. The read-back is a new statement, so under READ
     * COMMITTED it sees a rival's row once the insert has waited for it.
     */
    private Outcome decide(UUID chargeReference, String insert, MapSqlParameterSource row) {
        return ownTransaction.execute(status -> {
            jdbc.update(insert, row);
            return read(chargeReference).orElseThrow(() -> new IllegalStateException(
                    "fake_gateway_charge has no row for " + chargeReference + " right after writing one"));
        });
    }

    private Optional<Outcome> read(UUID chargeReference) {
        List<Outcome> rows = jdbc.query("""
                SELECT outcome, gateway_reference, reason, decided_at
                FROM fake_gateway_charge WHERE charge_reference = :ref""",
                new MapSqlParameterSource("ref", chargeReference),
                (rs, n) -> {
                    var at = rs.getObject("decided_at", OffsetDateTime.class).toInstant();
                    return switch (rs.getString("outcome")) {
                        case "CAPTURED" -> new Captured(rs.getString("gateway_reference"), at);
                        case "DECLINED" -> new Declined(rs.getString("reason"), at);
                        case "VOIDED" -> new Voided(at);
                        default -> throw new IllegalStateException(
                                "fake_gateway_charge holds an unknown outcome " + rs.getString("outcome"));
                    };
                });
        return rows.stream().findFirst();
    }

    private static void declined(MapSqlParameterSource row, String reason) {
        row.addValue("outcome", "DECLINED").addValue("gatewayReference", null).addValue("reason", reason);
    }

    private static void requireReference(UUID chargeReference) {
        if (chargeReference == null) {
            throw new IllegalArgumentException("A gateway call needs the charge reference");
        }
    }
}
