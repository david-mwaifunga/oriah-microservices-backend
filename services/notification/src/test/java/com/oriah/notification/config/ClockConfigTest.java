package com.oriah.notification.config;

import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notification tells time from one clock, so that a test can stand it still and move it: when a message is too old to send, when a reminder is due
 * and when an alert closes are all questions about "now".
 */
class ClockConfigTest {

    @Test
    @DisplayName("the service tells time from one clock, in UTC")
    void theServiceTellsTimeFromOneClock() {
        new ApplicationContextRunner().withUserConfiguration(ClockConfig.class).run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        });
    }
}
