package com.oriah.order.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock the sale reads (hold lapses, claim times, the hold job's cut-offs), so a test can hold time still. The entity's own
 * {@code Instant.now()} stamps (placed-at, updated-at) are the database-facing times and stay as they are.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
