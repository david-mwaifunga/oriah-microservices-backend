package com.oriah.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What payment needs from a card gateway. Today there is one implementation
 * ({@link FakePaymentGateway}); a real one (ZynlePay, Phase 13) plugs in here
 * without touching callers.
 *
 * <h2>Every call names a charge reference</h2>
 *
 * Payment chooses the reference, records it on the intent and commits that
 * BEFORE calling {@link #charge}. The gateway treats the reference as the
 * identity of the charge, so:
 *
 * <ul>
 *   <li>{@link #charge} is idempotent by reference: calling it again with the
 *       same reference returns the first answer and takes no more money;</li>
 *   <li>{@link #lookup} can be asked, later, what became of a reference
 *       whose answer never reached payment (a timeout, a crash);</li>
 *   <li>{@link #voidUnsettled} closes a reference the gateway has no outcome
 *       for, so that a charge still in flight can never be captured
 *       afterwards. It is authoritative: once a reference is VOIDED, every
 *       later charge of it answers VOIDED. A reference that already has an
 *       outcome keeps it, and the void answers with it: CAPTURED (a void
 *       never undoes a capture) or DECLINED.</li>
 * </ul>
 *
 * <h2>Outcomes carry the gateway's time</h2>
 *
 * Each decided outcome carries {@code at}, the moment the gateway decided.
 * That -- not the moment payment heard about it -- is when the payment
 * happened, and it is what payment records as the intent's confirmed time and
 * the event's occurred time, however late the answer is learned.
 *
 * <h2>A throw is not a decline</h2>
 *
 * {@link #charge} answers CAPTURED, DECLINED or (for a voided reference)
 * VOIDED. A timeout or any other exception means the outcome is unknown: the
 * money may or may not have been taken, and the caller must find out by
 * reference, never assume either.
 */
public interface PaymentGateway {

    ChargeResult charge(UUID chargeReference, String cardNumber, BigDecimal amount, String currency);

    LookupResult lookup(UUID chargeReference);

    VoidResult voidUnsettled(UUID chargeReference);

    /** What {@link #charge} can answer. */
    sealed interface ChargeResult permits Outcome {}

    /** What {@link #lookup} can answer. */
    sealed interface LookupResult permits Outcome, NotFound, Unknown {}

    /** What {@link #voidUnsettled} can answer. */
    sealed interface VoidResult permits Outcome, Unknown {}

    /**
     * A decision the gateway has made about one reference. Final: the same
     * reference gets the same outcome every time it is asked.
     */
    sealed interface Outcome extends ChargeResult, LookupResult, VoidResult permits Captured, Declined, Voided {
        Instant at();
    }

    /** The money was taken. {@code gatewayReference} is the gateway's own id for it. */
    record Captured(String gatewayReference, Instant at) implements Outcome {
        public Captured {
            if (gatewayReference == null || gatewayReference.isBlank()) {
                throw new IllegalArgumentException("A capture carries the gateway's reference for it");
            }
            requireTime(at);
        }
    }

    /** No money was taken, for {@code reason}. */
    record Declined(String reason, Instant at) implements Outcome {
        public Declined {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("A decline carries its reason");
            }
            requireTime(at);
        }
    }

    /** The reference was closed before any charge of it was decided; no money was or ever will be taken. */
    record Voided(Instant at) implements Outcome {
        public Voided {
            requireTime(at);
        }
    }

    /** The gateway has never seen a charge with this reference. */
    record NotFound() implements LookupResult {}

    /** The gateway could not say; ask again later. Never a reason to assume either way. */
    record Unknown(String detail) implements LookupResult, VoidResult {}

    private static void requireTime(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("An outcome carries the time the gateway decided it");
        }
    }
}
