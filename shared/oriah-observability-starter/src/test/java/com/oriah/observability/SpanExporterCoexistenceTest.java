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
 * <p>The answer was BOTH, so the staged swap went ahead: OTLP was added while
 * Zipkin still ran, the smoke test proved the same traceId reached Tempo across
 * all seven saga services, and only then was Zipkin removed.
 *
 * <p>The test outlives that decision, because its central assertion is the one
 * that matters permanently: <strong>at least one SpanExporter must be
 * wired</strong>. It immediately caught the Zipkin removal accidentally taking
 * {@code spring-boot-starter-opentelemetry} with it (the two dependencies were
 * adjacent in the pom), reporting {@code SpanExporter beans: []}. Because the
 * image build is gated on the tests, that failure arrived before any of the 15
 * images were built; otherwise every service would have shipped producing spans
 * into nothing, with no error anywhere to say so.
 */
@SpringBootTest(
        classes = SpanExporterCoexistenceTest.ProbeApp.class,
        properties = {
                "spring.application.name=exporter-probe",
                "spring.main.banner-mode=off",
                "management.tracing.sampling.probability=1.0",
                // Pointed at a closed port: we are inspecting the bean graph, not
                // shipping spans, and this keeps the background sender from
                // retrying against the real stack.
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
                        ? "BOTH wired (the state during the staged swap)"
                        : "single backend wired (the state after the swap)");

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
