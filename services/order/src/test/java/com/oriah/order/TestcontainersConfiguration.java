package com.oriah.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres for every test context -- and the Spring Cloud Stream test
 * binder in place of Kafka, for every context too.
 *
 * <p>Why the binder is here and not left to each test class: since the event
 * log (Phase 1), order binds a listener at startup (replay requests, on
 * {@code oriah.outbox.replay-requests}) and runs the relay. A context on the
 * real Kafka binder would reach for the broker at localhost:9092 as it
 * starts: with none there, startup waits for the binding to time out; with
 * the development stack's Kafka running, the test would join order's real
 * consumer group and could answer a real replay request from a test
 * database. So no order test may ever run on the Kafka binder.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestChannelBinderConfiguration.class)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }
}
