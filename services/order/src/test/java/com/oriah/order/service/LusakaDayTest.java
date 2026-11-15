package com.oriah.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The calendar day in Lusaka (Africa/Lusaka: UTC+2, no daylight saving), the day a paper sale and the money that paid it must share (Phase 9): a sale counts on
 * the day it happened and is paid (Q22), and a month's profit is a count of days, so "the same day" is judged where the shop is, not in UTC.
 */
class LusakaDayTest {

    @Test
    @DisplayName("the day changes at 22:00 UTC, which is midnight in Lusaka")
    void midnightIsTwoHoursBeforeUtc() {
        assertThat(LusakaDay.same(Instant.parse("2026-11-12T21:59:59.999999Z"), Instant.parse("2026-11-12T22:00:00Z"))).isFalse();
        assertThat(LusakaDay.same(Instant.parse("2026-11-12T22:00:00Z"), Instant.parse("2026-11-13T21:59:59.999999Z"))).isTrue();
        assertThat(LusakaDay.same(Instant.parse("2026-11-12T22:00:00Z"), Instant.parse("2026-11-13T22:00:00Z"))).isFalse();
    }

    @Test
    @DisplayName("two moments of one Lusaka day are the same day whatever their UTC dates say")
    void sameDayAcrossTheUtcDate() {
        // 23:30 UTC on the 12th is 01:30 on the 13th in Lusaka, the same day as 08:00 UTC (10:00) on the 13th.
        assertThat(LusakaDay.same(Instant.parse("2026-11-12T23:30:00Z"), Instant.parse("2026-11-13T08:00:00Z"))).isTrue();
        assertThat(LusakaDay.same(Instant.parse("2026-11-12T08:00:00Z"), Instant.parse("2026-11-12T23:30:00Z"))).isFalse();
    }

    @Test
    @DisplayName("an instant is the same day as itself, and the order of the two does not matter")
    void symmetric() {
        Instant a = Instant.parse("2026-11-12T08:00:00Z");
        Instant b = Instant.parse("2026-11-12T20:00:00Z");

        assertThat(LusakaDay.same(a, a)).isTrue();
        assertThat(LusakaDay.same(a, b)).isEqualTo(LusakaDay.same(b, a));
        assertThat(LusakaDay.same(a, b)).isTrue();
    }
}
