package com.oriah.payment.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * When Oriah next asks ZynlePay what became of a payment ({@code oriah.payment.zynlepay.poll.*}).
 *
 * <p>Often while the customer is likely still approving it ({@code fast-every}, for {@code fast-for}: longer for a card page than for a prompt
 * on a phone), then every {@code slow-every}. When ZynlePay itself says "still deciding" it is left alone for {@code indeterminate-wait}. At
 * {@code give-up-after} with no answer the attempt becomes the owner's (UNRESOLVED): it is still asked about every {@code unresolved-every} until
 * {@code stop-after}, and it is <strong>never assumed failed</strong>. A failed attempt is asked about once more after {@code final-recheck}, to
 * catch ZynlePay contradicting itself.
 */
public record CheckSchedule(Duration firstCheckAfter, Duration fastEvery, Duration fastForMobileMoney, Duration fastForCard, Duration slowEvery,
                            Duration indeterminateWait, Duration giveUpAfter, Duration unresolvedEvery, Duration stopAfter, Duration finalRecheck) {

    public CheckSchedule {
        positive("first-check-after", firstCheckAfter);
        positive("fast-every", fastEvery);
        positive("fast-for.mobile-money", fastForMobileMoney);
        positive("fast-for.card", fastForCard);
        positive("slow-every", slowEvery);
        positive("indeterminate-wait", indeterminateWait);
        positive("give-up-after", giveUpAfter);
        positive("unresolved-every", unresolvedEvery);
        positive("stop-after", stopAfter);
        positive("final-recheck", finalRecheck);
        Duration longestApproval = fastForMobileMoney.compareTo(fastForCard) > 0 ? fastForMobileMoney : fastForCard;
        if (giveUpAfter.compareTo(longestApproval) < 0) {
            throw new IllegalArgumentException("give-up-after (" + giveUpAfter + ") is shorter than the time a customer is given to approve ("
                    + longestApproval + "): a payment would go to the owner while the customer may still be paying");
        }
    }

    public Instant first(Instant sentAt) {
        return sentAt.plus(firstCheckAfter);
    }

    /** The next question after one that brought no final answer. */
    public Instant afterNoNews(boolean card, Instant sentAt, Instant now, boolean zynlePaySaidPending) {
        Duration fastFor = card ? fastForCard : fastForMobileMoney;
        Duration wait = now.isBefore(sentAt.plus(fastFor)) ? fastEvery : slowEvery;
        if (zynlePaySaidPending && wait.compareTo(indeterminateWait) < 0) {
            wait = indeterminateWait;
        }
        return now.plus(wait);
    }

    /** Whether an attempt sent at {@code sentAt} has waited long enough to become the owner's. */
    public boolean givenUp(Instant sentAt, Instant now) {
        return !now.isBefore(sentAt.plus(giveUpAfter));
    }

    /** The next question about an unresolved attempt, or empty once it is no longer asked about (it stays listed for the owner). */
    public Optional<Instant> afterUnresolved(Instant unresolvedAt, Instant now) {
        return now.isBefore(unresolvedAt.plus(stopAfter)) ? Optional.of(now.plus(unresolvedEvery)) : Optional.empty();
    }

    public Instant finalRecheck(Instant failedAt) {
        return failedAt.plus(finalRecheck);
    }

    private static void positive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("oriah.payment.zynlepay.poll." + name + " must be a length of time above nothing, was " + value);
        }
    }
}
