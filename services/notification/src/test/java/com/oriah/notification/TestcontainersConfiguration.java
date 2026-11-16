package com.oriah.notification;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        // logServerErrorDetail=false, as on the service's own URLs: the database never quotes the row it refused, so an address in a
        // refused row cannot reach an exception message, and through it a log line or a dead-letter record.
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).withUrlParam("logServerErrorDetail", "false");
    }

    /** The service's clock in every database test: it stands still until a test moves it (see {@link MutableClock}). */
    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
