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
     * A price's date as resolved: the instant it takes effect, whether it is a HISTORICAL row (dated before the price in force, so it changes nothing about today's
     * price and only says what the price was on an earlier day), and the reason kept with a historical row.
     */
    public record Resolved(Instant effectiveFrom, boolean historical, String backdateReason) {
    }

    /**
     * The date and the kind of a new price row (Phase 9). Everything {@link #effectiveFrom} decides stands, with ONE addition for the person who may back-date a
     * sale (BACKDATE_SALE): a date BEFORE the price in force, at any age, is a historical row, kept with a reason of 5 to 200 characters. A paper sale made before a
     * piece's first price was entered cannot otherwise be recorded (a sale is never priced at today's price) and a price may start at most 72 hours back.
     *
     * <p>A historical row never rewrites the live history: a date AFTER the latest row but older than the window is still too old (it would change today's price
     * retroactively), a piece with no history has nothing to insert before, and the future is refused for everyone.
     *
     * @throws PriceRuleViolation the same codes as {@link #effectiveFrom}, and {@code backdate-reason-required} for a historical row with no usable reason
     */
    public static Resolved resolve(Instant requested, Instant now, Instant latestEffectiveFrom, Duration window, boolean mayBackdate, String backdateReason) {
        if (requested != null && !requested.isAfter(now) && mayBackdate && latestEffectiveFrom != null && requested.isBefore(latestEffectiveFrom)) {
            String reason = backdateReason == null ? "" : backdateReason.strip();
            if (reason.length() < 5 || reason.length() > 200) {
                throw new PriceRuleViolation("backdate-reason-required",
                        "a price from before the price in force needs a reason of 5 to 200 characters: say why it is being added now");
            }
            return new Resolved(requested, true, reason);
        }
        return new Resolved(effectiveFrom(requested, now, latestEffectiveFrom, window), false, null);
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
