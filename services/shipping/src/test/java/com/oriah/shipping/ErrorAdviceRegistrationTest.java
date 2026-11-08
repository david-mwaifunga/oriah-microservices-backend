package com.oriah.shipping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.ControllerAdvice;

import com.oriah.observability.OriahExceptionHandlerSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipping has exactly one advice carrying the Oriah error contract, and it is
 * shipping's own.
 *
 * <p>The per-service guard described in the Tier 5 checklist. Until Phase 0
 * item 1 shipping threw nothing and kept the shared default advice. It now
 * refuses a destination outside Zambia with a 422 of its own problem type, so
 * it declares {@code exception/GlobalExceptionHandler}, extending the shared
 * support; the starter's default must then back off, leaving exactly one.
 *
 * <p>Asserts on beans assignable to {@link OriahExceptionHandlerSupport} rather
 * than counting {@code @ControllerAdvice} beans. The count is already ≥2 in
 * every web module because {@code springdoc-openapi-starter-common} contributes
 * {@code SpringDocConfiguration$OpenApiResourceAdvice}; keying the starter's
 * back-off condition on that annotation was a real regression that would have
 * removed the error contract from every module without one of its own.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ErrorAdviceRegistrationTest {

    /** Shipping's own advice: the shared contract plus the 422 for outside Zambia. */
    private static final Class<?> EXPECTED_ADVICE = com.oriah.shipping.exception.GlobalExceptionHandler.class;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the error contract is present, not silently absent")
    void theErrorContractIsPresentAtAll() {
        assertThat(context.getBeanNamesForType(OriahExceptionHandlerSupport.class))
                .as("no advice extending OriahExceptionHandlerSupport means shipping has "
                        + "NO error contract: every framework exception falls through to "
                        + "DefaultHandlerExceptionResolver with a bare status, empty body "
                        + "and no traceId, and nothing else would say so")
                .isNotEmpty();
    }

    @Test
    @DisplayName("exactly one advice carries it, and it is the expected one")
    void exactlyOneErrorContractAdvice() {
        String[] contractAdvices = context.getBeanNamesForType(OriahExceptionHandlerSupport.class);

        assertThat(contractAdvices).hasSize(1);
        assertThat(context.getBean(contractAdvices[0])).isInstanceOf(EXPECTED_ADVICE);
    }

    @Test
    @DisplayName("springdoc's own advice coexists")
    void springdocAdviceCoexists() {
        assertThat(context.getBeanNamesForAnnotation(ControllerAdvice.class))
                .hasSizeGreaterThanOrEqualTo(2);
    }
}
