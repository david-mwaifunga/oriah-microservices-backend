package com.oriah.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * What every payment test context runs against: a real Postgres in a
 * container, and Spring Cloud Stream's in-memory test binder in place of
 * Kafka.
 *
 * <p>The test binder is imported here, for every test, because the outbox
 * relay runs in every context (it is switched on in application.yaml). With
 * the Kafka binder in its place, a test that settles a payment would publish
 * payment.succeeded to whatever listens on localhost:9092 -- the dev stack's
 * own Kafka, when it is up. Tests that read what was published take the
 * {@code OutputDestination} bean this brings.
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
