package com.oriah.payment.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.payment.provider.AttemptState.FailureCode;
import com.oriah.payment.provider.AttemptState.Status;
import com.oriah.payment.provider.AttemptState.UnresolvedReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The states of one attempt to collect money through ZynlePay, and the only moves between them (Phase 13).
 *
 * <p>Three rules are in these moves. Nothing ambiguous is ever a failure. A failed attempt is never made "paid" by the machine: if ZynlePay later
 * says it was paid, it goes to the owner. And an attempt "holds the order" (no other money may be recorded for it, no second attempt starts)
 * from the moment it exists until it fails or is verified, and never again after.
 */
class AttemptStateTest {

    private static AttemptState pending() {
        return AttemptState.starting().sent();
    }

    private static AttemptState unresolved(UnresolvedReason reason) {
        return pending().unresolved(reason);
    }

    @Test
    @DisplayName("a new attempt is STARTING and holds its order")
    void aNewAttempt() {
        AttemptState state = AttemptState.starting();

        assertThat(state.status()).isEqualTo(Status.STARTING);
        assertThat(state.holdsOrder()).isTrue();
        assertThat(state.failureCode()).isNull();
        assertThat(state.unresolvedReason()).isNull();
        assertThat(state.mayBeAsked()).isFalse();           // nothing was sent: there is nothing to ask ZynlePay about
    }

