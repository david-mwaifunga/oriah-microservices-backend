package com.oriah.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.oriah.payment.service.PaymentReconciler;

/**
 * Runs {@link PaymentReconciler#reconcile()} every
 * {@code oriah.payment.reconciler.delay} milliseconds after the previous pass
 * ends, while {@code oriah.payment.reconciler.enabled} is true.
 *
 * <p>application.yaml switches it on for every profile. The tests switch it
 * off (src/test/resources/config/application.yaml) and call the reconciler
 * themselves, so a pass can never land in the middle of a test that is
 * looking at a CHARGING intent; ReconcilerScheduleTest switches it back on to
 * prove this class.
 *
 * <p>A pass that throws is logged by the scheduler and the next one runs as
 * usual; the reconciler already catches a failure per intent.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty("oriah.payment.reconciler.enabled")
public class ReconcilerSchedule {

    private final PaymentReconciler reconciler;

    public ReconcilerSchedule(PaymentReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${oriah.payment.reconciler.delay}",
            initialDelayString = "${oriah.payment.reconciler.delay}")
    public void run() {
        reconciler.reconcile();
    }
}
