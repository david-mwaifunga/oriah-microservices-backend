package com.oriah.catalog.controller;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;

import com.oriah.catalog.TestcontainersConfiguration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/products/contact-info} -- the course's {@code /contact-info}
 * convention, for the catalog service.
 *
 * <p>Runs with the Config Server <strong>disabled</strong>, which is the whole
 * point: the endpoint must answer from catalog's own {@code application.yaml}
 * fallback when config-repo is unreachable. Two measured facts sit behind that
 * design:
 *
 * <ul>
 *   <li>plain {@code @ConfigurationProperties} binds <strong>null</strong> for
 *       a missing prefix and the context starts happily -- so without a
 *       fallback this endpoint would serve three nulls: a 200 that lies;</li>
 *   <li>{@code @Validated} turns that into a
 *       {@code BindValidationException} that <strong>fails the context</strong>
 *       -- the Tier 4 {@code base-config} hard-link mistake repeated, where
 *       centralizing a value converted an optional dependency into a required
 *       one.</li>
 * </ul>
 *
 * <p>The fallback plus validation gives neither failure mode, and because the
 * context would not start without the fallback, reaching any assertion below is
 * itself half the proof.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ContactInfoTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("answers with every field populated, with no Config Server")
    void servesContactInfoFromTheLocalFallback() throws Exception {
        mvc.perform(get("/api/v1/products/contact-info").with(authenticated()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.contactDetails").isMap())
                .andExpect(jsonPath("$.contactDetails.name").isNotEmpty())
                .andExpect(jsonPath("$.contactDetails.email").isNotEmpty())
                .andExpect(jsonPath("$.onCallSupport").isArray())
                .andExpect(jsonPath("$.onCallSupport[0]").isNotEmpty());
    }

    @Test
    @DisplayName("requires a token, even where this service's reads are public")
    void contactInfoRequiresAToken() throws Exception {
        // Deliberately diverges from the course, whose version is open. The
        // body carries on-call phone numbers, so it should not be readable
        // anonymously from the public edge. For the services that permit
        // anonymous GETs on this prefix (catalog, search, inventory) that took
        // an explicit authenticated() rule declared BEFORE the permitAll --
        // Spring Security matches in declaration order. This assertion is what
        // keeps that rule from being reordered away.
        mvc.perform(get("/api/v1/products/contact-info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/contact-info is not swallowed by the /{id} mapping")
    void contactInfoIsNotMatchedAsAProductId() throws Exception {
        // A real ambiguity, worth pinning rather than trusting. ProductController
        // maps GET /api/v1/products/{id} with a UUID path variable, and the new
        // literal /api/v1/products/contact-info matches that pattern too.
        // Spring's PathPattern comparator prefers the pattern with fewer URI
        // variables, so the literal wins -- but if it did not, this request
        // would try to parse "contact-info" as a UUID and answer 400, and the
        // endpoint would simply never work.
        mvc.perform(get("/api/v1/products/contact-info").with(authenticated()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    private static RequestPostProcessor authenticated() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
