package com.oriah.observability;

import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the property that turns OTLP <em>metrics</em> export off, and the fact
 * that it is on by default.
 *
 * <h2>The defect this came from</h2>
 *
 * <p>Every service logged this every 60 seconds, with a full stack trace,
 * from the day the stack was built:
 *
 * <pre>
 * WARN OtlpMeterRegistry Failed to publish metrics to OTLP receiver
 *   (url=http://localhost:4318/v1/metrics, resource-attributes={service.name=cart})
 * java.net.ConnectException: Connection refused
 * </pre>
 *
 * <p>It was 20% of all idle log volume. The cause is that nothing ever
 * configured an OTLP <em>metrics</em> URL — a repo-wide search of every
 * service, shared and infra {@code resources/*.yaml} for {@code otlp} finds
 * only the <em>tracing</em> endpoint — so Micrometer fell back to its default
 * of {@code http://localhost:4318/v1/metrics}, which inside a container is the
 * service itself. Nothing listens there.
 *
 * <p>The registry is on the classpath only because it rides in transitively
 * with the tracing stack this starter declares
 * ({@code spring-boot-starter-opentelemetry} +
 * {@code spring-boot-micrometer-tracing-opentelemetry}). Nobody asked for OTLP
 * metrics; Spring Boot simply auto-configures the registry when it sees the
 * jar. So the honest fix is to turn the registry off, not to stand up a second
 * metrics pipeline — Prometheus already scrapes
 * {@code /actuator/prometheus} and {@code smoke-test.ps1} step 13 asserts all
 * twelve services report {@code up=1}. Removing the jar was considered and
 * rejected: it would risk a working trace pipeline to silence a log line.
 *
 * <h2>Why this test exists at all</h2>
 *
 * <p>It does not prove the fix is deployed — the property lives in
 * {@code config-repo/application.yaml} and the behavioural gate is the live
 * smoke-test assertion of zero OTLP publish failures. What it proves is the
 * part most likely to be silently wrong: <strong>the property name</strong>.
 * Boot 4 moved this configuration from
 * {@code org.springframework.boot.actuate.autoconfigure.metrics.export.otlp}
 * to {@code org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp},
 * and a renamed or mistyped property binds to nothing, fails silently, and
 * looks exactly like a working fix until someone reads the logs again.
 *
 * <p>The first assertion is the one that documents the bug: with no property
 * set, the registry <em>is</em> created. That is why this shipped broken.
 */
class OtlpMetricsExportDisabledTest {

    // MetricsAutoConfiguration is required, not decoration: the OTLP
    // auto-configuration is @ConditionalOnBean(Clock.class) and that Clock is
    // what supplies it. Without it the registry is absent for a reason that has
    // nothing to do with the property under test, and all three assertions
    // below would agree on the wrong answer.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MetricsAutoConfiguration.class,
                    OtlpMetricsExportAutoConfiguration.class));

    @Test
    @DisplayName("by default the OTLP meter registry is created — which is how this shipped broken")
    void registryIsOnByDefault() {
        runner.run(context -> assertThat(context)
                .as("if this fails, Boot changed the default and the whole "
                        + "premise of the fix needs re-checking")
                .hasSingleBean(OtlpMeterRegistry.class));
    }

    @Test
    @DisplayName("management.otlp.metrics.export.enabled=false removes it")
    void theDisableFlagActuallyBinds() {
        // Verified against the jar before being written here: javap -v on
        // OtlpMetricsProperties in spring-boot-micrometer-metrics-4.0.6.jar
        // reports @ConfigurationProperties("management.otlp.metrics.export"),
        // and OtlpMetricsExportAutoConfiguration carries
        // @ConditionalOnEnabledMetricsExport, which is what reads <prefix>.enabled.
        runner.withPropertyValues("management.otlp.metrics.export.enabled=false")
                .run(context -> assertThat(context)
                        .as("the property did not bind — a silent no-op fix")
                        .doesNotHaveBean(OtlpMeterRegistry.class));
    }

    @Test
    @DisplayName("a mistyped property does NOT disable it, so this test can fail")
    void aWrongPropertyNameLeavesItEnabled() {
        // The control. Without this, the test above would pass just as happily
        // against a property name that binds to nothing, which is precisely the
        // failure mode it is supposed to catch.
        runner.withPropertyValues("management.otlp.metrics.enabled=false")
                .run(context -> assertThat(context)
                        .hasSingleBean(OtlpMeterRegistry.class));
    }
}
