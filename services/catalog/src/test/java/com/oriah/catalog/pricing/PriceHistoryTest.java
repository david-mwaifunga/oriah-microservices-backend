package com.oriah.catalog.pricing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A price is dated history: a change adds a row from a date and never overwrites, so a paper sale keyed late is priced at
 * its true date. These are the two rules that make that safe: which row applies at an instant, and which dates a new row may
 * be given.
 */
class PriceHistoryTest {

    private static final Instant NOON = Instant.parse("2026-11-13T12:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(72);

    private static PriceRow row(long seq, String from, String price) {
        return new PriceRow(seq, Instant.parse(from), new BigDecimal(price));
    }

    @Test
    @DisplayName("the price at an instant is the latest row from on or before it, and none before the first row")
    void priceAtAnInstant() {
        List<PriceRow> history = List.of(row(1, "2026-11-01T08:00:00Z", "1000.00"), row(2, "2026-11-10T08:00:00Z", "1200.00"));

        assertThat(PriceHistory.priceAt(history, Instant.parse("2026-10-31T23:59:59Z"))).isEmpty();
        assertThat(PriceHistory.priceAt(history, Instant.parse("2026-11-01T08:00:00Z"))).map(PriceRow::unitPrice).contains(new BigDecimal("1000.00"));
        assertThat(PriceHistory.priceAt(history, Instant.parse("2026-11-09T12:00:00Z"))).map(PriceRow::unitPrice).contains(new BigDecimal("1000.00"));
        assertThat(PriceHistory.priceAt(history, Instant.parse("2026-11-10T08:00:00Z"))).map(PriceRow::unitPrice).contains(new BigDecimal("1200.00"));
        assertThat(PriceHistory.priceAt(history, NOON)).map(PriceRow::unitPrice).contains(new BigDecimal("1200.00"));
    }

    @Test
    @DisplayName("two rows from the same instant are ordered by when they were written: the later one is the price, so a typo corrected is the price from the typo's own time")
    void tiesGoToTheLaterWrite() {
        List<PriceRow> history = List.of(row(7, "2026-11-12T09:00:00Z", "15000.00"), row(8, "2026-11-12T09:00:00Z", "1500.00"));

        assertThat(PriceHistory.priceAt(history, Instant.parse("2026-11-12T09:00:00Z"))).map(PriceRow::unitPrice).contains(new BigDecimal("1500.00"));
        assertThat(PriceHistory.latest(history)).map(PriceRow::unitPrice).contains(new BigDecimal("1500.00"));
    }

    @Test
    @DisplayName("the order the rows are handed over in does not matter")
    void orderOfTheListDoesNotMatter() {
        PriceRow first = row(1, "2026-11-01T08:00:00Z", "1000.00");
        PriceRow second = row(2, "2026-11-10T08:00:00Z", "1200.00");

        assertThat(PriceHistory.priceAt(List.of(second, first), NOON)).contains(second);
        assertThat(PriceHistory.latest(List.of(second, first))).contains(second);
    }

    @Test
    @DisplayName("with no history there is no price and no latest row")
    void noHistory() {
        assertThat(PriceHistory.priceAt(List.of(), NOON)).isEqualTo(Optional.empty());
        assertThat(PriceHistory.latest(List.of())).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("no date given: the price starts now, unless the clock reads earlier than the latest row (a clock stepped back), when it starts at that row")
    void defaultDate() {
        assertThat(PriceHistory.effectiveFrom(null, NOON, null, WINDOW)).isEqualTo(NOON);
        assertThat(PriceHistory.effectiveFrom(null, NOON, Instant.parse("2026-11-12T00:00:00Z"), WINDOW)).isEqualTo(NOON);
        Instant ahead = NOON.plusSeconds(3);
        assertThat(PriceHistory.effectiveFrom(null, NOON, ahead, WINDOW)).isEqualTo(ahead);
    }

    @Test
    @DisplayName("a date in the past is allowed within the window, back to the latest row's own date and no earlier")
    void pastDates() {
        Instant latest = Instant.parse("2026-11-12T09:00:00Z");

        assertThat(PriceHistory.effectiveFrom(Instant.parse("2026-11-13T09:00:00Z"), NOON, latest, WINDOW)).isEqualTo(Instant.parse("2026-11-13T09:00:00Z"));
        assertThat(PriceHistory.effectiveFrom(latest, NOON, latest, WINDOW)).as("the same instant as the latest row").isEqualTo(latest);
        assertThatThrownBy(() -> PriceHistory.effectiveFrom(latest.minusSeconds(1), NOON, latest, WINDOW))
                .isInstanceOf(PriceRuleViolation.class).extracting("code").isEqualTo("effective-date-before-latest-price");
    }

    @Test
    @DisplayName("older than the window is refused, exactly at the window's edge is allowed")
    void tooOld() {
        assertThat(PriceHistory.effectiveFrom(NOON.minus(WINDOW), NOON, null, WINDOW)).isEqualTo(NOON.minus(WINDOW));
        assertThatThrownBy(() -> PriceHistory.effectiveFrom(NOON.minus(WINDOW).minusSeconds(1), NOON, null, WINDOW))
                .isInstanceOf(PriceRuleViolation.class).extracting("code").isEqualTo("effective-date-too-old");
    }

    @Test
    @DisplayName("a date in the future is refused: nothing flips a price at a later time, so a future row would be a price nobody sees yet")
    void futureIsRefused() {
        assertThatThrownBy(() -> PriceHistory.effectiveFrom(NOON.plusSeconds(1), NOON, null, WINDOW))
                .isInstanceOf(PriceRuleViolation.class).extracting("code").isEqualTo("effective-date-in-the-future");
        assertThat(PriceHistory.effectiveFrom(NOON, NOON, null, WINDOW)).as("now itself is fine").isEqualTo(NOON);
    }
}
