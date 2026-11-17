package com.oriah.order.config;

import com.oriah.order.service.CorrectionJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs {@link CorrectionJob#run} every {@code oriah.sales.correction-job.delay} milliseconds after the previous pass ends, while
 * {@code oriah.sales.correction-job.enabled} is true (Phase 12). On in every profile; the tests switch it off and call the job themselves, so a pass
 * can never land mid-test; {@code CorrectionJobScheduleTest} switches it back on to prove it. It needs its own thread: order's scheduling pool is four
 * (the outbox relay, the hold job, the settlement job and this), so a pass waiting on a slow inventory cannot hold the others back. Add a thread for
 * any further {@code @Scheduled} job.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("oriah.sales.correction-job.enabled")
public class CorrectionJobSchedule {

    private final CorrectionJob job;

    public CorrectionJobSchedule(CorrectionJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${oriah.sales.correction-job.delay:15000}", initialDelayString = "${oriah.sales.correction-job.delay:15000}")
    public void run() {
        job.run();
    }
}
