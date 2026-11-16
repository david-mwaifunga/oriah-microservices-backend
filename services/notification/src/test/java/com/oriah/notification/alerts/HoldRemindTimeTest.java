package com.oriah.notification.alerts;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When staff are reminded that a customer's claimed payment is still not confirmed: three hours before the pieces are released, but never in the
 * middle of the night, when nobody would read it. Times in these tests are written in UTC; Lusaka is two hours ahead.
 */
class HoldRemindTimeTest {

    private static final HoldRemindTime RULE = new HoldRemindTime(Duration.ofHours(3), LocalTime.of(20, 0), LocalTime.of(7, 0),
            ZoneId.of("Africa/Lusaka"));

    private static Instant utc(String time) {
        return Instant.parse(time);
    }

    @Test
    @DisplayName("three hours before the hold ends")
    void threeHoursBeforeTheHoldEnds() {
        // Claimed at 09:00 Lusaka, released at 17:00 Lusaka: reminded at 14:00 Lusaka.
        assertThat(RULE.of(utc("2026-11-17T15:00:00Z"), utc("2026-11-17T07:00:00Z"))).contains(utc("2026-11-17T12:00:00Z"));
    }

    @Test
    @DisplayName("a reminder that would fall in the night moves back to eight in the evening before")
    void aNightTimeMovesBackToEightInTheEvening() {
        // Released at 09:00 Lusaka on the 18th: three hours before is 06:00, in the night. Reminded at 20:00 Lusaka on the 17th.
        assertThat(RULE.of(utc("2026-11-18T07:00:00Z"), utc("2026-11-17T08:00:00Z"))).contains(utc("2026-11-17T18:00:00Z"));
        // Released at 01:00 Lusaka on the 18th: three hours before is 22:00 on the 17th, in the night. Reminded at 20:00 that evening.
        assertThat(RULE.of(utc("2026-11-17T23:00:00Z"), utc("2026-11-17T08:00:00Z"))).contains(utc("2026-11-17T18:00:00Z"));
    }

    @Test
    @DisplayName("exactly eight in the evening is night; exactly seven in the morning is not")
    void theEdgesOfTheNight() {
        // Three hours before is 20:00 Lusaka sharp: it stays 20:00.
        assertThat(RULE.of(utc("2026-11-17T21:00:00Z"), utc("2026-11-17T08:00:00Z"))).contains(utc("2026-11-17T18:00:00Z"));
        // Three hours before is 07:00 Lusaka sharp: it stays 07:00.
        assertThat(RULE.of(utc("2026-11-18T08:00:00Z"), utc("2026-11-17T08:00:00Z"))).contains(utc("2026-11-18T05:00:00Z"));
    }

    @Test
    @DisplayName("never before now: when eight in the evening has already passed, the reminder goes at seven in the morning if the hold still runs then")
    void neverBeforeNow() {
        // Claimed at 23:00 Lusaka on the 17th, released at 09:00 on the 18th: three hours before is 06:00 (night), 20:00 has passed.
        // Reminded at 07:00 Lusaka on the 18th, two hours before the release.
        assertThat(RULE.of(utc("2026-11-18T07:00:00Z"), utc("2026-11-17T21:00:00Z"))).contains(utc("2026-11-18T05:00:00Z"));
    }

    @Test
    @DisplayName("no reminder when the only time left for one is in the night")
    void noReminderWhenOnlyTheNightIsLeft() {
        // Claimed at 23:00 Lusaka, released at 07:10: seven o'clock leaves ten minutes, too late to act on.
        assertThat(RULE.of(utc("2026-11-18T05:10:00Z"), utc("2026-11-17T21:00:00Z"))).isEmpty();
    }

    @Test
    @DisplayName("a hold with under three and a half hours left gets no reminder: staff were told at once, moments ago")
    void aShortHoldGetsNoReminder() {
        assertThat(RULE.of(utc("2026-11-17T10:29:00Z"), utc("2026-11-17T07:00:00Z"))).isEmpty();
        assertThat(RULE.of(utc("2026-11-17T10:30:00Z"), utc("2026-11-17T07:00:00Z"))).contains(utc("2026-11-17T07:30:00Z"));
        assertThat(RULE.of(utc("2026-11-17T06:00:00Z"), utc("2026-11-17T07:00:00Z"))).isEmpty();
    }
}
