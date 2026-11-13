package com.oriah.order.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.oriah.order.service.HoldJob;

/**
 * Runs {@link HoldJob#run} every {@code oriah.sales.hold-job.delay} milliseconds after the previous pass ends, while
 * {@code oriah.sales.hold-job.enabled} is true.
 *
 * <p>application.yaml switches it on for every profile. The tests switch it off (src/test/resources/config/application.yaml) and call the job
 * themselves, so a pass can never land in the middle of a test that is counting orders. A pass that throws is logged by the scheduler and the next
 * runs as usual.
 *
 * <p>It needs its own thread: order's scheduling pool is two (the outbox relay and this), so a slow pass cannot hold the relay back, nor a relay round
 * waiting on a stopped Kafka hold this up. Add a thread for any further {@code @Scheduled} job.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("oriah.sales.hold-job.enabled")
public class HoldJobSchedule {

    private final HoldJob job;

    public HoldJobSchedule(HoldJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${oriah.sales.hold-job.delay:15000}", initialDelayString = "${oriah.sales.hold-job.delay:15000}")
    public void run() {
        job.run();
    }
}
