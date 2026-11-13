package com.oriah.inventory;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;

/**
 * Puts a clock a test can move in place of inventory's own, so a hold can be made to lapse without waiting. Tests that import the same
 * configurations share one Spring context, so every test that needs the clock imports this and resets it to {@link #T0} first.
 */
@TestConfiguration(proxyBeanMethods = false)
@PropertySource("classpath:inventory-test-clock.properties")
public class TestClockConfiguration {

    public static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");
    public static final MovableClock CLOCK = new MovableClock(T0);

    @Bean
    @Primary
    Clock testClock() {
        return CLOCK;
    }
}
