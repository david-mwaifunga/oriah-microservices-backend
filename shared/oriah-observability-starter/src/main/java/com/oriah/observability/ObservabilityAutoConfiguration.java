package com.oriah.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Marker auto-configuration for the Oriah observability stack.
 *
 * <p>Listed in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * so it loads automatically the moment a Spring Boot module declares a
 * dependency on this jar. There are no beans here yet — Micrometer Tracing's
 * own auto-configuration (triggered by {@code micrometer-tracing-bridge-otel}
 * on the classpath) wires the {@code Tracer}, the OTel Zipkin exporter, and
 * the HTTP/Feign/JDBC/Kafka instrumentation by itself.
 *
 * <p>The class exists as a stable hook for future cross-cutting beans
 * (custom resource attributes, common health indicators, sampler overrides)
 * that should ride along with the dependency and not require each consumer
 * to re-wire anything.
 */
@AutoConfiguration
@Configuration
public class ObservabilityAutoConfiguration {
}
