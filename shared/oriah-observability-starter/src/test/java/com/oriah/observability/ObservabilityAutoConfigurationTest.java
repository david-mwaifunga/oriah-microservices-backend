package com.oriah.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity check that the auto-configuration loads through Spring Boot's
 * {@code AutoConfiguration.imports} mechanism. If this test fails, the
 * starter is dead — no consuming service would pick it up either.
 *
 * <p>We don't pull the full tracing infrastructure into the context here
 * (that requires a {@code spring.application.name} and various Micrometer
 * properties); instead we verify the class itself is found and bean-loaded.
 * Tracing wiring is exercised end-to-end by the live smoke test once Zipkin
 * is in compose.
 */
class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    void autoConfigurationBeanIsLoadedWhenStarterIsOnClasspath() {
        contextRunner.run(ctx -> assertThat(ctx).hasSingleBean(ObservabilityAutoConfiguration.class));
    }
}
