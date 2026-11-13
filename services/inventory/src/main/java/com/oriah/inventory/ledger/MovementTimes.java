package com.oriah.inventory.ledger;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * When a stock change happened: one rule, in one place.
 *
 * <ul>
 *   <li>Left out, it is now. Every time is kept to the microsecond, which is all the database keeps.</li>
 *   <li>It is never in the future (five minutes of allowance for clocks that disagree).</li>
 *   <li>Where the stock could not have left before it arrived (a write-off, a give-away, a take-back), it is never
 *       before the lot it touches arrived: the caller passes that time as {@code notBefore}. A paid sale is never
 *       refused for its date, so a sale passes none.</li>
 * </ul>
 */
public final class MovementTimes {

    /** How far ahead of this server's clock a date may be before it is called the future. */
    static final Duration CLOCK_ALLOWANCE = Duration.ofMinutes(5);

    private MovementTimes() {
    }

    /**
     * @param requested what the caller sent, or null
     * @param notBefore the earliest the movement could have happened, or null for no limit
     * @return the instant to record
     * @throws IllegalArgumentException (a 400) when the date breaks the rule
     */
    public static Instant resolve(Instant requested, Instant now, Instant notBefore) {
        if (requested == null) {
            return now.truncatedTo(ChronoUnit.MICROS);
        }
        if (requested.isAfter(now.plus(CLOCK_ALLOWANCE))) {
            throw new IllegalArgumentException("occurredAt " + requested + " is in the future");
        }
        if (notBefore != null && requested.isBefore(notBefore)) {
            throw new IllegalArgumentException("occurredAt " + requested + " is before the stock arrived (" + notBefore + ")");
        }
        // The database keeps microseconds. Kept to the same, so what is recorded is what is answered when it is asked again.
        return requested.truncatedTo(ChronoUnit.MICROS);
    }
}
