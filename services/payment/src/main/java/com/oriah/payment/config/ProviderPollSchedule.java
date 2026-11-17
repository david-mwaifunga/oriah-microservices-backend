package com.oriah.payment.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.oriah.payment.provider.ProviderPaymentChecks;

/**
 * Runs the checker ({@link ProviderPaymentChecks#poll}) every {@code oriah.payment.zynlepay.poll.delay} milliseconds after the previous pass
 * ends, while {@code oriah.payment.zynlepay.poll.enabled} is true (Phase 13).
 *
 * <p>application.yaml switches it on for every profile: without it a payment ZynlePay collected would be recorded only when a callback came or a
 * person pressed "check now". The tests switch it off (src/test/resources/config/application.yaml) and call the checker themselves, so a pass can
 * never land in the middle of a test; {@code ProviderPollScheduleTest} switches it back on to prove this class. A pass that throws is logged by
 * the scheduler and the next one runs as usual; each attempt is asked about and recorded on its own.
 *
 * <p>It has its own thread of the scheduling pool (three: the relay, the settlement watcher and this), so a pass waiting on ZynlePay cannot hold
 * back a payment announcement.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("oriah.payment.zynlepay.poll.enabled")
public class ProviderPollSchedule {

    private final ProviderPaymentChecks checks;
    private final Clock clock;

    public ProviderPollSchedule(ProviderPaymentChecks checks, Clock clock) {
        this.checks = checks;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${oriah.payment.zynlepay.poll.delay}", initialDelayString = "${oriah.payment.zynlepay.poll.delay}")
    public void run() {
        checks.poll(clock.instant());
    }
}
