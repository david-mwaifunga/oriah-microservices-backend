package com.oriah.procurement.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's clock, a bean so a test can say what time it is. Receiving stamps the moment the
 * goods landed from it; nothing else in the service reads the time directly.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
