package com.oriah.observability;

import java.util.List;
import java.util.Map;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate that decides HOW the trace backend gets swapped from Zipkin to
 * Tempo.
 *
 * <p>The plan was to add OTLP export alongside Zipkin, prove Tempo receives
 * spans, and only then remove Zipkin in a separate commit — so that
 * {@code smoke-test.ps1}'s end-to-end trace assertion never goes dark, not
 * even for one commit. That plan only works if both exporters can be active
 * simultaneously. Spring Boot's tracing auto-configurations are full of
 * {@code @ConditionalOnMissingBean} and {@code @ConditionalOnEnabledTracing}
 * guards, and it is entirely plausible that one backs off in the presence of
 * the other, in which case "parallel run" would silently mean "Zipkin only"
 * (and the later removal commit would be the first time Tempo was ever
 * exercised — exactly the untested cutover the plan was designed to avoid).
 *
 * <p>So: measure it. This test asserts nothing about which exporter *should*
 * win; it records which ones are actually wired when both
 * {@code spring-boot-zipkin} and {@code spring-boot-starter-opentelemetry} are
 * on the classpath, and prints the bean names so the answer is visible in the
 * build output.
 *
 * <p>If both are present, the staged swap proceeds as planned. If only one is,
 * the swap becomes a single atomic commit that adds the Tempo assertion and
 * removes the Zipkin one together.
 */
@SpringBootTest(
        classes = SpanExporterCoexistenceTest.ProbeApp.class,
        properties = {
                "spring.application.name=exporter-probe",
                "spring.main.banner-mode=off",
                "management.tracing.sampling.probability=1.0",
                // Both exporters pointed at closed ports: we are inspecting the
                // bean graph, not shipping spans, and this keeps their
                // background senders from retrying against the real stack.
                "management.tracing.export.zipkin.endpoint=http://127.0.0.1:1/api/v2/spans",
                "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:1/v1/traces"
        })
class SpanExporterCoexistenceTest {

    @SpringBootApplication
    static class ProbeApp {
    }

    @org.springframework.beans.factory.annotation.Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("records which SpanExporters are active with both backends on the classpath")
    void spanExportersOnTheClasspath() {
        Map<String, SpanExporter> exporters = context.getBeansOfType(SpanExporter.class);

        List<String> described = exporters.entrySet().stream()
                .map(e -> e.getKey() + " -> " + e.getValue().getClass().getName())
                .sorted()
                .toList();

        System.out.println("### SpanExporter beans: " + described);

        // The one thing that must hold regardless of the outcome: tracing is
        // wired at all. A context with zero exporters would mean spans are
        // produced and silently dropped, which is the failure mode this whole
        // tier exists to avoid.
        assertThat(exporters)
                .as("no SpanExporter bean is wired, so spans would be produced "
                        + "and silently discarded. Observed beans: %s", described)
                .isNotEmpty();

        boolean hasOtlp = described.stream().anyMatch(d -> d.toLowerCase().contains("otlp"));
        boolean hasZipkin = described.stream().anyMatch(d -> d.toLowerCase().contains("zipkin"));

        System.out.printf("### otlp=%s zipkin=%s -> %s%n", hasOtlp, hasZipkin,
                hasOtlp && hasZipkin
                        ? "COEXIST: staged swap is safe (add Tempo, prove it, remove Zipkin separately)"
                        : "SINGLE: swap must be one atomic commit");

        // OTLP must be present either way — it is the exporter Tempo needs, and
        // this dependency was added specifically to provide it. Zipkin's
        // presence is informational: it is on its way out, and the assertion
        // deliberately does not require it so that this test keeps passing
        // after the Zipkin removal commit.
        assertThat(hasOtlp)
                .as("OTLP exporter must be wired once spring-boot-starter-opentelemetry "
                        + "is on the classpath; Tempo ingests OTLP. Observed: %s", described)
                .isTrue();
    }
}
