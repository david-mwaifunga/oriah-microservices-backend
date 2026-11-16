package com.oriah.notification.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock notification tells time from. Whether a message is too old to send, when a reminder is due and when an alert closes are all
 * questions about "now"; asking one bean lets a test stand time still and move it.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
