package com.oriah.payment.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.payment.dto.CreateIntentRequest;
import com.oriah.payment.dto.PaymentIntentResponse;
import com.oriah.payment.gateway.PaymentGateway;
import com.oriah.payment.gateway.PaymentGateway.ChargeResult;
import com.oriah.payment.gateway.PaymentGateway.Outcome;
import com.oriah.payment.model.IdempotencyKey;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PaymentService {

    /** The partial unique index that allows one live intent per order (V5). */
    private static final String ONE_LIVE_INTENT_PER_ORDER = "uq_payment_intents_one_live_per_order";

    private final PaymentIntentRepository repository;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final PaymentGateway gateway;
    private final ChargeSettlement settlement;
    private final OrderOwnership orderOwnership;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentIntentRepository repository,
                          IdempotencyKeyRepository idempotencyKeys,
                          PaymentGateway gateway,
                          ChargeSettlement settlement,
                          OrderOwnership orderOwnership,
                          PlatformTransactionManager transactionManager,
                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotencyKeys = idempotencyKeys;
        this.gateway = gateway;
        this.settlement = settlement;
        this.orderOwnership = orderOwnership;
        this.transaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * What {@link #createIntent} did: made a new intent ({@code created}), or
     * found the very one the caller asked for already made -- 201 or 200.
     */
    public record CreatedIntent(PaymentIntent intent, boolean created) {}

    /**
     * A PENDING intent for one of the caller's own orders, for its total:
     * made new, or -- when the caller already has exactly that intent -- the
     * one already made. Refused (409) while the order has any other live
     * intent: pending for a different amount or currency or for somebody
     * else, being charged, or paid.
     *
     * <h2>Whose order, and for how much</h2>
     *
     * Order is asked first ({@link OrderOwnership}): not the caller's order or
     * no such order is a 404, an order no longer waiting for payment
     * (cancelled, or already paid) a 409, the wrong amount or currency a 400,
     * order not answering a 503 -- and nothing is written in any of them. Without it any
     * signed-in customer who knew an order id could make the order's one live
     * intent, and the owner's payment would be refused while it stood. The
     * call is made before any transaction is opened, so no database
     * connection is held while order is waited on.
     *
     * <h2>The same request twice gets the same intent</h2>
     *
     * Checkout retries this call up to three times. When the first attempt is
     * saved but its answer is lost on the way back, the retry finds the
     * caller's own PENDING intent for the same amount and currency and is
     * given it (200), rather than a 409 that would leave checkout without the
     * intent's id and the order unpayable. Only that exact intent is handed
     * back: one being charged or paid is never offered for confirming again,
     * and one for another amount, another currency or another person is not
     * the intent this request asked for.
     *
     * <h2>One live intent per order</h2>
     *
     * Two live intents for one order would be two ways to take the same
     * money: two confirms, two captures. The check here gives the caller its
     * answer; the partial unique index uq_payment_intents_one_live_per_order
     * is what actually holds when two requests pass the check at the same
     * moment. The loser's insert is refused, its transaction rolled back, and
     * it reads the winner's intent afresh and gets the same answer a later
     * request would: that intent (200), or the 409.
     */
    public CreatedIntent createIntent(String keycloakId, CreateIntentRequest req) {
        orderOwnership.requireCallersOwn(req.orderId(), req.amount(), req.currency());
        try {
            return transaction.execute(status -> findOrInsert(keycloakId, req));
        } catch (DataIntegrityViolationException e) {
            if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains(ONE_LIVE_INTENT_PER_ORDER)) {
                throw e;
            }
            // A request for the same order inserted first and committed. A
            // fresh transaction sees its intent -- this one was aborted with
            // the refused insert. Should that intent have stopped being live
            // in the meantime, there is nothing to hand back: 409, and the
            // caller may try again.
            log.info("PaymentIntent for orderId={}: another request made the order's live intent first",
                    req.orderId());
            return transaction.execute(status -> repository
                    .findByOrderIdAndStatusIn(req.orderId(), PaymentStatus.LIVE)
                    .map(live -> handBackOrRefuse(live, keycloakId, req, e))
                    .orElseThrow(() -> orderAlreadyHasALiveIntent(req.orderId(), e)));
        }
    }

    /** Runs inside {@link #transaction}. */
    private CreatedIntent findOrInsert(String keycloakId, CreateIntentRequest req) {
        Optional<PaymentIntent> live = repository.findByOrderIdAndStatusIn(req.orderId(), PaymentStatus.LIVE);
        if (live.isPresent()) {
            return handBackOrRefuse(live.get(), keycloakId, req, null);
        }
        // No default. CreateIntentRequest requires a currency, so there is
        // nothing to substitute -- and the substitute was "USD" on a platform
        // priced in Kwacha, written here AND in the PaymentIntent constructor,
        // which is how two defaults drift apart.
        PaymentIntent intent = new PaymentIntent(
                req.orderId(),
                keycloakId,
                req.amount(),
                req.currency()
        );
        // Flushed here so a lost race surfaces inside the transaction, where
        // createIntent can tell it apart from any other failure.
        PaymentIntent saved = repository.saveAndFlush(intent);
        log.info("Created PaymentIntent id={} orderId={} amount={} {}",
                saved.id(), saved.orderId(), saved.amount(), saved.currency());
        return new CreatedIntent(saved, true);
    }

    /**
     * The order's live intent, given back only when it is exactly what this
     * request asks for: PENDING, the caller's own, the same amount (compared
     * as an amount, so 4500 and 4500.00 are one) and the same currency.
     * Anything else is the 409.
     */
    private static CreatedIntent handBackOrRefuse(PaymentIntent live, String keycloakId, CreateIntentRequest req,
                                                  Exception cause) {
        boolean theSameIntent = live.status() == PaymentStatus.PENDING
                && live.keycloakId().equals(keycloakId)
                && live.amount().compareTo(req.amount()) == 0
                && live.currency().equals(req.currency());
        if (!theSameIntent) {
            throw orderAlreadyHasALiveIntent(req.orderId(), cause);
        }
        log.info("PaymentIntent id={} orderId={}: the same create again; answering with the intent already made",
                live.id(), live.orderId());
        return new CreatedIntent(live, false);
    }

    /**
     * Deliberately does not name the existing intent: it may be somebody
     * else's (one made before payment asked order whose order it was), and
     * then it is not the caller's to know about.
     */
    private static IllegalStateException orderAlreadyHasALiveIntent(UUID orderId, Exception cause) {
        return new IllegalStateException("Order " + orderId + " already has a payment intent that is pending, "
                + "being charged or paid. A new one can be made only if that one fails.", cause);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentIntent> findById(UUID id) {
        return repository.findById(id);
    }

    /**
     * Charges the card for one of the caller's own intents, and records what
     * the gateway said.
     *
     * <h2>No transaction is held across the gateway call</h2>
     *
     * It used to be one transaction around the charge, and that is how money
     * went wrong: a failure after the charge rolled the intent back to
     * PENDING with no trace of the charge, so the next confirm charged the
     * card again; and two confirms at once both read PENDING and both
     * charged. Now there are three steps, and the first one commits before
     * any money moves:
     *
     * <ol>
     *   <li><b>tx1</b> ({@link #begin}): the owner check; the Idempotency-Key,
     *       if sent, reserved; PENDING -&gt; CHARGING with a new charge
     *       reference, version-checked. Committed -- from here on the intent
     *       says a charge is in flight, and nothing can start another.</li>
     *   <li><b>the charge</b>, with no transaction open.</li>
     *   <li><b>tx2</b> ({@link ChargeSettlement#settle}): the gateway's answer
     *       recorded, its event appended, the key completed.</li>
     * </ol>
     *
     * A timeout or exception from the gateway is not a decline: the money may
     * have been taken. There is no tx2; the intent stays CHARGING and the
     * caller gets 503 "outcome unknown". The same 503 if tx2 itself fails
     * after the gateway answered -- unless it failed because the reconciler
     * recorded the same answer first, in which case the caller is given that
     * record ({@link #record}). Either way the intent can never be charged
     * again -- every retry is a 409 -- and {@link PaymentReconciler} settles
     * it by asking the gateway about its charge reference.
     *
     * <h2>The owner is a parameter, and that is the fix</h2>
     *
     * This method used to take only the id, so it found the intent, called the
     * gateway and marked the status with no idea who was asking. The caller
     * checked ownership on the RESULT -- after the money had moved. One
     * customer confirming another's intent settled it for real and received a
     * 404. {@code ConfirmAuthorizationTest} pins that the victim's intent is
     * still PENDING afterwards. The ownership decision is the first thing tx1
     * does, upstream of the irreversible step, where no caller can reorder
     * the two.
     *
     * @param idempotencyKey optional; when sent, a repeat of this request is
     *                       served the first one's response instead of being
     *                       charged again
     * @return empty when no such intent exists OR it belongs to someone else
     *         (also when the key is bound to someone else's intent). These are
     *         deliberately indistinguishable, so a 404 cannot confirm that
     *         another customer's intent id is real.
     * @throws IllegalStateException (409) the intent is not PENDING (being
     *         charged, or already settled); the key is bound to another of the
     *         caller's intents; or a request with this key is still in progress
     * @throws PaymentOutcomeUnknownException (503) the outcome is not known yet
     */
    public Optional<PaymentIntentResponse> confirm(UUID intentId, String keycloakId, String cardNumber,
                                                   String idempotencyKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // A caller's transaction would swallow tx1: CHARGING would not be
            // committed before the charge, which is the defect this method
            // exists to prevent.
            throw new IllegalStateException("PaymentService.confirm must not be called inside a transaction: "
                    + "the intent has to be committed as CHARGING before the gateway is called");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
        if (key != null && key.length() > IdempotencyKey.MAX_LENGTH) {
            // Refused here, before anything is charged. It used to fail only
            // when the key was saved -- after the charge.
            throw new IllegalArgumentException("Idempotency-Key is " + key.length() + " characters; at most "
                    + IdempotencyKey.MAX_LENGTH + " are accepted");
        }

        Begun begun = transaction.execute(status -> begin(intentId, keycloakId, key));
        return switch (begun) {
            case NotYours notYours -> Optional.empty();
            case Replay replay -> Optional.of(replay.response());
            case Charging charging -> Optional.of(chargeAndRecord(charging, cardNumber, "user:" + keycloakId));
        };
    }

    /** What tx1 decided. */
    private sealed interface Begun permits NotYours, Replay, Charging {}

    private record NotYours() implements Begun {}

    /** The key was already COMPLETED for this intent: serve its response, charge nothing. */
    private record Replay(PaymentIntentResponse response) implements Begun {}

    /** Committed as CHARGING with {@code chargeReference}: go and charge it. */
    private record Charging(UUID intentId, UUID chargeReference, BigDecimal amount, String currency)
            implements Begun {}

    /** tx1. Runs inside {@link #transaction}. */
    private Begun begin(UUID intentId, String keycloakId, String key) {
        Optional<PaymentIntent> owned = repository.findById(intentId)
                .filter(intent -> intent.keycloakId().equals(keycloakId));
        if (owned.isEmpty()) {
            return new NotYours();
        }
        PaymentIntent intent = owned.get();

        if (key != null && idempotencyKeys.reserve(key, intentId, keycloakId) == 0) {
            IdempotencyKey existing = idempotencyKeys.findById(key).orElseThrow(() -> new IllegalStateException(
                    "Idempotency-Key " + key + " was refused as a duplicate but cannot be read"));
            if (!existing.intentId().equals(intentId)) {
                // Ownership of the BOUND intent first: a caller who does not
                // own it gets the same 404 as for an unknown intent, so
                // presenting someone else's key never answers "that key
                // exists". Only an owner learns, by a 409, that they reused
                // their own key on a different intent -- refused rather than
                // replayed, because replaying the first intent's SUCCEEDED
                // answer would report the second paid when it never was.
                boolean ownsBoundIntent = repository.findById(existing.intentId())
                        .map(bound -> bound.keycloakId().equals(keycloakId))
                        .orElse(false);
                if (!ownsBoundIntent) {
                    log.warn("Idempotency-Key={} presented by a subject that does not own the intent it is "
                            + "bound to; refusing against intent={}", key, intentId);
                    return new NotYours();
                }
                log.warn("Idempotency-Key={} is bound to intent={}, not to intent={}; refusing",
                        key, existing.intentId(), intentId);
                throw new IllegalStateException("This Idempotency-Key is already associated with a different "
                        + "payment intent. Use a fresh key for a new confirmation.");
            }
            if (existing.state() == IdempotencyKey.State.RESERVED) {
                // The first request with this key is at the gateway, or its
                // outcome is not settled yet. Refused rather than waited on:
                // an unknown outcome can take a while to settle, and a repeat
                // once it has is served its response.
                throw new IllegalStateException("A confirmation with this Idempotency-Key is still in progress "
                        + "for this payment intent. Repeat the request once it has finished to receive its "
                        + "result.");
            }
            log.info("Idempotency-Key={} hit, replaying intent={} (no gateway charge)", key, intentId);
            return new Replay(storedResponse(existing));
        }

        // Refused (409) unless PENDING: an intent being charged has a charge in
        // flight, and a settled one has had its answer. Deliberately a 409
        // rather than an idempotent 200 for a settled intent: a retry WITHOUT
        // an Idempotency-Key must not be told "fine" about a charge this
        // request did not make. A refusal here rolls the key reservation back
        // with it.
        intent.startCharging(UUID.randomUUID());
        // Flushed inside tx1, so the version check happens here: of two
        // requests that both read PENDING, the second's update finds the
        // version moved and is refused (409) before it can charge.
        repository.saveAndFlush(intent);
        return new Charging(intent.id(), intent.chargeReference(), intent.amount(), intent.currency());
    }

    /** The charge, outside any transaction, then tx2. */
    private PaymentIntentResponse chargeAndRecord(Charging charging, String cardNumber, String actor) {
        ChargeResult result;
        try {
            result = gateway.charge(charging.chargeReference(), cardNumber, charging.amount(), charging.currency());
        } catch (RuntimeException e) {
            log.error("PaymentIntent id={}: the gateway gave no answer for charge {}. The outcome is unknown; "
                    + "the intent stays CHARGING until it is settled by that reference.",
                    charging.intentId(), charging.chargeReference(), e);
            throw new PaymentOutcomeUnknownException(charging.intentId(), e);
        }
        Outcome outcome = switch (result) {
            case Outcome decided -> decided;
        };

        try {
            return PaymentIntentResponse.from(record(charging, outcome, actor));
        } catch (RuntimeException e) {
            // The gateway HAS answered -- for a capture, the money is taken --
            // and it could not be recorded. The intent stays CHARGING, so it
            // is never charged again, and the gateway's record of the charge
            // reference is what settles it.
            log.error("PaymentIntent id={}: the gateway answered {} for charge {} but recording it failed. "
                    + "The intent stays CHARGING until it is settled by that reference.",
                    charging.intentId(), outcome, charging.chargeReference(), e);
            throw new PaymentOutcomeUnknownException(charging.intentId(), e);
        }
    }

    /**
     * tx2, and once more if it lost a race to the reconciler.
     *
     * <p>The only other writer of a CHARGING intent is {@link PaymentReconciler},
     * settling the same charge reference. If it commits between tx2's read of
     * the intent and tx2's update, the version check refuses tx2. That is not
     * an unknown outcome: the gateway gives one answer per reference, so the
     * reconciler recorded the very answer this confirm holds. Settling again,
     * in a fresh transaction, finds the intent settled, records nothing and
     * returns it as it stands -- what this confirm would have answered had it
     * read a moment later. Any failure of that second attempt is the caller's
     * 503, as for any other failure to record.
     */
    private PaymentIntent record(Charging charging, Outcome outcome, String actor) {
        try {
            return transaction.execute(status ->
                    settlement.settle(charging.intentId(), charging.chargeReference(), outcome, actor));
        } catch (OptimisticLockingFailureException e) {
            log.info("PaymentIntent id={}: charge {} was recorded by another writer first; answering with that "
                    + "record", charging.intentId(), charging.chargeReference());
            return transaction.execute(status ->
                    settlement.settle(charging.intentId(), charging.chargeReference(), outcome, actor));
        }
    }

    private PaymentIntentResponse storedResponse(IdempotencyKey key) {
        try {
            return objectMapper.readValue(key.responseJson(), PaymentIntentResponse.class);
        } catch (IOException e) {
            // A COMPLETED key whose response cannot be read is a defect, not a
            // reason to charge again: the intent has been settled.
            throw new UncheckedIOException(
                    "Idempotency-Key " + key.key() + " holds a response that cannot be read", e);
        }
    }
}
