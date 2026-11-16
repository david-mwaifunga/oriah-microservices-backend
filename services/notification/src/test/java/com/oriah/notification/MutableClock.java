package com.oriah.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The service's clock in tests: it stands still until a test moves it.
 *
 * <p>It starts one minute after the moment every test event says it was recorded ({@code Relayed}: 2026-11-09T07:30:00.125Z), so a fresh event
 * is well inside the time a message is still worth sending. {@code TestData.clean} puts it back before every test, so one test moving the clock
 * a day ahead cannot make the next one's messages "too late".
 */
public final class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-11-09T07:31:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    public void set(Instant instant) {
        now.set(instant);
    }

    public void advance(Duration by) {
        now.updateAndGet(instant -> instant.plus(by));
    }

    public void reset() {
        now.set(START);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
