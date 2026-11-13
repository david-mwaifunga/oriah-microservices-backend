package com.oriah.shipping.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/shipping/contact-info} — the course's
 * {@code /contact-info} convention, which exists so an operator looking at a
 * misbehaving service can find out who owns it without going to a wiki.
 *
 * <p>Runs with the Config Server disabled, which is the whole point: the
 * endpoint must answer from shipping's own {@code application.yaml} fallback
 * when config-repo is unreachable. Two measured facts from a spike sit behind
 * that design, and this test is where they are pinned:
 *
 * <ul>
 *   <li>a plain {@code @ConfigurationProperties} class binds <strong>null</strong>
 *       for a missing prefix and the context starts happily — so without a
 *       fallback this endpoint would serve
 *       {@code {"message":null,"contactDetails":null,"onCallSupport":null}}:
 *       a 200 that lies;</li>
 *   <li>adding {@code @Validated} turns that into a
 *       {@code BindValidationException} that <strong>fails the context</strong>
 *       — the Tier 4 {@code base-config} hard-link mistake repeated, where
 *       centralizing a value converted an optional dependency into a required
 *       one.</li>
 * </ul>
 *
 * <p>The fallback plus validation gives neither failure mode: values are
 * always present, and a typo that empties them is caught at startup rather
 * than served.
 *
 * <p>Authenticated, unlike the course's, which is open. Not a considered
 * improvement so much as the consequence of {@code ShippingController} being
 * annotated {@code @PreAuthorize("hasRole('USER')")} at class level — a new
 * handler inherits it. Recorded as a divergence rather than worked around,
 * because making one method public means a method-level override here plus a
 * matching exception at the gateway, and the gateway's rules are Tier
 * 3-verified. The contact details are a name, an email and two phone numbers,
 * so there is no argument for them being reachable anonymously.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(com.oriah.shipping.TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // The scenario under test: no Config Server, so nothing supplies
        // oriah.shipping.contact-info.* from config-repo/shipping.yaml.
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ContactInfoTest {

    @Autowired
    private MockMvc mvc;

    /**
     * The OAuth2 resource server fetches JWKS from issuer-uri at startup, and
     * there is no Keycloak here. Same pattern as catalog's controller tests.
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("answers with every field populated, with no Config Server")
    void servesContactInfoFromTheLocalFallback() {
        // If the fallback block were missing, the context would not even start
        // (@Validated + absent prefix), so reaching this assertion is itself
        // half the proof.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                mvc.perform(get("/api/v1/shipping/contact-info")
                                .with(jwt().authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                                "ROLE_USER"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.message").isNotEmpty())
                        .andExpect(jsonPath("$.contactDetails").isMap())
                        .andExpect(jsonPath("$.contactDetails.name").isNotEmpty())
                        .andExpect(jsonPath("$.contactDetails.email").isNotEmpty())
                        .andExpect(jsonPath("$.onCallSupport").isArray())
                        .andExpect(jsonPath("$.onCallSupport[0]").isNotEmpty()));
    }

    @Test
    @DisplayName("requires a token, like every other shipping endpoint")
    void contactInfoRequiresAToken() throws Exception {
        // Pins the divergence from the course deliberately. If someone later
        // decides this should be public, this test is the place that says the
        // gateway needs a matching exception.
        mvc.perform(get("/api/v1/shipping/contact-info"))
                .andExpect(status().isUnauthorized());
    }
}
