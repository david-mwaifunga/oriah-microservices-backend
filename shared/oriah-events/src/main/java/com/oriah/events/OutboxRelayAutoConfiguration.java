package com.oriah.events;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code oriah.events.relay.enabled=true}: an {@link OutboxRelay} publishes
 * the outbox to Kafka on a fixed delay ({@code oriah.events.relay.delay},
 * milliseconds, default 500).
 *
 * <p>Scheduling is switched on here because the relay cannot work without
 * it; a service that already enables scheduling is unaffected.
 */
@AutoConfiguration(after = OutboxAutoConfiguration.class)
@ConditionalOnBooleanProperty("oriah.events.relay.enabled")
@EnableConfigurationProperties(EventsProperties.class)
@EnableScheduling
public class OutboxRelayAutoConfiguration {

    @Bean
    OutboxRelay outboxRelay(DataSource dataSource, PlatformTransactionManager transactionManager,
                            StreamOperations streams, EventsProperties properties, Environment environment) {
        // Every refusal happens at startup, so a misconfigured service never
        // runs with a relay that publishes nothing, sends anonymous messages
        // or skips a row Kafka did not take.
        if (!properties.outbox().enabled()) {
            throw new IllegalStateException("oriah.events.relay.enabled needs oriah.events.outbox.enabled: "
                    + "the relay publishes what the outbox writer appends");
        }
        if (properties.producer() == null || properties.producer().isBlank()) {
            throw new IllegalStateException("oriah.events.relay.enabled needs oriah.events.producer: "
                    + "every message names the service that sent it");
        }
        SyncSends.require(environment, "oriah.events.relay.enabled",
                "the relay would move its cursor past a row Kafka never took: that event would never be published");
        return new OutboxRelay(dataSource, transactionManager, streams,
                properties.producer(), properties.relay().batchSize());
    }
}
