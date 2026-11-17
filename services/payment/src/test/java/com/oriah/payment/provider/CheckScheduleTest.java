package com.oriah.payment.provider;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When Oriah next asks ZynlePay what became of a payment. Often while the customer is likely still approving it, then every minute, and after
 * half an hour with no answer the attempt goes to the owner: it is still asked about now and then for two weeks, and it is never assumed failed.
 */
class CheckScheduleTest {

    private static final Instant SENT = Instant.parse("2026-11-17T10:00:00Z");
    private static final CheckSchedule SCHEDULE = new CheckSchedule(Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofMinutes(5),
            Duration.ofMinutes(20), Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofDays(14),
            Duration.ofMinutes(15));

    private static Instant at(String time) {
        return Instant.parse("2026-11-17T" + time + "Z");
    }

    @Test
    @DisplayName("the first question is asked fifteen seconds after the request went out")
    void theFirstCheck() {
        assertThat(SCHEDULE.first(SENT)).isEqualTo(at("10:00:15"));
    }

    @Test
    @DisplayName("mobile money: every 15 seconds for the first five minutes, then every minute")
    void mobileMoney() {
        assertThat(SCHEDULE.afterNoNews(false, SENT, at("10:00:15"), false)).isEqualTo(at("10:00:30"));
        assertThat(SCHEDULE.afterNoNews(false, SENT, at("10:04:59"), false)).isEqualTo(at("10:05:14"));
        assertThat(SCHEDULE.afterNoNews(false, SENT, at("10:05:00"), false)).isEqualTo(at("10:06:00"));
        assertThat(SCHEDULE.afterNoNews(false, SENT, at("10:20:00"), false)).isEqualTo(at("10:21:00"));
    }

    @Test
    @DisplayName("a card page takes longer to fill: every 15 seconds for twenty minutes")
    void card() {
        assertThat(SCHEDULE.afterNoNews(true, SENT, at("10:19:00"), false)).isEqualTo(at("10:19:15"));
        assertThat(SCHEDULE.afterNoNews(true, SENT, at("10:20:00"), false)).isEqualTo(at("10:21:00"));
    }

    @Test
    @DisplayName("when ZynlePay itself says 'still deciding', it is not asked again for a minute")
    void zynlePaySaidPending() {
        assertThat(SCHEDULE.afterNoNews(false, SENT, at("10:00:15"), true)).isEqualTo(at("10:01:15"));
        assertThat(SCHEDULE.afterNoNews(false, SENT, at("10:10:00"), true)).isEqualTo(at("10:11:00"));
    }

    @Test
    @DisplayName("after thirty minutes with no answer the attempt is the owner's: at thirty minutes exactly, not before")
    void givingUp() {
        assertThat(SCHEDULE.givenUp(SENT, at("10:29:59"))).isFalse();
        assertThat(SCHEDULE.givenUp(SENT, at("10:30:00"))).isTrue();
    }

    @Test
    @DisplayName("an unresolved attempt is still asked about every hour for two weeks, then no more")
    void unresolved() {
        Instant unresolvedAt = at("10:30:00");

        assertThat(SCHEDULE.afterUnresolved(unresolvedAt, at("10:30:00"))).contains(at("11:30:00"));
        assertThat(SCHEDULE.afterUnresolved(unresolvedAt, Instant.parse("2026-12-01T10:29:59Z"))).contains(Instant.parse("2026-12-01T11:29:59Z"));
        assertThat(SCHEDULE.afterUnresolved(unresolvedAt, Instant.parse("2026-12-01T10:30:00Z"))).isEmpty();
    }

    @Test
    @DisplayName("a failed attempt is asked about once more, fifteen minutes later, in case ZynlePay contradicts itself")
    void theFinalRecheck() {
        assertThat(SCHEDULE.finalRecheck(at("10:02:00"))).isEqualTo(at("10:17:00"));
    }

    @Test
    @DisplayName("settings that cannot be right are refused: a wait of nothing, or giving up before the customer could have approved")
    void settingsThatCannotBeRight() {
        assertThatThrownBy(() -> new CheckSchedule(Duration.ZERO, Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofMinutes(20),
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofDays(14), Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("first-check-after");
        assertThatThrownBy(() -> new CheckSchedule(Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofMinutes(20),
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofMinutes(19), Duration.ofHours(1), Duration.ofDays(14), Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("give-up-after");
        assertThatThrownBy(() -> new CheckSchedule(Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofMinutes(20),
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofMinutes(30), null, Duration.ofDays(14), Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unresolved-every");
    }
}
