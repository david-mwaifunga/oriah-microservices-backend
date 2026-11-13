package com.oriah.promotion.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock the service reads the time from (so a test can hold it still), and the zone a promotion's days are counted in: Lusaka's
 * calendar days, whatever time zone the server runs in ({@code oriah.promotion.zone}).
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ZoneId promotionZone(@Value("${oriah.promotion.zone:Africa/Lusaka}") String zone) {
        return ZoneId.of(zone);
    }
}
