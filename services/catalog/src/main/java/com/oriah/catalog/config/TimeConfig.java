package com.oriah.catalog.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one clock, so a rule about dates (a price may start up to 72 hours back) can be tested against a fixed instant. */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
