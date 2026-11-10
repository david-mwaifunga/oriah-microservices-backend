package com.oriah.procurement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.ControllerAdvice;

import com.oriah.observability.OriahExceptionHandlerSupport;
import com.oriah.procurement.exception.GlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Procurement has exactly one advice carrying the Oriah error contract, and it
 * is the one this module is supposed to have.
 *
 * <p>The per-service guard, identical in every module apart from the package and
 * {@link #EXPECTED_ADVICE}. That constant records, per module, <em>which</em>
 * advice owns error rendering -- the shared default for a service with no domain
 * exceptions of its own, or the service's {@code exception.GlobalExceptionHandler}
 * once it has some.
 *
 * <p>Asserts on beans assignable to {@link OriahExceptionHandlerSupport} rather
 * than counting {@code @ControllerAdvice} beans: that count is already &gt;= 2 in
 * every web module, because springdoc contributes its own
 * {@code OpenApiResourceAdvice}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ErrorAdviceRegistrationTest {

    /**
     * The service's own advice, because it has a rule the shared default does not:
     * one specific database failure (two live suppliers with the same name) is a
     * 409, and every other integrity failure stays a 500. The shared starter backs
     * off when a service declares its own.
     */
    private static final Class<?> EXPECTED_ADVICE = GlobalExceptionHandler.class;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the error contract is present, not silently absent")
    void theErrorContractIsPresentAtAll() {
        assertThat(context.getBeanNamesForType(OriahExceptionHandlerSupport.class))
                .as("no advice extending OriahExceptionHandlerSupport means this service has "
                        + "NO error contract: every framework exception falls through to "
                        + "DefaultHandlerExceptionResolver with a bare status, empty body and "
                        + "no traceId, and nothing else would say so")
                .isNotEmpty();
    }

    @Test
    @DisplayName("exactly one advice carries it, and it is the expected one")
    void exactlyOneErrorContractAdvice() {
        String[] contractAdvices = context.getBeanNamesForType(OriahExceptionHandlerSupport.class);

        assertThat(contractAdvices)
                .as("two advices extending OriahExceptionHandlerSupport would both claim the "
                        + "inherited @ExceptionHandler(Exception.class), and neither is "
                        + "@Ordered, so the winner would come down to bean-name ordering")
                .hasSize(1);
        assertThat(context.getBean(contractAdvices[0])).isInstanceOf(EXPECTED_ADVICE);
    }

    @Test
    @DisplayName("springdoc's own advice coexists and is left alone")
    void springdocAdviceCoexists() {
        assertThat(context.getBeanNamesForAnnotation(ControllerAdvice.class))
                .as("the error-contract advice plus springdoc's OpenApiResourceAdvice -- "
                        + "two advices, one error contract")
                .hasSizeGreaterThanOrEqualTo(2);
    }
}
