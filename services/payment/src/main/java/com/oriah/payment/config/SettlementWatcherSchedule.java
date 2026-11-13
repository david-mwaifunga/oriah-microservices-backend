package com.oriah.payment.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.oriah.payment.confirmation.SettlementWatcher;

/**
 * Runs {@link SettlementWatcher#reemitDue} every {@code oriah.payment.watcher.delay} milliseconds after the previous pass ends, while
 * {@code oriah.payment.watcher.enabled} is true.
 *
 * <p>application.yaml switches it on for every profile. The tests switch it off (src/test/resources/config/application.yaml) and call the watcher
 * themselves, so a pass can never land in the middle of a test that is counting announcements; {@code WatcherScheduleTest} switches it back on to
 * prove this class. A pass that throws is logged by the scheduler and the next one runs as usual; each confirmation is handled in a transaction of
 * its own.
 *
 * <p>It needs its own thread: the scheduling pool has three (the relay, the card reconciler and this), so a slow pass cannot hold back the others.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("oriah.payment.watcher.enabled")
public class SettlementWatcherSchedule {

    private final SettlementWatcher watcher;
    private final Clock clock;

    public SettlementWatcherSchedule(SettlementWatcher watcher, Clock clock) {
        this.watcher = watcher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${oriah.payment.watcher.delay}", initialDelayString = "${oriah.payment.watcher.delay}")
    public void run() {
        watcher.reemitDue(clock.instant());
    }
}
