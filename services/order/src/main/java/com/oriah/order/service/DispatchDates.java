package com.oriah.order.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.oriah.order.exception.SalesProblem;

/**
 * Which date a dispatch step (packed, dispatched, delivered or handed over) is recorded at, and who may say it (Phase 8, design D14). A pure rule: no
 * Spring, no database, no clock of its own.
 *
 * <p>The date matters because the courier fare belongs to the month the parcel went, and a late-keyed step is recorded at its TRUE date, never the
 * day it was typed (CLIENT_DECISIONS 1.1 and 8.2). The client's rule is the same one that governs the day money arrived: the 72 hours decide only WHO
 * may key a date, anyone who may pack or ship inside the window and someone who may back-date a sale, with a reason, beyond it. Two things are new for
 * a step: it can never come before the step it follows, and the allowance for a phone's clock running ahead is five minutes.
 *
 * <p>Everything is judged to the microsecond the database keeps, so the date an event reports is exactly the date the row holds.
 */
public final class DispatchDates {

    /** A packer types the time from a phone: a time this far ahead is "now", beyond it is a mistake. */
    public static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    static final int REASON_MIN = 5;
    static final int REASON_MAX = 200;

    /** The date to record and, when it was beyond the window, the reason to keep with it (null otherwise). */
    public record Dated(Instant occurredAt, String backdateReason) {
    }

    private DispatchDates() {
    }

    /**
     * @param requested     when the caller says it happened; null means now
     * @param notBefore     the date of the step this one follows (pack: the day the money arrived); null means no bound
     * @param now           the time on this service's clock
     * @param window        how far back anyone who may take the step may date it (72 hours)
     * @param mayBackdate   whether the caller holds BACKDATE_SALE
     * @param backdateReason why it is recorded late, as typed
     * @throws SalesProblem 400 {@code invalid-occurred-at} (in the future, or before the step it follows); 400 {@code backdate-reason-required}
     * @throws AccessDeniedException (403) a date beyond the window by someone who may not back-date
     */
    public static Dated check(Instant requested, Instant notBefore, Instant now, Duration window, boolean mayBackdate, String backdateReason) {
        Instant clock = now.truncatedTo(ChronoUnit.MICROS);
        Instant floor = notBefore == null ? null : notBefore.truncatedTo(ChronoUnit.MICROS);
        if (requested == null) {
            // "Now", unless the step before it is a moment ahead of this clock (another service's clock, within its own allowance): never an error.
            return new Dated(floor != null && floor.isAfter(clock) ? floor : clock, null);
        }
        Instant at = requested.truncatedTo(ChronoUnit.MICROS);
        if (at.isAfter(clock.plus(CLOCK_SKEW))) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-occurred-at",
                    "this cannot have happened in the future: " + at + " is after " + clock);
        }
        if (floor != null && at.isBefore(floor)) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-occurred-at",
                    "this cannot have happened before the step it follows (" + floor + "): " + at);
        }
        if (!at.isBefore(clock.minus(window))) {
            return new Dated(at, null);
        }
        if (!mayBackdate) {
            throw new AccessDeniedException("a step that happened more than " + window.toHours() + " hours ago is recorded by someone who may back-date a sale");
        }
        String reason = backdateReason == null ? "" : backdateReason.strip();
        if (reason.length() < REASON_MIN || reason.length() > REASON_MAX) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "backdate-reason-required",
                    "a step that happened more than " + window.toHours() + " hours ago needs a reason of " + REASON_MIN + " to " + REASON_MAX + " characters");
        }
        return new Dated(at, reason);
    }
}
