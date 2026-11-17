package com.oriah.payment.provider;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where one attempt to collect money through ZynlePay stands, and the only moves from there (Phase 13). The database repeats these rules in a
 * trigger; this is where they are written to be read.
 *
 * <pre>
 * STARTING   -> PENDING (sent) | FAILED(ORDER_REFUSED | NOT_SENT)                    nothing has reached ZynlePay yet
 * PENDING    -> VERIFIED | FAILED(NOT_SENT | PROVIDER_REFUSED | DECLINED) | UNRESOLVED(NO_ANSWER | AMOUNT_DIFFERS | SAME_MONEY_ON_RECORD)
 * UNRESOLVED -> VERIFIED | FAILED(DECLINED | MARKED_NOT_PAID) | UNRESOLVED(SAME_MONEY_ON_RECORD)
 * FAILED(PROVIDER_REFUSED | DECLINED | MARKED_NOT_PAID) -> UNRESOLVED(PAID_AFTER_FAILED)   ZynlePay later said "paid": the owner's, never the machine's
 * VERIFIED   -> UNRESOLVED(CONFIRMATION_VOIDED)                                      the owner cancelled its money record
 * </pre>
 *
 * <p><strong>Nothing ambiguous is a failure.</strong> An attempt fails only when nothing was sent, on a refusal or a "failed" ZynlePay itself gave,
 * or on the owner's word. Silence, an unknown code and "not found" leave it PENDING and then UNRESOLVED.
 *
 * <p><strong>The machine's word is taken once.</strong> Three kinds of UNRESOLVED are decided by the owner alone ({@link #OWNER_ONLY}): ZynlePay
 * would only repeat the answer that is already in doubt.
 *
 * <p><strong>Holding the order.</strong> From the moment it exists until it fails or is verified, an attempt holds its order: no second attempt
 * starts and staff record no other money for it. Once let go, it is never held again.
 */
public record AttemptState(Status status, FailureCode failureCode, UnresolvedReason unresolvedReason, boolean holdsOrder) {

    public enum Status { STARTING, PENDING, UNRESOLVED, VERIFIED, FAILED }

    public enum FailureCode {
        /** The order would not take a payment (cancelled, already being paid, its pieces no longer held). Nothing was sent. */
        ORDER_REFUSED,
        /** The request never left: no connection was made. */
        NOT_SENT,
        /** ZynlePay answered the request with a code listed as a definite refusal. */
        PROVIDER_REFUSED,
        /** ZynlePay's status call answered "failed". */
        DECLINED,
        /** The owner decided, from ZynlePay's own records, that no money came. */
        MARKED_NOT_PAID
    }

    public enum UnresolvedReason { NO_ANSWER, AMOUNT_DIFFERS, SAME_MONEY_ON_RECORD, CONFIRMATION_VOIDED, PAID_AFTER_FAILED }

    /** Decided by the owner alone: the machine neither asks ZynlePay about these nor acts on what it would say. */
    public static final Set<UnresolvedReason> OWNER_ONLY =
            EnumSet.of(UnresolvedReason.SAME_MONEY_ON_RECORD, UnresolvedReason.CONFIRMATION_VOIDED, UnresolvedReason.PAID_AFTER_FAILED);

    private static final Set<FailureCode> BEFORE_ANYTHING_WAS_SENT = EnumSet.of(FailureCode.ORDER_REFUSED, FailureCode.NOT_SENT);
    private static final Set<FailureCode> OF_A_SENT_ATTEMPT = EnumSet.of(FailureCode.NOT_SENT, FailureCode.PROVIDER_REFUSED, FailureCode.DECLINED);
    private static final Set<UnresolvedReason> OF_A_PENDING_ATTEMPT =
            EnumSet.of(UnresolvedReason.NO_ANSWER, UnresolvedReason.AMOUNT_DIFFERS, UnresolvedReason.SAME_MONEY_ON_RECORD);

