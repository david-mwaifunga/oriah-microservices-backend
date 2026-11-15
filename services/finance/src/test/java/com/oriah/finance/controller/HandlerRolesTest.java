package com.oriah.finance.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.oriah.finance.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The books are for the owner and the bookkeeper only. The gateway and the security filter both stop everyone else at {@code /api/v1/admin/finance/**}, and every
 * handler states the rule itself as well, so a handler added tomorrow under another path, or a filter rule loosened by mistake, can never leave a figure open.
 * This walks EVERY handler the service serves and fails if one under the finance prefix does not require FINANCE_ADMIN (on the method or its class), and if
 * anything is served outside it that is not documentation or a health check.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class HandlerRolesTest {

    private static final String PREFIX = "/api/v1/admin/finance";

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("every handler under the finance prefix requires FINANCE_ADMIN, and nothing else is served by the application's own controllers")
    void everyHandlerNamesItsRole() {
        List<String> openOrOutside = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = e.getValue();
            String owner = handler.getBeanType().getName();
            if (!owner.startsWith("com.oriah.finance.")) {
                continue;   // springdoc's and the platform's own endpoints are permitted by name in the security filter
            }
            var patterns = e.getKey().getPathPatternsCondition() == null ? java.util.Set.<String>of()
                    : e.getKey().getPathPatternsCondition().getPatternValues();
            PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (rule == null) {
                rule = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            }
            boolean ok = rule != null && rule.value().contains("FINANCE_ADMIN") && patterns.stream().allMatch(p -> p.startsWith(PREFIX));
            if (!ok) {
                openOrOutside.add(handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName() + " " + patterns);
            }
            checked++;
        }

        assertThat(checked).as("the controllers were found").isGreaterThan(0);
        assertThat(openOrOutside).as("a handler without FINANCE_ADMIN, or outside the finance prefix").isEmpty();
    }
}
