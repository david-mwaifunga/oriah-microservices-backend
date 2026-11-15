package com.oriah.order.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.SaleKind;

/**
 * The date a staff-recorded sale is made at, and who may say it (Phase 9, design r4 section A). A pure rule: no Spring, no database, no clock of its own.
 *
 * <p>Two kinds of staff sale, chosen by the person on the form (never guessed from how old the date is: a paper sale keyed twenty minutes after a promotion
 * ended must still get that promotion):
 *
 * <ul>
 *   <li>{@link SaleKind#LIVE_PHONE}: the sale is happening now (a call or a chat). Dated now; a date typed with it is refused.</li>
 *   <li>{@link SaleKind#LATE_PAPER}: a sale written on paper and typed in later. Recorded at its TRUE date (CLIENT_DECISIONS 1.1, Q30), of any age; priced
 *       with the price and promotion of that day. A reason is ALWAYS required, because a date the person chose decides the price. Past the window of
 *       CLIENT_DECISIONS 8.2 (72 hours, the one {@code oriah.sales.backdate-window} payment also reads) only someone who may back-date a sale may key it.</li>
 * </ul>
 *
 * Everything is judged to the microsecond the database keeps, so the date an event reports is exactly the date the row holds.
 */
public final class SaleDating {

    /** A phone's clock running ahead: a time this far ahead is "now", beyond it is a mistake. */
    public static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    static final int REASON_MIN = 5;
    static final int REASON_MAX = 200;

    /** The date the sale is recorded at and, for a late paper sale, the reason kept with it (null for a live sale). */
    public record Dated(Instant occurredAt, String reason) {
    }

    private SaleDating() {
    }

    /**
     * @param kind        what the person chose on the form
     * @param requested   the date typed (a late paper sale's), null for a live sale
     * @param now         the time on this service's clock
     * @param window      how far back anyone who may record a sale may date it (72 hours)
     * @param mayBackdate whether the caller holds BACKDATE_SALE
     * @param reason      why a late paper sale is recorded late, as typed
     * @throws SalesProblem 400 {@code invalid-occurred-at} (a date on a live sale, none on a paper one, or one in the future); 400 {@code backdate-reason-required}
     * @throws AccessDeniedException (403) a date beyond the window by someone who may not back-date
     */
    public static Dated check(SaleKind kind, Instant requested, Instant now, Duration window, boolean mayBackdate, String reason) {
        Instant clock = now.truncatedTo(ChronoUnit.MICROS);
        if (kind == SaleKind.LIVE_PHONE) {
            if (requested != null) {
                throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-occurred-at",
                        "a sale taken now is dated now: a sale typed in later from paper is recorded as a late paper sale, with its date");
            }
            return new Dated(clock, null);
        }
        if (requested == null) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-occurred-at", "a late paper sale must say when it happened");
        }
        Instant at = requested.truncatedTo(ChronoUnit.MICROS);
        if (at.isAfter(clock.plus(CLOCK_SKEW))) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-occurred-at",
                    "a sale cannot have happened in the future: " + at + " is after " + clock);
        }
        if (at.isBefore(clock.minus(window)) && !mayBackdate) {
            throw new AccessDeniedException("a sale that happened more than " + window.toHours() + " hours ago is recorded by someone who may back-date a sale");
        }
        String why = reason == null ? "" : reason.strip();
        if (why.length() < REASON_MIN || why.length() > REASON_MAX) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "backdate-reason-required",
                    "a late paper sale needs a reason of " + REASON_MIN + " to " + REASON_MAX + " characters");
        }
        return new Dated(at, why);
    }
}
