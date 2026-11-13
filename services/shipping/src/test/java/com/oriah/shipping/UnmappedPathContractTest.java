package com.oriah.shipping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * What a service answers for a path it does not map.
 *
 * <p>Written because it came up by accident: while {@code /contact-info} was
 * still unimplemented, an authenticated request to it came back
 * <strong>500</strong> rather than 404. That is the Tier 0.5 defect shape — a
 * caller's mistake reported as a server fault — and it has a specific cost
 * here beyond the misleading status line: <strong>5xx is retry-eligible.</strong>
 * The gateway's {@code retry} filter and Tier 4's Resilience4j retry both act
 * on {@code SERVER_ERROR} only, so a typo'd URL would be re-sent three times
 * with backoff, and every one would log an ERROR with a stack trace.
 *
 * <p>So this pins the status rather than leaving it to be rediscovered the next
 * time somebody notices a 500 in the logs. Lives in shipping because it has the
 * smallest context in the repo and the question is about the shared error
 * contract, not about shipping.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(com.oriah.shipping.TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class UnmappedPathContractTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("an unmapped path under a mapped prefix is not reported as a server fault")
    void unmappedPathIsNotA5xx() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/shipping/does-not-exist")
                        .with(jwt().authorities(ROLE_USER)))
                .andReturn();

        int status = result.getResponse().getStatus();

        assertThat(status)
                .as("a path this service does not map is the CALLER's mistake. Reporting "
                        + "it as 5xx makes it retry-eligible at the gateway and in "
                        + "Resilience4j, so one typo becomes three requests with backoff "
                        + "plus three ERROR-level stack traces. Got %s", status)
                .isLessThan(500);
    }

    @Test
    @DisplayName("an unmapped path at the application root is not reported as a server fault")
    void unmappedRootPathIsNotA5xx() throws Exception {
        // Checked separately: a path under /api/v1/shipping/** could in
        // principle be handled differently from one that matches no controller
        // prefix at all, and the gateway routes plenty of both.
        MvcResult result = mvc.perform(get("/nope")
                        .with(jwt().authorities(ROLE_USER)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("same reasoning as the mapped-prefix case")
                .isLessThan(500);
    }
}
