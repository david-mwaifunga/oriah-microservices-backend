package com.oriah.inventory;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Provides a {@link PostgreSQLContainer} for {@code @SpringBootTest} runs.
 * {@code @ServiceConnection} hands Spring Boot the JDBC URL/credentials
 * automatically so tests don't have to wire datasource properties.
 *
 * <p>It also brings the Spring Cloud Stream TEST binder, for every context. Inventory asks procurement for a replay
 * of its history when it starts (the catch-up, which SENDS, and refuses to start if the send fails), so a context
 * with no binder would try a real Kafka at localhost:9092 and fail to start. With the test binder the send is
 * accepted, and tests can hand events to the consumers through {@code InputDestination}.
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
