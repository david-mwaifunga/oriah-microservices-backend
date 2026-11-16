package com.oriah.notification.text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A moment as a message to staff writes it: the hour on the shop's own clock, then the day ({@code 14:05 on Tuesday 17 November}). No year: a
 * message is about today or the next few days. English, whatever language the server runs in.
 */
public final class LusakaTime {

    private static final DateTimeFormatter WRITTEN = DateTimeFormatter.ofPattern("HH:mm 'on' EEEE d MMMM", Locale.ENGLISH);

    private LusakaTime() {
    }

    /** @param zone the shop's zone ({@code oriah.notification.zone}, Africa/Lusaka) */
    public static String write(Instant moment, ZoneId zone) {
        return WRITTEN.format(moment.atZone(zone));
    }
}
