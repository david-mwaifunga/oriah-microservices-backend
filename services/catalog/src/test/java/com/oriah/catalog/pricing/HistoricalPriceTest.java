package com.oriah.catalog.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A price from before the price in force (Phase 9, the owner's remedy). A paper sale made before a piece's first price was entered cannot be recorded (a sale is never
 * priced at today's price), and a price may start at most 72 hours back, so the person who may back-date a sale (BACKDATE_SALE) can add an EARLIER row to the history, with a
 * reason, without changing today's price. Everyone else keeps the old rules exactly.
 */
class HistoricalPriceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(72);
    private static final Instant LATEST = NOW.minus(Duration.ofHours(5));
    private static final String REASON = "the ring was sold on paper before it was priced here";

    private static PriceHistory.Resolved resolve(Instant requested, Instant latest, boolean mayBackdate, String reason) {
        return PriceHistory.resolve(requested, NOW, latest, WINDOW, mayBackdate, reason);
    }

    private static void refusedAs(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(PriceRuleViolation.class).extracting("code").isEqualTo(code);
    }

    @Test
    @DisplayName("an ordinary price is exactly what it was: dated within the window and not before the latest row, with nothing historical about it, whoever sets it and whatever reason is sent")
    void anOrdinaryPriceIsUnchanged() {
        PriceHistory.Resolved now = resolve(null, LATEST, false, null);
        PriceHistory.Resolved inside = resolve(NOW.minus(Duration.ofHours(2)), LATEST, true, REASON);

        assertThat(now.effectiveFrom()).isEqualTo(NOW);
        assertThat(now.historical()).isFalse();
        assertThat(inside.effectiveFrom()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(inside.historical()).as("a date after the latest row is an ordinary price even for the owner").isFalse();
        assertThat(inside.backdateReason()).isNull();
    }

    @Test
    @DisplayName("without the capability the old refusals stand: older than the window is too old, before the latest row is before the latest")
    void withoutTheCapabilityNothingChanges() {
        refusedAs(() -> resolve(NOW.minus(Duration.ofDays(10)), LATEST, false, REASON), "effective-date-too-old");
        refusedAs(() -> resolve(NOW.minus(Duration.ofHours(10)), LATEST, false, REASON), "effective-date-before-latest-price");
    }

    @Test
    @DisplayName("with the capability and a reason a price may be dated before the latest row, at any age: it is a historical row, kept with its reason")
    void theOwnersRemedy() {
        Instant tenDaysAgo = NOW.minus(Duration.ofDays(10));
        Instant fourHoursAgoButBeforeLatest = LATEST.minusSeconds(60);

        PriceHistory.Resolved old = resolve(tenDaysAgo, LATEST, true, "  " + REASON + "  ");
        PriceHistory.Resolved recent = resolve(fourHoursAgoButBeforeLatest, LATEST, true, REASON);

        assertThat(old.historical()).isTrue();
        assertThat(old.effectiveFrom()).isEqualTo(tenDaysAgo);
        assertThat(old.backdateReason()).as("trimmed").isEqualTo(REASON);
        assertThat(recent.historical()).isTrue();
    }

    @Test
    @DisplayName("a historical price needs a reason of 5 to 200 characters: none, blank, too short or too long is 400 backdate-reason-required")
    void theReasonIsRequired() {
        Instant old = NOW.minus(Duration.ofDays(10));
        for (String reason : new String[] {null, "", "   ", "four"}) {
            refusedAs(() -> resolve(old, LATEST, true, reason), "backdate-reason-required");
        }
        refusedAs(() -> resolve(old, LATEST, true, "x".repeat(201)), "backdate-reason-required");
        assertThat(resolve(old, LATEST, true, "fiver").backdateReason()).isEqualTo("fiver");
        assertThat(resolve(old, LATEST, true, "x".repeat(200)).backdateReason()).hasSize(200);
    }

    @Test
    @DisplayName("the capability does not let a price REPLACE the current one retroactively: a date after the latest row but older than the window is still too old, and so is any old date with no history at all")
    void theLiveHistoryIsNotRewritten() {
        Instant latestLongAgo = NOW.minus(Duration.ofDays(20));

        refusedAs(() -> resolve(NOW.minus(Duration.ofDays(10)), latestLongAgo, true, REASON), "effective-date-too-old");
        refusedAs(() -> resolve(NOW.minus(Duration.ofDays(10)), null, true, REASON), "effective-date-too-old");
    }

    @Test
    @DisplayName("the future is refused for everyone, the capability included")
    void theFutureIsRefused() {
        refusedAs(() -> resolve(NOW.plusSeconds(1), LATEST, true, REASON), "effective-date-in-the-future");
    }

    @Test
    @DisplayName("exactly the latest row's instant is an ordinary price (the old rule), and a microsecond before it is historical")
    void theBoundaryIsTheLatestRow() {
        assertThat(resolve(LATEST, LATEST, true, REASON).historical()).isFalse();
        assertThat(resolve(LATEST.minusNanos(1000), LATEST, true, REASON).historical()).isTrue();
    }
}
