package com.oriah.order.events;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Order's event-log settings, read from its own application.yaml -- the file
 * every profile and every test starts from, so no environment can run order
 * without them.
 *
 * <p>Order is a producer: it appends (outbox), publishes (relay) and answers
 * replay requests (replay). Since Phase 7 it also consumes: it owns the sale,
 * so it hears what became of the money (oriah.payment) and of the hold
 * (oriah.inventory) through the Inbox, each event applied once.
 *
 * <p>The binder settings are the ones the relay depends on. {@code sync=true}
 * makes a send return only once Kafka has the record (the relay refuses to
 * start without it). The three timeouts cap how long one send may wait for a
 * broker that is down, because the relay holds its transaction and its place
 * in the log while it waits; with Kafka's own defaults that is a minute or
 * two, with these about 10 to 25 seconds.
 */
class EventLogConfigTest {

    private static Binder config;

    @BeforeAll
    static void load() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addLast);
        config = Binder.get(environment);
    }

    private static String value(String name) {
        return config.bind(name, String.class).orElse(null);
    }

    @Test
    @DisplayName("order writes, publishes and replays its log, and (Phase 7) reads payment's and inventory's, each event once: its Inbox is on")
    void switches() {
        assertThat(value("oriah.events.outbox.enabled")).isEqualTo("true");
        assertThat(value("oriah.events.relay.enabled")).isEqualTo("true");
        assertThat(value("oriah.events.replay.enabled")).isEqualTo("true");
        assertThat(value("oriah.events.inbox.enabled")).isEqualTo("true");
        assertThat(value("oriah.events.inbox.topics")).isEqualTo("oriah.payment,oriah.inventory");
        assertThat(value("oriah.events.producer")).isEqualTo("order");
        assertThat(value("oriah.events.topics")).isEqualTo("oriah.order");
        assertThat(value("spring.application.name")).isEqualTo("order");
    }

    @Test
    @DisplayName("every send waits for Kafka, as JSON, and gives up on a broker that is down within seconds")
    void binder() {
        assertThat(value("spring.cloud.stream.kafka.default.producer.sync")).isEqualTo("true");
        // The Binder asks by the canonical (kebab-case) name; it reads contentType too.
        assertThat(value("spring.cloud.stream.default.content-type")).isEqualTo("application/json");
        // Bound as the Kafka binder binds them: a map whose keys keep their dots.
        Map<String, String> producer = config.bind("spring.cloud.stream.kafka.binder.producer-properties",
                Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        assertThat(producer).containsEntry("max.block.ms", "10000")
                .containsEntry("request.timeout.ms", "5000")
                .containsEntry("delivery.timeout.ms", "10000");
    }

    @Test
    @DisplayName("the old direct-send binding is gone: the relay sends to each row's own topic")
    void noDirectSendBinding() {
        // orderEvents-out-0 was how order sent from inside its transaction,
        // before the row it described was written. Nothing sends through it
        // now; left configured, it would invite exactly that send back.
        assertThat(config.bind("spring.cloud.stream.bindings.order-events-out-0",
                Bindable.mapOf(String.class, String.class)).orElse(Map.of())).isEmpty();
    }
}
