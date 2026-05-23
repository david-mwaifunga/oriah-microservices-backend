package com.oriah.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Cross-cutting auto-configuration for the Oriah observability stack.
 *
 * <p>Listed in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * so it loads automatically the moment a Spring Boot module declares a
 * dependency on this jar.
 *
 * <p>Imports:
 * <ul>
 *   <li>{@link ProblemDetailExceptionHandler} -- a global
 *       {@code @RestControllerAdvice} returning RFC 7807 problem
 *       details for the four common domain exception categories,
 *       echoing the active traceId so a 500 is one paste away from
 *       its Zipkin trace.</li>
 * </ul>
 *
 * <p>Micrometer Tracing's own auto-configuration (triggered by
 * {@code micrometer-tracing-bridge-otel} on the classpath, with
 * Spring Boot 4's {@code spring-boot-zipkin} + {@code
 * spring-boot-micrometer-tracing-opentelemetry} carrying the
 * autoconfig classes) wires the {@code Tracer}, the OTel Zipkin
 * exporter, and the HTTP/Feign/JDBC/Kafka instrumentation by itself
 * -- no extra beans needed here.
 */
@AutoConfiguration
@Configuration
@Import(ProblemDetailExceptionHandler.class)
public class ObservabilityAutoConfiguration {
}
