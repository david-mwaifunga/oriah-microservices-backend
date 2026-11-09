package com.oriah.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.oriah.events.probe.ProbeApplication;

import static com.oriah.events.Relayed.all;
import static com.oriah.events.Relayed.headers;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A restart: the service stops part-way through its backlog and a new
 * process starts on the same database. The relay keeps nothing in memory --
 * its position is the cursor row -- so the new process carries on from
 * exactly where the old one stopped: nothing re-sent, nothing missed.
 *
 * <p>Two real application contexts on one Postgres container, the second
 * started after the first is closed.
 */
class OutboxRelayRestartTest {

    private static final String TOPIC = "oriah.probe";

    private static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @BeforeAll
    static void startDatabase() {
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    @Test
    @DisplayName("after a restart the relay resumes from the cursor: the rest of the backlog, in order, nothing twice")
    void resumesFromTheCursor() {
        List<AppendedEvent> appended = new ArrayList<>();
        try (ConfigurableApplicationContext before = startService()) {
            OutboxWriter writer = before.getBean(OutboxWriter.class);
            TransactionTemplate transaction = new TransactionTemplate(before.getBean(PlatformTransactionManager.class));
            String order = UUID.randomUUID().toString();
            for (int i = 0; i < 5; i++) {
                appended.add(transaction.execute(status -> writer.append(draft(order))));
            }

            // Batch size 2: one round publishes the first two, then the "crash".
            assertThat(before.getBean(OutboxRelay.class).relayBatch()).isEqualTo(2);
            assertThat(headers(all(before.getBean(OutputDestination.class), TOPIC), "event-id"))
                    .containsExactly(eventId(appended, 0), eventId(appended, 1));
        }

        try (ConfigurableApplicationContext after = startService()) {
            OutboxRelay relay = after.getBean(OutboxRelay.class);
            int sent = 0;
            int round;
            while ((round = relay.relayBatch()) > 0) {
                sent += round;
            }

            assertThat(sent).isEqualTo(3);
            assertThat(headers(all(after.getBean(OutputDestination.class), TOPIC), "event-id"))
                    .containsExactly(eventId(appended, 2), eventId(appended, 3), eventId(appended, 4));
        }
    }

    private static ConfigurableApplicationContext startService() {
        return new SpringApplicationBuilder(ProbeApplication.class, TestChannelBinderConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.flyway.locations=classpath:db/oriah-events",
                        "oriah.events.producer=probe",
                        "oriah.events.topics=" + TOPIC,
                        "oriah.events.outbox.enabled=true",
                        "oriah.events.relay.enabled=true",
                        "oriah.events.relay.delay=3600000",
                        "oriah.events.relay.batch-size=2",
                        "spring.cloud.stream.kafka.default.producer.sync=true")
                .run();
    }

    private static String eventId(List<AppendedEvent> appended, int index) {
        return appended.get(index).eventId().toString();
    }

    private static EventDraft draft(String orderId) {
        return new EventDraft("order", orderId, "order.created", 1, TOPIC,
                Instant.parse("2026-11-09T07:30:00Z"), "system:probe", Map.of("orderId", orderId));
    }
}
