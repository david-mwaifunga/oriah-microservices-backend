package com.oriah.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.ControllerAdvice;

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
     * <p>Keyed on the {@code @ControllerAdvice} <strong>annotation</strong>
     * rather than on the {@link OriahExceptionHandlerSupport} supertype. Both
     * would catch the Tier 5 services, but the annotation also catches an
     * advice that does not extend ours — which is the right reading of the
     * condition ("this context already has an advice") and is measured, not
     * assumed: {@code getBeanNamesForAnnotation} resolves
     * {@code @RestControllerAdvice} through its {@code @ControllerAdvice}
     * meta-annotation. {@code SharedAdviceRegistrationTest} covers both
     * shapes.
     *
     * <p>{@code @ConditionalOnMissingBean} is only reliable inside
     * auto-configuration, which this is: component-scanned bean definitions
     * are registered before auto-configurations are processed, so a service's
     * own advice is already visible when this condition evaluates.
     *
     * <p>One sharp edge, recorded rather than guarded here: a module that adds
     * an advice for some unrelated purpose silently takes over error
     * rendering, because this starter then contributes nothing. That is why
     * the per-service guard test asserts the advice bean is the
     * <em>expected</em> one, not merely that there is exactly one.
     */
    @Bean
    @ConditionalOnMissingBean(annotation = ControllerAdvice.class)
    public ProblemDetailExceptionHandler problemDetailExceptionHandler() {
        return new ProblemDetailExceptionHandler();
    }
}
