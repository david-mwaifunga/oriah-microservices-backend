package com.oriah.finance.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.finance.TestcontainersConfiguration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/admin/finance/contact-info} -- who owns the finance
 * service, for staff.
 *
 * <p>Runs with the Config Server <strong>disabled</strong>, which is the whole
 * point: the endpoint must answer from finance's own {@code application.yaml}
 * fallback when config-repo is unreachable. Plain {@code @ConfigurationProperties}
 * would bind <strong>null</strong> for a missing prefix and serve a 200 that
 * lies; {@code @Validated} would fail the context instead. The fallback plus
 * validation gives neither failure mode.
 *
 * <h2>Why the path, and why the role</h2>
 *
 * <p>The gateway routes only {@code /api/v1/admin/finance/**} to this
 * service, so the endpoint sits under that prefix to be reachable at the edge at
 * all (inventory's twin sits outside any routed prefix and is unreachable there).
 * It is a staff area, so the gateway already requires the FINANCE_ADMIN
 * capability for the path -- but the gateway is a layer, not the only one: every
 * service validates tokens itself, because services call each other directly and
 * the ports are only closed in production. So the controller carries the same
 * rule, and the "plain USER gets 403" test below talks to the service, not the
 * gateway.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ContactInfoTest {

    private static final String PATH = "/api/v1/admin/finance/contact-info";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the owner's bookkeeper (FINANCE_ADMIN) gets every field populated, with no Config Server")
    void servesContactInfoFromTheLocalFallback() throws Exception {
        mvc.perform(get(PATH).with(role("FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.contactDetails").isMap())
                .andExpect(jsonPath("$.contactDetails.name").isNotEmpty())
                .andExpect(jsonPath("$.contactDetails.email").isNotEmpty())
                .andExpect(jsonPath("$.onCallSupport").isArray())
                .andExpect(jsonPath("$.onCallSupport[0]").isNotEmpty());
    }

    @Test
    @DisplayName("requires a token: the body carries on-call phone numbers")
    void contactInfoRequiresAToken() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a plain shopper (USER) is refused with 403, by the service itself")
    void aPlainUserIsForbidden() throws Exception {
        mvc.perform(get(PATH).with(role("USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    @DisplayName("another capability is not enough, and the 403 does not name the role it wanted")
    void anotherCapabilityIsForbidden() throws Exception {
        mvc.perform(get(PATH).with(role("INVENTORY_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("FINANCE_ADMIN"))));
    }

    private static RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
