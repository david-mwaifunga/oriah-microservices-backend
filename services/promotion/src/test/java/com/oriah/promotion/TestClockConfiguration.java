package com.oriah.promotion;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Puts a clock a test can move in place of the service's own. Tests that import it share one context and reset the clock first. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    /** 14 November 2026, 10:00 in Lusaka (UTC+2, no summer time). */
    public static final Instant T0 = Instant.parse("2026-11-14T08:00:00Z");
    public static final MovableClock CLOCK = new MovableClock(T0);

    @Bean
    @Primary
    Clock testClock() {
        return CLOCK;
    }
}
