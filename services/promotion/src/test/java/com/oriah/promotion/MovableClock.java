package com.oriah.promotion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** A clock a test can set and move, cut to microseconds (Postgres's precision) so a time written and read back compares equal. */
public final class MovableClock extends Clock {

    private volatile Instant now;

    public MovableClock(Instant start) {
        set(start);
    }

    public void set(Instant at) {
        this.now = at.truncatedTo(ChronoUnit.MICROS);
    }

    public void advance(Duration by) {
        set(now.plus(by));
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("promotion keeps every time in UTC and counts days in its own zone");
    }
}