    @Test
    @DisplayName("before anything is sent it can only be sent, be refused by the order, or never be sent: those two failures are final")
    void fromStarting() {
        assertThat(AttemptState.starting().sent().status()).isEqualTo(Status.PENDING);
        for (FailureCode code : new FailureCode[] {FailureCode.ORDER_REFUSED, FailureCode.NOT_SENT}) {
            AttemptState failed = AttemptState.starting().failed(code);
            assertThat(failed.status()).isEqualTo(Status.FAILED);
            assertThat(failed.holdsOrder()).isFalse();
            assertThat(failed.mayBeAsked()).as("nothing reached ZynlePay").isFalse();
            assertThatThrownBy(() -> failed.paidAfterFailed()).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> AttemptState.starting().failed(FailureCode.DECLINED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AttemptState.starting().verified(false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AttemptState.starting().unresolved(UnresolvedReason.NO_ANSWER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a pending attempt is verified, fails on a definite answer, or goes unresolved: it is never failed for want of an answer")
    void fromPending() {
        assertThat(pending().holdsOrder()).isTrue();
        assertThat(pending().mayBeAsked()).isTrue();

        AttemptState verified = pending().verified(false);
        assertThat(verified.status()).isEqualTo(Status.VERIFIED);
        assertThat(verified.holdsOrder()).isFalse();
        assertThat(verified.mayBeAsked()).isFalse();

        for (FailureCode code : new FailureCode[] {FailureCode.NOT_SENT, FailureCode.PROVIDER_REFUSED, FailureCode.DECLINED}) {
            assertThat(pending().failed(code).holdsOrder()).isFalse();
        }
        assertThatThrownBy(() -> pending().failed(FailureCode.ORDER_REFUSED)).isInstanceOf(IllegalStateException.class);
        // Only the owner says "not paid"; and only of an attempt that already went to the owner.
        assertThatThrownBy(() -> pending().failed(FailureCode.MARKED_NOT_PAID)).isInstanceOf(IllegalStateException.class);

        for (UnresolvedReason reason : new UnresolvedReason[] {UnresolvedReason.NO_ANSWER, UnresolvedReason.AMOUNT_DIFFERS,
                UnresolvedReason.SAME_MONEY_ON_RECORD}) {
            AttemptState state = pending().unresolved(reason);
            assertThat(state.status()).isEqualTo(Status.UNRESOLVED);
            assertThat(state.unresolvedReason()).isEqualTo(reason);
            assertThat(state.holdsOrder()).as("an unanswered attempt still holds the order: money may yet come").isTrue();
        }
        assertThatThrownBy(() -> pending().unresolved(UnresolvedReason.CONFIRMATION_VOIDED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending().unresolved(UnresolvedReason.PAID_AFTER_FAILED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending().sent()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an attempt ZynlePay has not answered can still be settled by ZynlePay's own answer, or by the owner")
    void unresolvedForWantOfAnAnswer() {
        for (UnresolvedReason reason : new UnresolvedReason[] {UnresolvedReason.NO_ANSWER, UnresolvedReason.AMOUNT_DIFFERS}) {
            assertThat(unresolved(reason).mayBeAsked()).isTrue();
            assertThat(unresolved(reason).verified(false).status()).isEqualTo(Status.VERIFIED);
            assertThat(unresolved(reason).verified(true).status()).isEqualTo(Status.VERIFIED);
            assertThat(unresolved(reason).failed(FailureCode.DECLINED).status()).isEqualTo(Status.FAILED);
            assertThat(unresolved(reason).failed(FailureCode.MARKED_NOT_PAID).holdsOrder()).isFalse();
            assertThat(unresolved(reason).unresolved(UnresolvedReason.SAME_MONEY_ON_RECORD).unresolvedReason()).isEqualTo(UnresolvedReason.SAME_MONEY_ON_RECORD);
        }
        // Unanswered, then ZynlePay says paid for another amount: it stays the owner's, and now says why. Never back to "no answer".
        assertThat(unresolved(UnresolvedReason.NO_ANSWER).unresolved(UnresolvedReason.AMOUNT_DIFFERS).unresolvedReason()).isEqualTo(UnresolvedReason.AMOUNT_DIFFERS);
        assertThat(unresolved(UnresolvedReason.NO_ANSWER).unresolved(UnresolvedReason.AMOUNT_DIFFERS).holdsOrder()).isTrue();
        for (UnresolvedReason back : new UnresolvedReason[] {UnresolvedReason.NO_ANSWER, UnresolvedReason.AMOUNT_DIFFERS}) {
            assertThatThrownBy(() -> unresolved(UnresolvedReason.AMOUNT_DIFFERS).unresolved(back)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("a failed attempt ZynlePay later calls paid is never verified by the machine: it goes to the owner, and holds no order")
    void paidAfterFailed() {
        for (FailureCode code : new FailureCode[] {FailureCode.PROVIDER_REFUSED, FailureCode.DECLINED}) {
            AttemptState failed = pending().failed(code);
            assertThat(failed.mayBeAsked()).as("%s can still be asked about, to detect a contradiction", code).isTrue();
            assertThatThrownBy(() -> failed.verified(false)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> failed.verified(true)).isInstanceOf(IllegalStateException.class);

            AttemptState late = failed.paidAfterFailed();
            assertThat(late.status()).isEqualTo(Status.UNRESOLVED);
            assertThat(late.unresolvedReason()).isEqualTo(UnresolvedReason.PAID_AFTER_FAILED);
            assertThat(late.failureCode()).isNull();
            assertThat(late.holdsOrder()).as("another attempt may hold the order by now").isFalse();
            // Only the owner decides it: the machine's word is not taken twice.
            assertThat(late.mayBeAsked()).isFalse();
            assertThatThrownBy(() -> late.verified(false)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> late.failed(FailureCode.DECLINED)).isInstanceOf(IllegalStateException.class);
            assertThat(late.verified(true).status()).isEqualTo(Status.VERIFIED);
            assertThat(late.failed(FailureCode.MARKED_NOT_PAID).status()).isEqualTo(Status.FAILED);
        }
        AttemptState markedNotPaid = unresolved(UnresolvedReason.NO_ANSWER).failed(FailureCode.MARKED_NOT_PAID);
        assertThat(markedNotPaid.paidAfterFailed().unresolvedReason()).isEqualTo(UnresolvedReason.PAID_AFTER_FAILED);
    }

    @Test
    @DisplayName("money the same as a record already in the books is the owner's to decide, and the machine leaves it alone")
    void sameMoneyOnRecord() {
        AttemptState state = unresolved(UnresolvedReason.SAME_MONEY_ON_RECORD);

        assertThat(state.mayBeAsked()).isFalse();
        assertThatThrownBy(() -> state.verified(false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.failed(FailureCode.DECLINED)).isInstanceOf(IllegalStateException.class);
        assertThat(state.verified(true).status()).isEqualTo(Status.VERIFIED);
        assertThat(state.failed(FailureCode.MARKED_NOT_PAID).status()).isEqualTo(Status.FAILED);
    }

    @Test
    @DisplayName("a verified attempt changes only when the owner cancels its money record: then the owner decides it again, and the machine never does")
    void confirmationVoided() {
        AttemptState verified = pending().verified(false);
        assertThatThrownBy(() -> verified.failed(FailureCode.DECLINED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> verified.unresolved(UnresolvedReason.NO_ANSWER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> verified.verified(true)).isInstanceOf(IllegalStateException.class);

        AttemptState voided = verified.confirmationVoided();
        assertThat(voided.status()).isEqualTo(Status.UNRESOLVED);
        assertThat(voided.unresolvedReason()).isEqualTo(UnresolvedReason.CONFIRMATION_VOIDED);
        assertThat(voided.holdsOrder()).isFalse();
        assertThat(voided.mayBeAsked()).as("ZynlePay would only say 'paid' again").isFalse();
        assertThatThrownBy(() -> voided.verified(false)).isInstanceOf(IllegalStateException.class);
        assertThat(voided.verified(true).status()).isEqualTo(Status.VERIFIED);

        AttemptState notPaid = voided.failed(FailureCode.MARKED_NOT_PAID);
        assertThat(notPaid.status()).isEqualTo(Status.FAILED);
        assertThatThrownBy(() -> pending().confirmationVoided()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("once an attempt has let go of its order it never holds it again")
    void holdingIsNeverRegained() {
        assertThat(pending().failed(FailureCode.DECLINED).paidAfterFailed().verified(true).holdsOrder()).isFalse();
        assertThat(pending().verified(false).confirmationVoided().verified(true).confirmationVoided().holdsOrder()).isFalse();
        assertThatThrownBy(() -> new AttemptState(Status.FAILED, FailureCode.DECLINED, null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttemptState(Status.VERIFIED, null, null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttemptState(Status.FAILED, null, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttemptState(Status.UNRESOLVED, null, null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttemptState(Status.PENDING, FailureCode.DECLINED, null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttemptState(Status.STARTING, null, null, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
