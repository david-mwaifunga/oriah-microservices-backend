package com.oriah.payment.confirmation;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.access.AccessDeniedException;

/**
 * Which dates money may be said to have arrived on, and who may say it.
 *
 * <p>A confirmation is recorded at its TRUE date (the day the money arrived), never the day it was keyed, so the books are right. Anyone who
 * may confirm can date it up to 72 hours back (the weekend, a day the shop was shut); further back is a decision, so it needs BACKDATE_SALE
 * and a reason. It is a check in the service and not a path at the gateway, because it depends on a number in the body.
 */
public final class ArrivalWindow {

    /** Two services' clocks never agree exactly: an arrival this far ahead is "now". */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    static final int REASON_MIN = 5;
    static final int REASON_MAX = 200;

    private ArrivalWindow() {
    }

    /**
     * @param arrivedAt          the day and time the money reached Oriah
     * @param now                the time on the service's clock
     * @param window             how far back anyone who may confirm may date it (72 hours)
     * @param mayBackdate        whether the caller holds BACKDATE_SALE
     * @param backdateReason     why it is being recorded late, as typed
     * @return the reason to keep with the confirmation: null when none was needed, otherwise the trimmed text
     * @throws IllegalArgumentException (400) for an arrival in the future, or a late one with no reason of 5 to 200 characters
     * @throws AccessDeniedException (403) for a late one by someone without BACKDATE_SALE
     */
    public static String check(Instant arrivedAt, Instant now, Duration window, boolean mayBackdate, String backdateReason) {
        if (arrivedAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("money cannot have arrived in the future: " + arrivedAt + " is after " + now);
        }
        if (!arrivedAt.isBefore(now.minus(window))) {
            return null;
        }
        if (!mayBackdate) {
            throw new AccessDeniedException("money that arrived more than " + window.toHours() + " hours ago is recorded by someone who may back-date a sale");
        }
        String reason = backdateReason == null ? "" : backdateReason.strip();
        if (reason.length() < REASON_MIN || reason.length() > REASON_MAX) {
            throw new IllegalArgumentException("money that arrived more than " + window.toHours() + " hours ago needs a reason of " + REASON_MIN
                    + " to " + REASON_MAX + " characters");
        }
        return reason;
    }
}
