package com.oriah.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;

import com.oriah.events.probe.ProbeApplication;

/**
 * One shared test setup, so every test class that uses it shares one Spring
 * context and one Postgres container: the probe application with every
 * switch on (outbox, relay, replay and inbox -- a service may be producer
 * and consumer at once), the reference SQL migrated by Flyway exactly as a
 * service would run it, and the Spring Cloud Stream test binder standing in
 * for Kafka.
 *
 * <p>The relay's schedule is set to an hour so it never fires on its own
 * here: these tests run the relay by hand, so they decide exactly when a
 * batch happens. The schedule itself is proved in its own test class.
 *
 * <p>The outbox and processed_event are append-only, so no test can clean
 * them up. Tests therefore use fresh aggregate ids and empty the relay's
 * backlog before they start, rather than relying on empty tables.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(classes = ProbeApplication.class, properties = {
        "spring.application.name=probe",
        "spring.flyway.locations=classpath:db/oriah-events",
        "oriah.events.producer=probe",
        "oriah.events.topics=oriah.probe,oriah.probe-other",
        "oriah.events.outbox.enabled=true",
        "oriah.events.relay.enabled=true",
        "oriah.events.relay.delay=3600000",
        "oriah.events.relay.batch-size=100",
        "oriah.events.replay.enabled=true",
        "oriah.events.inbox.enabled=true",
        // The test binder ignores it, but the relay refuses to start without
        // it, exactly as in a service (see SyncSends).
        "spring.cloud.stream.kafka.default.producer.sync=true",
        // The send timeouts the README asks of a producing service. The test
        // binder ignores them, but replay refuses to start unless a whole
        // replay step fits the time Kafka allows its listener between polls
        // (see ReplayStepTime), exactly as in a service.
        "spring.cloud.stream.kafka.binder.producer-properties.max.block.ms=10000",
        "spring.cloud.stream.kafka.binder.producer-properties.request.timeout.ms=5000",
        "spring.cloud.stream.kafka.binder.producer-properties.delivery.timeout.ms=10000",
        // The Inbox's topic and its binding, and dead-lettering on that
        // binding, as a service sets them. These tests call the Inbox
        // directly and the test binder ignores all of it, but the module
        // refuses to start without it (see DeadLetterStartupCheck), exactly
        // as in a service. (The replay-request listener's dead-lettering is
        // the module's own: see OutboxReplayBinding.)
        "oriah.events.inbox.topics=oriah.probe",
        "spring.cloud.stream.bindings.probeEvents-in-0.destination=oriah.probe",
        "spring.cloud.stream.bindings.probeEvents-in-0.group=probe",
        "spring.cloud.stream.kafka.default.consumer.enable-dlq=true",
        "spring.cloud.stream.kafka.bindings.probeEvents-in-0.consumer.dlq-name=oriah.probe.dlq.probe"
})
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
public @interface ProbeTest {
}
