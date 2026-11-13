package com.oriah.order.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.oriah.order.service.SettlementJob;

/**
 * Runs {@link SettlementJob#run} every {@code oriah.sales.settlement-job.delay} milliseconds after the previous pass ends, while
 * {@code oriah.sales.settlement-job.enabled} is true. On in every profile; the tests switch it off and call the job themselves, so a pass can never
 * land mid-test; {@code SettlementJobScheduleTest} switches it back on to prove it. It needs its own thread: order's scheduling pool is three (the
 * outbox relay, the hold job and this), so a slow pass cannot hold the others back. Add a thread for any further {@code @Scheduled} job.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("oriah.sales.settlement-job.enabled")
public class SettlementJobSchedule {

    private final SettlementJob job;

    public SettlementJobSchedule(SettlementJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${oriah.sales.settlement-job.delay:15000}", initialDelayString = "${oriah.sales.settlement-job.delay:15000}")
    public void run() {
        job.run();
    }
}
