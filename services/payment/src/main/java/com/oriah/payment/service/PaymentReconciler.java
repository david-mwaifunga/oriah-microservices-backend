package com.oriah.payment.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.payment.gateway.PaymentGateway;
import com.oriah.payment.gateway.PaymentGateway.NotFound;
import com.oriah.payment.gateway.PaymentGateway.Outcome;
import com.oriah.payment.gateway.PaymentGateway.Unknown;
import com.oriah.payment.repository.PaymentIntentRepository;
import com.oriah.payment.repository.PaymentIntentRepository.ChargeInFlight;

import lombok.extern.slf4j.Slf4j;

/**
 * Settles the intents whose charge outcome never reached payment.
 *
 * <h2>Why this exists</h2>
 *
 * Confirm commits CHARGING before it calls the gateway, and records the answer
 * after. If the answer is lost -- the gateway timed out, the service crashed,
 * or recording it failed -- the intent stays CHARGING. That is safe (nothing
 * can charge it again) but not finished: the shopper may have paid, and the
 * order is still waiting. Only the gateway knows, so this asks it, by the
 * charge reference committed before the call.
 *
 * <h2>What it does with each intent CHARGING for longer than the age</h2>
 *
 * <pre>
 *   lookup(reference)
 *     CAPTURED / DECLINED  -&gt; recorded: SUCCEEDED / FAILED, with its event
 *     NOT_FOUND            -&gt; voidUnsettled(reference)
 *                               VOIDED   -&gt; FAILED "charge not completed", at the void's time
 *                               CAPTURED -&gt; SUCCEEDED (the charge landed first)
 *                               UNKNOWN  -&gt; left CHARGING, ERROR for staff
 *     UNKNOWN              -&gt; left CHARGING, ERROR for staff
 * </pre>
 *
 * Why void rather than wait when the gateway has never heard of the charge:
 * the request may still be on its way. A void closes the reference for good,
 * so a request that arrives later takes nothing, and "never heard of it" can
 * be turned into an answer that will not change.
 *
 * <p>Why the age: a confirm still waiting on the gateway is also CHARGING.
 * Voiding it would not lose money -- its charge would answer VOIDED -- but it
 * would fail a payment that was about to go through. So only intents CHARGING
 * for longer than {@code oriah.payment.reconciler.charging-age}, well beyond
 * any gateway call, are asked about.
 *
 * <h2>Recording the answer</h2>
 *
 * Through {@link ChargeSettlement#settle}, exactly as confirm records it, in
 * one transaction: the intent (version-checked), its RESERVED
 * Idempotency-Key completed with the response confirm would have given, and
 * the event, written by {@value #ACTOR} at the gateway's decision time --
 * however long after the charge this runs. The gateway is asked with no
 * transaction open, as in confirm, so no database lock waits on it.
 *
 * <p>Safe to run on two instances at once, or alongside a late confirm: the
 * gateway's answer for a reference never changes, and settling is a no-op
 * for an intent that is no longer CHARGING with that reference. Of two
 * settlements racing, the version check lets one in; the other rolls back
 * before writing anything.
 */
@Component
@Slf4j
public class PaymentReconciler {

    /** Who settled it, in the event's actor. */
    public static final String ACTOR = "system:payment-reconciler";

    private final PaymentIntentRepository intents;
    private final PaymentGateway gateway;
    private final ChargeSettlement settlement;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration chargingAge;

    public PaymentReconciler(PaymentIntentRepository intents, PaymentGateway gateway, ChargeSettlement settlement,
                             PlatformTransactionManager transactionManager, Clock clock,
                             @Value("${oriah.payment.reconciler.charging-age}") Duration chargingAge) {
        if (chargingAge == null || chargingAge.isNegative() || chargingAge.isZero()) {
            // With no age, a confirm still waiting on the gateway would be
            // voided under it, failing a payment that was about to succeed.
            throw new IllegalArgumentException("oriah.payment.reconciler.charging-age must be more than zero, and "
                    + "longer than any gateway call; was " + chargingAge);
        }
        this.intents = intents;
        this.gateway = gateway;
        this.settlement = settlement;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.chargingAge = chargingAge;
    }

    /**
     * One pass over every intent CHARGING for longer than the age. A failure
     * on one intent is logged and the pass goes on to the next: one charge
     * the gateway cannot answer for must not hold up the rest.
     */
    public void reconcile() {
        List<ChargeInFlight> waiting = intents.findChargingStartedBefore(clock.instant().minus(chargingAge));
        for (ChargeInFlight charge : waiting) {
            try {
                gatewayAnswerFor(charge).ifPresent(outcome -> record(charge, outcome));
            } catch (RuntimeException e) {
                // The gateway call or the recording failed. Nothing was
                // recorded (the transaction rolled back, if it had begun), so
                // the intent is still CHARGING and the next pass tries again.
                log.error("PaymentIntent id={} has been CHARGING since {}; settling charge {} failed: {}. It stays "
                        + "CHARGING and is asked about again on the next pass.",
                        charge.intentId(), charge.since(), charge.chargeReference(), e.getMessage(), e);
            }
        }
    }

    /** The gateway's final answer about the charge, or empty when it cannot give one yet. */
    private Optional<Outcome> gatewayAnswerFor(ChargeInFlight charge) {
        return switch (gateway.lookup(charge.chargeReference())) {
            case Outcome decided -> Optional.of(decided);
            case Unknown unknown -> stillUnknown(charge, "the gateway cannot say what became of it", unknown);
            case NotFound notFound -> switch (gateway.voidUnsettled(charge.chargeReference())) {
                case Outcome decided -> Optional.of(decided);
                case Unknown unknown -> stillUnknown(charge,
                        "the gateway has no record of it and could not close it", unknown);
            };
        };
    }

    private Optional<Outcome> stillUnknown(ChargeInFlight charge, String what, Unknown unknown) {
        // ERROR, every pass, until it is settled: the shopper may have paid
        // and nothing says so yet. Staff can ask the gateway about the
        // reference directly.
        log.error("PaymentIntent id={} has been CHARGING since {}: {} (charge {}: {}). It stays CHARGING and is "
                + "asked about again on the next pass; if this persists, check the charge with the gateway by "
                + "its reference.", charge.intentId(), charge.since(), what, charge.chargeReference(),
                unknown.detail());
        return Optional.empty();
    }

    private void record(ChargeInFlight charge, Outcome outcome) {
        try {
            transaction.executeWithoutResult(status ->
                    settlement.settle(charge.intentId(), charge.chargeReference(), outcome, ACTOR));
        } catch (OptimisticLockingFailureException e) {
            // Someone else -- another pass, or the confirm itself -- recorded
            // this charge between our read and our write. Their record stands;
            // ours rolled back with nothing written.
            log.info("PaymentIntent id={}: charge {} was settled by another writer first; nothing more to record",
                    charge.intentId(), charge.chargeReference());
        }
    }
}
