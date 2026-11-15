package com.oriah.finance.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's clock, a bean so a test can say what time it is. Nothing else in the service reads the time directly (the database's own clock stamps what
 * finance learned and when; see the ledger).
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
