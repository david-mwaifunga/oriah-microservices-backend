package com.oriah.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.ControllerAdvice;

import com.oriah.catalog.exception.GlobalExceptionHandler;
import com.oriah.observability.OriahExceptionHandlerSupport;
import com.oriah.observability.ProblemDetailExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalog has exactly one advice carrying the Oriah error contract, and it is
 * the one this module is supposed to have.
 *
 * <p>Copy this file into each service as Tier 5 reaches it, changing only the
 * package and {@link #EXPECTED_ADVICE}. That constant is the whole point: it
 * records, per module, <em>which</em> advice is meant to own error rendering —
 * the shared default for a service with no domain exceptions of its own, or the
 * service's {@code exception.GlobalExceptionHandler} once it has some.
 *
 * <h2>Why it is not a count of {@code @ControllerAdvice} beans</h2>
 *
 * <p>That was the first version, and it failed here for a reason unrelated to
 * what it was guarding. {@code springdoc-openapi-starter-common} contributes
 * {@code SpringDocConfiguration$OpenApiResourceAdvice}, a member class
 * annotated {@code @RestControllerAdvice} with its own
 * {@code @ExceptionHandler}. So "exactly one advice" is already false in all 12
 * services and cannot be made true — a count assertion would produce twelve
 * tests failing for the wrong reason.
 *
 * <p>The same discovery was a live bug, not just a bad test: the starter's
 * back-off condition was keyed on the {@code @ControllerAdvice} annotation, so
 * springdoc's bean satisfied it and the shared error contract would have
 * registered nowhere. The condition now keys on
 * {@link OriahExceptionHandlerSupport}, and the invariant worth asserting is
 * the same one: <strong>exactly one advice extending that class</strong>,
 * because that is the set where a duplicate makes the winner depend on
 * bean-name ordering.
 *
 * <p>The starter's own {@code SharedAdviceRegistrationTest} covers the
 * conditional in an {@code ApplicationContextRunner}, where advices are
 * registered as explicit user configurations. This runs in catalog's
 * <em>real</em> context, where they arrive by component scanning and
 * auto-configuration — which is the thing that has to work, and is not the
 * thing the starter test exercises.
 */
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@SpringBootTest
@TestPropertySource(properties = {
        // Same properties as CatalogServiceApplicationTests, so this shares its
        // cached context rather than booting a second one.
        "eureka.client.enabled=false"
})
class ErrorAdviceRegistrationTest {

    /**
     * Catalog throws only the shared {@code ResourceNotFoundException}, whose
     * 404 mapping lives in {@link OriahExceptionHandlerSupport}, so it has
     * nothing of its own to add and keeps the shared default advice. If catalog
     * later gains a domain exception, this becomes
     * {@code com.oriah.catalog.exception.GlobalExceptionHandler} and the shared
     * default must then be absent — which the second test checks.
     */
    /**
     * Catalog now owns its advice, which is the second of the two cases this
     * class's javadoc describes. It carries two handlers the shared default
     * cannot: DataIntegrityViolationException (whose type lives in spring-tx,
     * a dependency the observability starter deliberately avoids so the
     * non-JPA services stay free of spring-data) and
     * AttributeValidationException (catalog-specific by nature).
     *
     * <p>Changing this constant is the point of the test: the shared starter
     * backs off when a service declares its own OriahExceptionHandlerSupport
     * subclass, and this assertion is what proves the back-off happened
     * rather than leaving two advices whose winner depends on bean-name
     * ordering.
     */
    private static final Class<?> EXPECTED_ADVICE = GlobalExceptionHandler.class;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("exactly one advice carries the error contract, and it is the expected one")
    void exactlyOneErrorContractAdvice() {
        String[] contractAdvices = context.getBeanNamesForType(OriahExceptionHandlerSupport.class);

        assertThat(contractAdvices)
                .as("two advices extending OriahExceptionHandlerSupport would both claim "
                        + "the inherited @ExceptionHandler(Exception.class), and neither is "
                        + "@Ordered, so which one wins would come down to bean-name ordering")
                .hasSize(1);

        assertThat(context.getBean(contractAdvices[0]))
                .as("this module's error rendering must come from %s",
                        EXPECTED_ADVICE.getSimpleName())
                .isInstanceOf(EXPECTED_ADVICE);
    }

    @Test
    @DisplayName("the error contract is actually present, not silently absent")
    void theErrorContractIsPresentAtAll() {
        // The assertion that would have caught the regression where springdoc's
        // advice satisfied the back-off condition. An empty result here means
        // every framework exception falls through to
        // DefaultHandlerExceptionResolver -- bare status, empty body, no
        // traceId -- and every domain exception to Boot's default error page,
        // silently undoing Tier 0.5 in this service.
        assertThat(context.getBeanNamesForType(OriahExceptionHandlerSupport.class))
                .as("no advice extending OriahExceptionHandlerSupport means this service "
                        + "has NO error contract at all, and nothing else would say so")
                .isNotEmpty();
    }

    @Test
    @DisplayName("springdoc's own advice coexists and is left alone")
    void springdocAdviceCoexists() {
        // Documented rather than treated as a problem, so the next person to
        // reach for a "one advice" assertion sees why the count is not 1.
        // springdoc's advice handles its own exception type only; it never
        // competes for Exception.class.
        assertThat(context.getBeanNamesForAnnotation(ControllerAdvice.class))
                .as("the error-contract advice plus springdoc's OpenApiResourceAdvice -- "
                        + "two advices, one error contract")
                .hasSizeGreaterThanOrEqualTo(2);
    }
}
