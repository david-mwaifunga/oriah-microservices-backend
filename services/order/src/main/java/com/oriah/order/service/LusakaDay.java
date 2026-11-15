package com.oriah.order.service;

import java.time.Instant;
import java.time.ZoneId;

/**
 * The calendar day in Lusaka ({@code Africa/Lusaka}: UTC+2, no daylight saving), judged where the shop is, never in UTC (Phase 9). A sale counts on the day it
 * happened and is paid (Q22), and a month's profit is a count of days: a paper sale at ten at night and the money that paid it at seven the next morning are
 * two days, however UTC dates them.
 */
public final class LusakaDay {

    private static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");

    private LusakaDay() {
    }

    /** Whether the two moments fall on the same calendar day in Lusaka. */
    public static boolean same(Instant a, Instant b) {
        return a.atZone(LUSAKA).toLocalDate().equals(b.atZone(LUSAKA).toLocalDate());
    }
}
