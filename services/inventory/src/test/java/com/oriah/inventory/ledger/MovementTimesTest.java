package com.oriah.inventory.ledger;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When a stock change happened. One rule in one place: left out it is now; it is never in the future; and where the
 * stock could not have left before it arrived (a write-off, a take-back) it is never before the lot it touches.
 * A real, paid sale is never refused for its date: that caller passes no earlier limit.
 */
class MovementTimesTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    @Test
    @DisplayName("left out, it is now, and the caller is told what was recorded")
    void leftOutIsNow() {
        assertThat(MovementTimes.resolve(null, NOW, null)).isEqualTo(NOW);
    }

    @Test
    @DisplayName("times are kept to the microsecond, which is all the database keeps, so an answer given again is the same answer")
    void microsecondsAreAllThatIsKept() {
        Instant nowWithNanos = Instant.parse("2026-11-13T10:00:00.123456789Z");

        assertThat(MovementTimes.resolve(null, nowWithNanos, null)).isEqualTo(Instant.parse("2026-11-13T10:00:00.123456Z"));
        assertThat(MovementTimes.resolve(Instant.parse("2026-03-05T09:30:00.999999999Z"), nowWithNanos, null))
                .isEqualTo(Instant.parse("2026-03-05T09:30:00.999999Z"));
    }

    @Test
    @DisplayName("a date in the past is kept as keyed")
    void thePastIsKept() {
        Instant march = Instant.parse("2026-03-05T09:30:00Z");

        assertThat(MovementTimes.resolve(march, NOW, null)).isEqualTo(march);
    }

    @Test
    @DisplayName("a date in the future is refused, with five minutes of allowance for clocks")
    void theFutureIsRefused() {
        assertThat(MovementTimes.resolve(NOW.plus(Duration.ofMinutes(4)), NOW, null)).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
        assertThatThrownBy(() -> MovementTimes.resolve(NOW.plus(Duration.ofMinutes(6)), NOW, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future");
    }

    @Test
    @DisplayName("where stock could not have left before it arrived, an earlier date is refused")
    void notBeforeTheStockArrived() {
        Instant received = Instant.parse("2026-11-01T08:00:00Z");

        assertThat(MovementTimes.resolve(received, NOW, received)).isEqualTo(received);
        assertThatThrownBy(() -> MovementTimes.resolve(received.minusSeconds(1), NOW, received))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("arrived");
    }
}
