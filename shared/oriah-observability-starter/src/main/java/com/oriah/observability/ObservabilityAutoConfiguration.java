package com.oriah.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cross-cutting auto-configuration for the Oriah observability stack.
 *
 * <p>Listed in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * so it loads automatically the moment a Spring Boot module declares a
 * dependency on this jar.
 *
 * <p>Contributes exactly one thing: the default RFC 7807 error advice, and
 * only when the consuming module has none of its own. See
 * {@link #problemDetailExceptionHandler()}.
 *
 * <p>Micrometer Tracing's own auto-configuration (triggered by
 * {@code micrometer-tracing-bridge-otel} on the classpath, with Spring Boot
 * 4's {@code spring-boot-starter-opentelemetry} carrying the autoconfig
 * classes) wires the {@code Tracer}, the OTLP exporter, and the
 * HTTP/Feign/JDBC/Kafka instrumentation by itself — no extra beans needed
 * here.
 */
@AutoConfiguration
@Configuration
public class ObservabilityAutoConfiguration {

    /**
     * The default error advice, registered only when the module supplies no
     * {@code @ControllerAdvice} of its own.
     *
     * <p>This was an unconditional {@code @Import}, which was correct while
     * this starter owned the only advice in the system. Tier 5 gives each of
     * the 12 services a
     * {@code com.oriah.<service>.exception.GlobalExceptionHandler} extending
     * {@link OriahExceptionHandlerSupport}, and an unconditional import would
     * then put <em>two</em> advices in every service context — both claiming
     * {@code @ExceptionHandler(Exception.class)}, one declared and one
     * inherited. Spring does not reject that; it sorts advices with
     * {@code OrderComparator}, and with neither {@code @Order}ed both sit at
     * {@code LOWEST_PRECEDENCE}, so which one claims a given exception comes
     * down to bean-name ordering. Error semantics would depend on class
     * naming.
     *
     * <p>Keyed on the {@link OriahExceptionHandlerSupport} <strong>supertype,
     * not</strong> on the {@code @ControllerAdvice} annotation. The annotation
     * was the first attempt, on the reading "this context already has an
     * advice". It was wrong, and measurably so:
     *
     * <p>{@code springdoc-openapi-starter-common} ships
     * {@code SpringDocConfiguration$OpenApiResourceAdvice} — a member class
     * annotated {@code @RestControllerAdvice} with its own
     * {@code @ExceptionHandler} (verified with {@code javap} against the 3.0.2
     * jar on this classpath). {@code @RestControllerAdvice} is meta-annotated
     * {@code @Component}, so as a member class of an {@code @Configuration} it
     * registers as a bean, and springdoc is on all 12 services and the
     * gateway. Under the annotation-keyed condition that bean satisfied "an
     * advice exists", so the shared error contract would have registered
     * <strong>nowhere</strong> — and since both are contributed by
     * auto-configuration, whether the condition even saw it depended on
     * auto-config ordering. A silently-absent error advice, nondeterministically.
     *
     * <p>An advice that cannot render our problem bodies is not a substitute
     * for one that can, so the supertype is the honest key. The real invariant
     * is therefore <em>not</em> "exactly one {@code @ControllerAdvice}" — that
     * is already false in every service and cannot be made true — but
     * <strong>"exactly one advice carrying the Oriah error contract, and it is
     * the expected one"</strong>. That is what the per-service guard tests
     * assert.
     *
     * <p>{@code @ConditionalOnMissingBean} is only reliable inside
     * auto-configuration, which this is: component-scanned bean definitions
     * are registered before auto-configurations are processed, so a service's
     * own advice is already visible when this condition evaluates. Keying on a
     * type this starter owns also removes the ordering question entirely —
     * nothing else on the classpath can extend it.
     */
    @Bean
    @ConditionalOnMissingBean(OriahExceptionHandlerSupport.class)
    public ProblemDetailExceptionHandler problemDetailExceptionHandler() {
        return new ProblemDetailExceptionHandler();
    }
}