    public AttemptState {
        if (status == null) {
            throw new IllegalArgumentException("an attempt has a status");
        }
        if ((status == Status.FAILED) != (failureCode != null)) {
            throw new IllegalArgumentException("a failure code belongs to a FAILED attempt, and every FAILED attempt has one");
        }
        if ((status == Status.UNRESOLVED) != (unresolvedReason != null)) {
            throw new IllegalArgumentException("a reason belongs to an UNRESOLVED attempt, and every UNRESOLVED attempt has one");
        }
        if (holdsOrder && (status == Status.FAILED || status == Status.VERIFIED)) {
            throw new IllegalArgumentException("a " + status + " attempt does not hold its order");
        }
        if (!holdsOrder && (status == Status.STARTING || status == Status.PENDING)) {
            throw new IllegalArgumentException("a " + status + " attempt holds its order");
        }
    }

    public static AttemptState starting() {
        return new AttemptState(Status.STARTING, null, null, true);
    }

    /** The order agreed and the amount is known: committed BEFORE the request goes out, so a crash never leaves a sent request unrecorded. */
    public AttemptState sent() {
        require(status == Status.STARTING, "be sent");
        return new AttemptState(Status.PENDING, null, null, true);
    }

    public AttemptState failed(FailureCode code) {
        boolean allowed = switch (status) {
            case STARTING -> BEFORE_ANYTHING_WAS_SENT.contains(code);
            case PENDING -> OF_A_SENT_ATTEMPT.contains(code);
            case UNRESOLVED -> code == FailureCode.MARKED_NOT_PAID || (code == FailureCode.DECLINED && !OWNER_ONLY.contains(unresolvedReason));
            case VERIFIED, FAILED -> false;
        };
        require(allowed, "fail as " + code);
        return new AttemptState(Status.FAILED, code, null, false);
    }

    public AttemptState unresolved(UnresolvedReason reason) {
        boolean allowed = switch (status) {
            case PENDING -> OF_A_PENDING_ATTEMPT.contains(reason);
            case UNRESOLVED -> reason == UnresolvedReason.SAME_MONEY_ON_RECORD && !OWNER_ONLY.contains(unresolvedReason);
            case STARTING, VERIFIED, FAILED -> false;
        };
        require(allowed, "go unresolved as " + reason);
        return new AttemptState(Status.UNRESOLVED, null, reason, holdsOrder);
    }

    /** @param byTheOwner true for the owner's recorded decision; false for ZynlePay's own "paid" answer to our status call */
    public AttemptState verified(boolean byTheOwner) {
        boolean allowed = switch (status) {
            case PENDING -> !byTheOwner;
            case UNRESOLVED -> byTheOwner || !OWNER_ONLY.contains(unresolvedReason);
            case STARTING, VERIFIED, FAILED -> false;
        };
        require(allowed, byTheOwner ? "be marked paid by the owner" : "be verified");
        return new AttemptState(Status.VERIFIED, null, null, false);
    }

    /** ZynlePay said "paid" of an attempt that had failed. Never verified by the machine: recorded for the owner, holding no order. */
    public AttemptState paidAfterFailed() {
        require(status == Status.FAILED && !nothingReachedZynlePay(), "be reported paid after failing");
        return new AttemptState(Status.UNRESOLVED, null, UnresolvedReason.PAID_AFTER_FAILED, false);
    }

    /** The owner cancelled the money record of a verified attempt: the attempt is the owner's to decide again. */
    public AttemptState confirmationVoided() {
        require(status == Status.VERIFIED, "have its money record cancelled");
        return new AttemptState(Status.UNRESOLVED, null, UnresolvedReason.CONFIRMATION_VOIDED, false);
    }

    /** Whether there is any point, and any right, in asking ZynlePay what became of it. */
    public boolean mayBeAsked() {
        return switch (status) {
            case PENDING -> true;
            case UNRESOLVED -> !OWNER_ONLY.contains(unresolvedReason);
            case FAILED -> !nothingReachedZynlePay();
            case STARTING, VERIFIED -> false;
        };
    }

    /**
     * A failure that proves nothing reached ZynlePay. {@code NOT_SENT} is also the code of a sent attempt whose connection was refused, which
     * equally reached nobody: both are final.
     */
    private boolean nothingReachedZynlePay() {
        return BEFORE_ANYTHING_WAS_SENT.contains(failureCode);
    }

    private void require(boolean allowed, String move) {
        if (!allowed) {
            throw new IllegalStateException("a " + status + (failureCode != null ? "(" + failureCode + ")" : "")
                    + (unresolvedReason != null ? "(" + unresolvedReason + ")" : "") + " attempt cannot " + move);
        }
    }
}
