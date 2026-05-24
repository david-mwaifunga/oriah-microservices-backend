package com.oriah.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class KeycloakAuditorAwareTest {

    private final KeycloakAuditorAware auditor = new KeycloakAuditorAware();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyContextReturnsSystemSentinel() {
        SecurityContextHolder.clearContext();
        assertThat(auditor.getCurrentAuditor()).contains("system");
    }

    @Test
    void anonymousAuthenticationReturnsSystem() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(auditor.getCurrentAuditor()).contains("system");
    }

    @Test
    void jwtAuthenticationReturnsTheSubClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("c7a049f7-5af2-4129-b163-7f3b8408a03e")
                .claim("preferred_username", "alice")
                .build();
        // Two-arg ctor (jwt, authorities) marks the token authenticated,
        // matching what the resource-server filter chain produces.
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(auditor.getCurrentAuditor()).contains("c7a049f7-5af2-4129-b163-7f3b8408a03e");
    }

    @Test
    void nonJwtAuthenticationFallsBackToName() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("maya", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertThat(auditor.getCurrentAuditor()).contains("maya");
    }
}
