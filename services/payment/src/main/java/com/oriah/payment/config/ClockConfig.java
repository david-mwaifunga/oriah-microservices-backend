package com.oriah.payment.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock payment reads "now" from.
 *
 * <p>A bean rather than {@code Instant.now()} scattered through the code, so a
 * test can move time: a reconciliation that runs days after the charge must
 * still record the charge at the moment the gateway decided it, and the only
 * way to prove that is to make those days pass.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
