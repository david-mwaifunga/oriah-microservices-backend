package com.oriah.finance;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The real Postgres every test runs against, and the in-memory stream binder.
 *
 * <p>The binder is imported HERE so that every {@code @SpringBootTest} gets it
 * (they all import this class). Without it a test context binds the real Kafka
 * binder: about 60 seconds waiting for the replay listener's topic to be set up
 * at start, 30 more at shutdown, and with the dev stack running the test would
 * join the REAL consumer group, could answer a real replay request from its
 * throwaway database, and would publish test events to the real topic.
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
