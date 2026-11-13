package com.oriah.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }

    /**
     * Inventory, for every test that imports this configuration, is {@link StubInventory}: one HTTP server for the whole run,
     * reached through the discovery client's static list (Eureka is off in tests). Registered here, as a registrar and not as a
     * {@code @DynamicPropertySource} on each test class, so that every such class keeps the SAME Spring context: a context takes
     * a minute to build and a differing property source would make each class build its own. A test that stands up a server of its own
     * (to play inventory failing in some particular way) says {@code oriah.test.stub-inventory=false} and registers it itself.
     */
    @Bean
    @ConditionalOnProperty(name = "oriah.test.stub-inventory", havingValue = "true", matchIfMissing = true)
    DynamicPropertyRegistrar inventoryIsTheStub() {
        return registry -> registry.add("spring.cloud.discovery.client.simple.instances.inventory[0].uri", StubInventory::uri);
    }

    /**
     * Promotion, likewise, is {@link StubPromotion}: with nothing registered it says no promotion is in force, so a test that is not about
     * promotions prices at the list price. A test that stands up a server of its own says {@code oriah.test.stub-promotion=false}.
     */
    @Bean
    @ConditionalOnProperty(name = "oriah.test.stub-promotion", havingValue = "true", matchIfMissing = true)
    DynamicPropertyRegistrar promotionIsTheStub() {
        return registry -> registry.add("spring.cloud.discovery.client.simple.instances.promotion[0].uri", StubPromotion::uri);
    }
}
