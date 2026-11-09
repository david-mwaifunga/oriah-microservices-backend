package com.oriah.search.events;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.oriah.search.SearchServiceApplication;
import com.oriah.search.repository.SearchIndexRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deleted product stays deleted after search restarts, when an older update
 * of it arrives only then.
 *
 * <p>Nothing about the order of snapshots may live only in memory: after a
 * restart (a deploy, a crash), Kafka resumes from the last committed offset
 * and can hand over anything not yet committed, in any order across a
 * rebalance. The newest seq per product is in processed_aggregate, in the
 * database, so a fresh instance knows product.deleted at seq 3 is already
 * applied and skips product.updated at seq 2.
 *
 * <p>Two whole application contexts run one after the other against ONE
 * Postgres container that outlives both -- the restart. (A container
 * declared as a bean, as the other tests do, would stop with the first
 * context and take the data with it.)
 */
class ProductEventRestartTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    @DisplayName("product.updated at seq 2 arriving after a restart does not bring back the product deleted at seq 3")
    void tombstoneSurvivesARestart() {
        UUID productId = UUID.randomUUID();

        try (ConfigurableApplicationContext before = startSearch()) {
            send(before, "product.created", productId, 1, ProductEventInboxTest.snapshot(productId, "Aurelia Solitaire"));
            send(before, "product.deleted", productId, 3, "{\"id\":\"" + productId + "\"}");
            assertThat(before.getBean(SearchIndexRepository.class).findById(productId)).isEmpty();
        }

        try (ConfigurableApplicationContext after = startSearch()) {
            send(after, "product.updated", productId, 2,
                    ProductEventInboxTest.snapshot(productId, "Aurelia Solitaire (edited before it was deleted)"));

            assertThat(after.getBean(SearchIndexRepository.class).findById(productId))
                    .as("the restarted service still knows seq 3 was applied, so seq 2 is skipped")
                    .isEmpty();
        }
    }

    private static void send(ConfigurableApplicationContext context, String type, UUID productId, long seq,
                             String payload) {
        context.getBean(InputDestination.class)
                .send(Relayed.productEvent(type, productId, seq, payload).build(), "catalog.product");
    }

    /** The search service as it starts in production, on the test binder and this test's database. */
    private static ConfigurableApplicationContext startSearch() {
        return new SpringApplicationBuilder(SearchServiceApplication.class, TestChannelBinderConfiguration.class,
                NoTokens.class)
                .initializers(context -> context.getBeanFactory()
                        .registerSingleton("leaveOutTestComponents", new LeaveOutTestComponents()))
                .properties(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.cloud.config.enabled=false",
                        "eureka.client.enabled=false",
                        "server.port=0",
                        "spring.cloud.function.definition=productEvents",
                        "spring.cloud.stream.bindings.productEvents-in-0.destination=catalog.product")
                .run();
    }

    /** No request is made here; refuse every token rather than ask Keycloak for its keys. */
    @TestConfiguration(proxyBeanMethods = false)
    static class NoTokens {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("no tokens in this test");
            };
        }
    }

    /**
     * What a @SpringBootTest does for its own contexts: keep the test tree's
     * configurations (TestcontainersConfiguration above all, which would start
     * a database of its own) out of the application's component scan.
     */
    static final class LeaveOutTestComponents extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getAnnotationMetadata().isAnnotated(TestComponent.class.getName());
        }
    }
}
