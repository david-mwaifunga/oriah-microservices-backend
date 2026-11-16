package com.oriah.payment.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.oriah.payment.confirmation.SettlementWatcher;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * A number for dashboards: confirmed money that has waited past the settlement window with no outcome. (Notification's alert to staff does not read it: it is raised
 * by the watcher's second announcement of a confirmation.) Read from the database when the
 * meter is scraped (one cheap count), so it is right whatever the watcher is doing, and it is registered whether or not the watcher's schedule is on.
 *
 * <p>{@code strongReference(true)} is said out loud: a Micrometer gauge may hold only a WEAK reference to the object it reads (the other overloads do),
 * and the reader here is a lambda nothing else refers to, so a weak one would read NaN after the next garbage collection and an alert on "more than
 * zero" would never fire. The supplier form already holds it strongly by default; this keeps it so whatever form is used.
 * {@code ConfirmationGaugesTest} forces a collection and reads the gauge.
 */
@Configuration(proxyBeanMethods = false)
public class ConfirmationGauges {

    public static final String STUCK = "payment.confirmations.stuck";

    @Bean
    public MeterBinder stuckConfirmations(SettlementWatcher watcher, Clock clock) {
        return registry -> Gauge.builder(STUCK, () -> watcher.stuckCount(clock.instant()))
                .description("Confirmed money that has waited longer than the settlement window for order to say what became of it")
                .strongReference(true)
                .register(registry);
    }
}
