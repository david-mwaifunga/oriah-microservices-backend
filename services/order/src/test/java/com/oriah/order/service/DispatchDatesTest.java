package com.oriah.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.oriah.order.exception.SalesProblem;
import com.oriah.order.service.DispatchDates.Dated;

/**
 * Which date a dispatch step is recorded at, and who may say it (Phase 8, design D14). A plain unit test: the date is the one fact the books are
 * built on (a courier fare belongs to the month the parcel went), so every edge is tried here.
 *
 * <p>The client's rule is that a late-keyed sale or step is recorded at its TRUE date and the 72 hours decide only WHO may key it (CLIENT_DECISIONS
 * 1.1 and 8.2): inside the window any packer, beyond it someone who may back-date a sale, with a reason. The rule is the same one payment applies to
 * the day money arrived ({@code ArrivalWindow}); the only differences are that a step can never come before the step it follows, and that the
 * clock-skew allowance is five minutes (a packer types the time from a phone).
 */
class DispatchDatesTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(72);

    private static Dated check(Instant requested, Instant notBefore, boolean mayBackdate, String reason) {
        return DispatchDates.check(requested, notBefore, NOW, WINDOW, mayBackdate, reason);
    }

    private static void refusedAs(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SalesProblem.class, p -> {
            assertThat(p.status()).isEqualTo(status);
            assertThat(p.code()).isEqualTo(code);
        });
    }

    @Test
    @DisplayName("nothing typed means now, to the microsecond the database keeps, and no reason is kept")
    void defaultsToNow() {
        Dated at = DispatchDates.check(null, null, Instant.parse("2026-11-20T10:00:00.123456789Z"), WINDOW, false, null);
        assertThat(at.occurredAt()).isEqualTo(Instant.parse("2026-11-20T10:00:00.123456Z"));
        assertThat(at.backdateReason()).isNull();
    }

    @Test
    @DisplayName("nothing typed, and the step before is a moment AHEAD of this clock (the other service's clock): the step takes that moment, never an error")
    void defaultNeverBeforeThePreviousStep() {
        Instant ahead = NOW.plusSeconds(20);
        assertThat(check(null, ahead, false, null).occurredAt()).isEqualTo(ahead);
        assertThat(check(null, NOW.minusSeconds(20), false, null).occurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a time typed inside the window is kept for anyone, truncated to microseconds; a reason sent with it is not kept")
    void insideTheWindowAnyone() {
        Instant earlier = Instant.parse("2026-11-19T08:30:15.987654321Z");
        Dated at = check(earlier, null, false, "ignored: it was not needed");
        assertThat(at.occurredAt()).isEqualTo(Instant.parse("2026-11-19T08:30:15.987654Z"));
        assertThat(at.backdateReason()).isNull();
    }

    @Test
    @DisplayName("exactly 72 hours ago is still inside the window; one microsecond older is not")
    void theWindowEdge() {
        Instant edge = NOW.minus(WINDOW);
        assertThat(check(edge, null, false, null).occurredAt()).isEqualTo(edge);
        assertThatThrownBy(() -> check(edge.minusNanos(1_000), null, false, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a time beyond the window needs someone who may back-date a sale: 403 for anyone else")
    void beyondTheWindowNeedsTheCapability() {
        Instant old = NOW.minus(Duration.ofDays(5));
        assertThatThrownBy(() -> check(old, null, false, "the shop was shut for the weekend"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("72 hours");
    }

    @Test
    @DisplayName("a time beyond the window by someone who may back-date it needs a reason of 5 to 200 characters, kept trimmed")
    void beyondTheWindowNeedsAReason() {
        Instant old = NOW.minus(Duration.ofDays(5));
        for (String bad : new String[] {null, "", "    ", "four", "  four  ", "x".repeat(201)}) {
            refusedAs(() -> check(old, null, true, bad), HttpStatus.BAD_REQUEST, "backdate-reason-required");
        }
        assertThat(check(old, null, true, "  fives ").backdateReason()).isEqualTo("fives");
        assertThat(check(old, null, true, "x".repeat(200)).backdateReason()).hasSize(200);
        assertThat(check(old, null, true, "the weekend, shop shut").occurredAt()).isEqualTo(old);
    }

    @Test
    @DisplayName("a time in the future is 400 invalid-occurred-at, with five minutes of allowance for a phone's clock")
    void theFuture() {
        assertThat(check(NOW.plus(DispatchDates.CLOCK_SKEW), null, false, null).occurredAt()).isEqualTo(NOW.plus(DispatchDates.CLOCK_SKEW));
        assertThat(DispatchDates.CLOCK_SKEW).isEqualTo(Duration.ofMinutes(5));
        refusedAs(() -> check(NOW.plus(DispatchDates.CLOCK_SKEW).plusNanos(1_000), null, false, null), HttpStatus.BAD_REQUEST, "invalid-occurred-at");
        refusedAs(() -> check(NOW.plus(Duration.ofDays(1)), null, true, "long enough a reason"), HttpStatus.BAD_REQUEST, "invalid-occurred-at");
    }

    @Test
    @DisplayName("a step cannot come before the step it follows: 400 invalid-occurred-at; the same instant is fine; no previous step means no bound")
    void neverBeforeThePreviousStep() {
        Instant previous = Instant.parse("2026-11-20T08:00:00Z");
        assertThat(check(previous, previous, false, null).occurredAt()).isEqualTo(previous);
        assertThat(check(previous.plusNanos(1_000), previous, false, null).occurredAt()).isEqualTo(previous.plusNanos(1_000));
        refusedAs(() -> check(previous.minusNanos(1_000), previous, false, null), HttpStatus.BAD_REQUEST, "invalid-occurred-at");
        assertThat(check(Instant.parse("2026-11-19T00:00:00Z"), null, false, null).occurredAt()).isEqualTo(Instant.parse("2026-11-19T00:00:00Z"));
    }

    @Test
    @DisplayName("the bound is judged after truncating to microseconds, as the database keeps it: a nanosecond below the previous step's microsecond is the same microsecond")
    void judgedAtMicrosecondPrecision() {
        Instant previous = Instant.parse("2026-11-20T08:00:00.000100Z");
        assertThat(check(Instant.parse("2026-11-20T08:00:00.000100999Z"), previous, false, null).occurredAt()).isEqualTo(previous);
        refusedAs(() -> check(Instant.parse("2026-11-20T08:00:00.000099999Z"), previous, false, null), HttpStatus.BAD_REQUEST, "invalid-occurred-at");
    }

    @Test
    @DisplayName("a time that is wrong is said to be wrong before anyone is told to fetch a manager: before-the-previous-step beats the window")
    void aWrongTimeIsNotAPermissionQuestion() {
        Instant previous = NOW.minus(Duration.ofDays(1));
        Instant tooOldAndBefore = NOW.minus(Duration.ofDays(6));
        refusedAs(() -> check(tooOldAndBefore, previous, false, null), HttpStatus.BAD_REQUEST, "invalid-occurred-at");
        refusedAs(() -> check(tooOldAndBefore, previous, true, "plenty of reason"), HttpStatus.BAD_REQUEST, "invalid-occurred-at");
    }

    @Test
    @DisplayName("the window is a setting: a shorter one moves who may key a time")
    void theWindowIsASetting() {
        Instant sixHoursAgo = NOW.minus(Duration.ofHours(6));
        assertThat(DispatchDates.check(sixHoursAgo, null, NOW, Duration.ofHours(12), false, null).occurredAt()).isEqualTo(sixHoursAgo);
        assertThatThrownBy(() -> DispatchDates.check(sixHoursAgo, null, NOW, Duration.ofHours(4), false, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("4 hours");
    }
}
