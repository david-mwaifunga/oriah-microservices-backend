package com.oriah.observability;

import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the MDC key names Micrometer Tracing's OpenTelemetry bridge binds
 * while a span is current.
 *
 * <p><strong>Why this test exists.</strong> Our {@code logback-spring.xml}
 * renders every event as one-line JSON with a bare {@code <mdc/>} provider,
 * which copies MDC entries into the JSON object <em>under their MDC key
 * names verbatim</em>. So whatever this test observes is literally the JSON
 * field name a Loki query and a Grafana {@code derivedFields}
 * {@code matcherRegex} have to match on. Guessing wrong produces a trace
 * link that silently never resolves — the failure mode is a dead "View
 * trace" button, not an error, which is exactly the kind of thing that
 * survives a refactor unnoticed.
 *
 * <p>Boot 3 + Brave and Boot 4 + the OTel bridge have historically differed
 * between {@code traceId}/{@code spanId} and {@code trace_id}/{@code
 * span_id} (the course's log pattern uses the snake_case pair via
 * {@code %X{trace_id}}). Rather than carry that assumption into the Grafana
 * datasource config, we measure it here and let the assertion message
 * report the truth.
 */
@SpringBootTest(
        classes = MdcTraceCorrelationKeyTest.ProbeApp.class,
        properties = {
                "spring.application.name=mdc-probe",
                "spring.main.banner-mode=off",
                // Sample everything so nextSpan() is never a no-op span,
                // which would leave MDC empty and make this test lie.
                "management.tracing.sampling.probability=1.0",
                // The Zipkin exporter is on the classpath; point it at a
                // closed port so its background batch sender fails fast
                // instead of waiting on the real compose endpoint.
                "management.tracing.export.zipkin.endpoint=http://127.0.0.1:1/api/v2/spans"
        })
class MdcTraceCorrelationKeyTest {

    /**
     * Minimal host app. The starter is a library with no application class
     * of its own, but the tracing auto-configurations need a real context
     * to bind the {@link Tracer} and the MDC scope decorator.
     */
    @SpringBootApplication
    static class ProbeApp {
    }

    @Autowired
    private Tracer tracer;

    @Test
    @DisplayName("a current span puts traceId + spanId into MDC under camelCase keys")
    void currentSpanPopulatesMdcCorrelationKeys() {
        Map<String, String> captured = new LinkedHashMap<>();

        Span span = tracer.nextSpan().name("mdc-probe");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
            Map<String, String> live = MDC.getCopyOfContextMap();
            if (live != null) {
                captured.putAll(live);
            }
        } finally {
            span.end();
        }

        // Surfaces the real key set in the surefire output even on success,
        // so the Grafana regex can be derived from evidence rather than memory.
        System.out.println("### MDC while span current: " + captured);

        assertThat(captured)
                .as("MDC bound by the Micrometer OTel bridge while a span is "
                        + "current. These exact keys become JSON field names via "
                        + "logback's <mdc/> provider, and therefore the Grafana "
                        + "derivedFields matcherRegex. Observed: %s", captured)
                .containsKeys("traceId", "spanId");

        assertThat(captured.get("traceId"))
                .as("traceId must be the 32-hex W3C trace id, since the Loki "
                        + "derived field feeds it straight to Tempo as a trace lookup")
                .matches("[0-9a-f]{32}");
        assertThat(captured.get("spanId"))
                .as("spanId must be 16-hex")
                .matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("MDC carries no trace keys outside a span scope")
    void noSpanMeansNoCorrelationKeys() {
        // Guards the inverse: if MDC leaked trace ids after the scope closed,
        // log lines from pooled threads would be attributed to stale traces —
        // the same class of bug that made checkout's Feign calls lose their
        // SecurityContext across a thread hop.
        Map<String, String> outside = MDC.getCopyOfContextMap();

        assertThat(outside == null ? Map.<String, String>of() : outside)
                .doesNotContainKeys("traceId", "spanId");
    }
}
