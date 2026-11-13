package com.oriah.payment.service;

import java.io.UncheckedIOException;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.payment.dto.PaymentIntentResponse;
import com.oriah.payment.events.PaymentEvents;
import com.oriah.payment.gateway.PaymentGateway.Captured;
import com.oriah.payment.gateway.PaymentGateway.Declined;
import com.oriah.payment.gateway.PaymentGateway.Outcome;
import com.oriah.payment.gateway.PaymentGateway.Voided;
import com.oriah.payment.model.IdempotencyKey;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Records the gateway's answer about one charge: the second transaction of
 * confirm, and the transaction in which {@link PaymentReconciler} records an
 * answer that never reached confirm.
 *
 * <p>In one transaction, in this order:
 * <ol>
 *   <li>the intent, if it is still CHARGING with this reference, takes the
 *       outcome -- CAPTURED to SUCCEEDED with the gateway's reference, DECLINED
 *       to FAILED with its reason, VOIDED to FAILED "charge not completed" --
 *       each at the gateway's decision time; version-checked;</li>
 *   <li>any Idempotency-Key RESERVED for the intent is COMPLETED with the
 *       response it will replay;</li>
 *   <li>the outcome is appended to the outbox, last.</li>
 * </ol>
 * All of it commits, or none of it does. If none of it does, the intent is
 * still CHARGING: nothing can charge it again, and the outcome is still the
 * gateway's to tell, by reference.
 *
 * <p>Safe to run twice for the same charge: an intent that is no longer
 * CHARGING with this reference was already settled, so nothing is recorded
 * again and no second event is written.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty("oriah.payment.card.enabled")
@Component
@Slf4j
public class ChargeSettlement {

    /** The failure reason for a charge voided before the gateway decided it. */
    public static final String CHARGE_NOT_COMPLETED = "charge not completed";

    private final PaymentIntentRepository intents;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final PaymentEvents events;
    private final ObjectMapper objectMapper;

    public ChargeSettlement(PaymentIntentRepository intents, IdempotencyKeyRepository idempotencyKeys,
                            PaymentEvents events, ObjectMapper objectMapper) {
        this.intents = intents;
        this.idempotencyKeys = idempotencyKeys;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * @param actor who settled it, for the event: {@code user:<subject>} for
     *              a shopper's confirm, {@code system:<name>} for a job
     * @return the intent as it now stands
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentIntent settle(UUID intentId, UUID chargeReference, Outcome outcome, String actor) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow(() -> new IllegalStateException(
                "PaymentIntent " + intentId + " vanished while its charge " + chargeReference + " was in flight"));

        boolean awaitingThisCharge = intent.status() == PaymentStatus.CHARGING
                && chargeReference.equals(intent.chargeReference());
        if (awaitingThisCharge) {
            switch (outcome) {
                case Captured captured -> intent.markSucceeded(captured.gatewayReference(), captured.at());
                case Declined declined -> intent.markFailed(declined.reason(), declined.at());
                case Voided voided -> intent.markFailed(CHARGE_NOT_COMPLETED, voided.at());
            }
            // Flushed now: the version check, and any constraint, fails here,
            // before the outbox's lock is taken.
            intents.saveAndFlush(intent);
            log.info("PaymentIntent id={} {} (charge {}, decided at {})",
                    intent.id(), intent.status(), chargeReference, outcome.at());
        } else {
            log.info("PaymentIntent id={} is {} with charge {}; charge {} was already settled, nothing to record",
                    intent.id(), intent.status(), intent.chargeReference(), chargeReference);
        }

        completeReservedKeys(intent);

        if (awaitingThisCharge) {
            events.outcomeRecorded(intent, actor);
        }
        return intent;
    }

    private void completeReservedKeys(PaymentIntent intent) {
        var reserved = idempotencyKeys.findByIntentIdAndState(intent.id(), IdempotencyKey.State.RESERVED);
        if (reserved.isEmpty()) {
            return;
        }
        String response = json(PaymentIntentResponse.from(intent));
        reserved.forEach(key -> key.complete(response));
        idempotencyKeys.flush();
    }

    private String json(PaymentIntentResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
