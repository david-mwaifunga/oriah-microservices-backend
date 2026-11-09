package com.oriah.events;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Wires the replay-request listener into Spring Cloud Stream, so that
 * {@code oriah.events.replay.enabled=true} is all a producing service sets.
 *
 * <p>When replay is on, it adds (ahead of every other setting):
 * <ul>
 *   <li>the listener, {@value #FUNCTION}, to
 *       {@code spring.cloud.function.definition}, after the service's own
 *       consumers. Spring Cloud Stream guesses which function to bind only
 *       when a service has exactly one; with two it binds none unless the
 *       definition lists them all, so the service's own list is kept and
 *       extended, never replaced;</li>
 *   <li>its binding: destination {@code oriah.outbox.replay-requests}, group
 *       {@code ${spring.application.name}}. Every producer service has its
 *       own group, so each one sees every request; within one service the
 *       instances share the group, so only one of them answers it;</li>
 *   <li>its dead-lettering ({@code enable-dlq}, and the dead-letter topic
 *       {@code oriah.outbox.replay-requests.dlq.${spring.application.name}}),
 *       with {@code IllegalArgumentException} marked not retryable: a
 *       malformed request fails the same way every time, so it goes to the
 *       dead-letter topic on its first delivery (see
 *       {@link DeadLetterCustomizer});</li>
 *   <li>its Kafka consumer settings: {@code max.poll.records=1} and
 *       {@code max.poll.interval.ms=300000}. One request -- one replay step
 *       -- per poll, so the time Kafka allows between two polls is the time
 *       one step may take; {@link ReplayStepTime} checks at startup that the
 *       slowest step fits.</li>
 * </ul>
 * The listener is the module's, so these win over anything the service sets
 * for it: the step protocol depends on them.
 *
 * <p>It runs after Boot has loaded the service's own configuration files
 * (it is ordered last), so it sees the service's definition before
 * extending it.
 */
public class OutboxReplayBinding implements EnvironmentPostProcessor, Ordered {

    static final String FUNCTION = "outboxReplayRequests";

    private static final String DEFINITION = "spring.cloud.function.definition";
    /** The listener's binding name. */
    static final String BINDING_NAME = FUNCTION + "-in-0";

    /** The time Kafka allows the listener between two polls: one replay step. */
    static final long MAX_POLL_INTERVAL_MS = 300_000;

    private static final String BINDING = "spring.cloud.stream.bindings." + BINDING_NAME + ".";
    private static final String KAFKA_BINDING = "spring.cloud.stream.kafka.bindings." + BINDING_NAME + ".consumer.";
    private static final String SOURCE = "oriahEventsReplayBinding";

    /** The module's four switches, each read by an auto-configuration's condition. */
    static final List<String> SWITCHES = List.of(
            "oriah.events.outbox.enabled",
            "oriah.events.relay.enabled",
            "oriah.events.replay.enabled",
            "oriah.events.inbox.enabled");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        SWITCHES.forEach(name -> requireTrueOrFalse(environment, name));
        if (!"true".equalsIgnoreCase(environment.getProperty("oriah.events.replay.enabled"))) {
            return;
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(DEFINITION, definition(environment.getProperty(DEFINITION)));
        settings.put(BINDING + "destination", ReplayRequest.TOPIC);
        settings.put(BINDING + "group", "${spring.application.name}");
        // A request that keeps failing goes to a dead-letter topic of the
        // service's own (see DeadLetterCustomizer), never dropped.
        settings.put(KAFKA_BINDING + "enable-dlq", "true");
        settings.put(KAFKA_BINDING + "dlq-name", ReplayRequest.TOPIC + ".dlq.${spring.application.name}");
        // A malformed request fails the same way every time: dead-lettered
        // on its first delivery, not tried again.
        settings.put(BINDING + "consumer.retryable-exceptions." + IllegalArgumentException.class.getName(), "false");
        // One request (one replay step) per poll, and five minutes for it.
        // Kafka presumes a listener dead when it takes longer than
        // max.poll.interval.ms between polls; with one record per poll that
        // time is one step's, which ReplayStepTime checks at startup fits.
        settings.put(KAFKA_BINDING + "configuration.max.poll.records", "1");
        settings.put(KAFKA_BINDING + "configuration.max.poll.interval.ms", Long.toString(MAX_POLL_INTERVAL_MS));
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, settings));
    }

    /**
     * A switch is true, false or unset. Spring reads other spellings
     * differently in different places -- its converter takes "on", "yes" and
     * "1" as true, while an auto-configuration's condition takes only "true"
     * -- so "on" could bind the replay listener with no bean behind it, or
     * pass the "replay needs the relay" check with no relay running. Refused
     * here, before either reads it.
     */
    private static void requireTrueOrFalse(ConfigurableEnvironment environment, String name) {
        String value = environment.getProperty(name);
        if (value != null && !"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalStateException(name + "=" + value + " is refused: a switch is true or false");
        }
    }

    /** The service's own functions, then the listener, listed once even if this runs twice. */
    private static String definition(String existing) {
        if (!StringUtils.hasText(existing)) {
            return FUNCTION;
        }
        boolean listed = Arrays.stream(existing.split(";")).map(String::trim).anyMatch(FUNCTION::equals);
        return listed ? existing : existing + ";" + FUNCTION;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
