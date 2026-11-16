package com.oriah.notification.text;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a moment is written in a message to staff: the hour on the shop's own clock, then the day, "14:05 on Tuesday 17 November". The system keeps
 * time in UTC; Lusaka is two hours ahead all year.
 */
class LusakaTimeTest {

    private static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");

    @Test
    @DisplayName("writes the hour, the day of the week, the day and the month, on Lusaka's clock")
    void writesTheHourTheDayAndTheMonthInLusaka() {
        assertThat(LusakaTime.write(Instant.parse("2026-11-17T12:05:00Z"), LUSAKA)).isEqualTo("14:05 on Tuesday 17 November");
    }

    @Test
    @DisplayName("a moment late in the evening in UTC is already the next day in Lusaka")
    void theDayIsLusakasOwn() {
        assertThat(LusakaTime.write(Instant.parse("2026-11-17T22:30:00Z"), LUSAKA)).isEqualTo("00:30 on Wednesday 18 November");
    }

    @Test
    @DisplayName("the writing does not depend on the language the server happens to run in")
    void theSameOnEveryServer() {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE);
            assertThat(LusakaTime.write(Instant.parse("2026-11-17T12:05:00Z"), LUSAKA)).isEqualTo("14:05 on Tuesday 17 November");
        } finally {
            java.util.Locale.setDefault(before);
        }
    }
}
