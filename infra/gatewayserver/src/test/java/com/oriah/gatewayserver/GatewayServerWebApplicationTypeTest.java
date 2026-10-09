package com.oriah.gatewayserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.DispatcherHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one classpath hazard that would break this module silently
 * rather than loudly: the gateway must stay a <strong>reactive</strong>
 * application.
 *
 * <p>{@code WebApplicationType.deduceFromClasspath()} returns {@code REACTIVE}
 * only while {@code DispatcherHandler} is present <em>and</em>
 * {@code org.springframework.web.servlet.DispatcherServlet} is absent. If
 * anything ever drags {@code spring-webmvc} onto this module — most plausibly
 * through a convenience dependency added to the shared
 * {@code oriah-observability-starter}, which all 15 modules consume — Spring
 * Boot would start a servlet container instead, and Spring Cloud Gateway's
 * entire reactive filter chain would never be wired. The app would boot
 * "successfully" and route nothing.
 *
 * <p>This matters right now because the starter deliberately depends on
 * {@code jakarta.servlet-api} (needed to compile the framework-exception
 * handlers in {@code ProblemDetailExceptionHandler}, whose exception types
 * extend {@code ServletException}). The servlet <em>API</em> alone is
 * harmless; {@code spring-webmvc} is not. This test is what makes that
 * distinction enforceable rather than a comment someone has to remember.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "management.health.redis.enabled=false",
        "spring.data.redis.repositories.enabled=false"
})
class GatewayServerWebApplicationTypeTest {

    private static final String WEBMVC_INDICATOR = "org.springframework.web.servlet.DispatcherServlet";

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("spring-webmvc is absent, so Boot still deduces a reactive app")
    void springWebMvcIsNotOnTheClasspath() {
        assertThat(ClassUtils.isPresent(WEBMVC_INDICATOR, getClass().getClassLoader()))
                .as("%s on the gateway classpath flips WebApplicationType to SERVLET "
                        + "and silently disables the Spring Cloud Gateway filter chain", WEBMVC_INDICATOR)
                .isFalse();

        assertThat(ClassUtils.isPresent(DispatcherHandler.class.getName(), getClass().getClassLoader()))
                .as("the reactive indicator class must be present")
                .isTrue();
    }

    @Test
    @DisplayName("the running context is a reactive web context")
    void contextIsReactive() {
        // Belt and braces: assert the deduction's *outcome*, not just its
        // inputs, so the guard holds even if Boot changes how it decides.
        assertThat(context)
                .as("gateway context type")
                .isInstanceOf(org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext.class);
    }
}
