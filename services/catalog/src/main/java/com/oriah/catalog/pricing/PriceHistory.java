package com.oriah.catalog.pricing;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which row of a variant's price history applies, and which dates a new row may be given.
 *
 * <p>A price is dated history: a change adds a row from a date and never overwrites, so a paper sale keyed late is priced at its
 * true date. Rows are ordered by the date they take effect and, for the same instant, by when they were written, so a typo
 * corrected is the price from the typo's own time.
 */
public final class PriceHistory {

    private static final Comparator<PriceRow> ORDER = Comparator.comparing(PriceRow::effectiveFrom).thenComparingLong(PriceRow::seq);

    private PriceHistory() {
    }

    /** The row in force at an instant: the latest one from on or before it; empty before the first. */
    public static Optional<PriceRow> priceAt(List<PriceRow> history, Instant at) {
        return history.stream().filter(row -> !row.effectiveFrom().isAfter(at)).max(ORDER);
    }

    /** The row in force now: the last one in the order above. */
    public static Optional<PriceRow> latest(List<PriceRow> history) {
        return history.stream().max(ORDER);
    }

    /**
     * The date a new price row takes effect.
     *
     * <p>No date asked for: now, or the latest row's own date if the clock reads earlier (a clock stepped back), so rows never run
     * backwards. A date asked for must not be in the future (nothing flips a price later, so a future row would be a price nobody
     * sees yet), not before the latest row (history stays in order, and the latest row is also the last one written), and not
     * older than the window (a paper tag changed this morning and keyed tonight is real; a fortnight ago is not a late key).
     *
     * @throws PriceRuleViolation with the code {@code effective-date-in-the-future}, {@code effective-date-too-old} or
     *                            {@code effective-date-before-latest-price}
     */
    public static Instant effectiveFrom(Instant requested, Instant now, Instant latestEffectiveFrom, Duration window) {
        if (requested == null) {
            return latestEffectiveFrom != null && latestEffectiveFrom.isAfter(now) ? latestEffectiveFrom : now;
        }
        if (requested.isAfter(now)) {
            throw new PriceRuleViolation("effective-date-in-the-future", "a price cannot start in the future: " + requested + " is after " + now);
        }
        if (requested.isBefore(now.minus(window))) {
            throw new PriceRuleViolation("effective-date-too-old",
                    "a price cannot start more than " + window.toHours() + " hours back: " + requested + " is before " + now.minus(window));
        }
        if (latestEffectiveFrom != null && requested.isBefore(latestEffectiveFrom)) {
            throw new PriceRuleViolation("effective-date-before-latest-price",
                    "a price cannot start before the price it follows, which starts at " + latestEffectiveFrom);
        }
        return requested;
    }
}
