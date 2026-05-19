package com.oriah.checkout.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import feign.RequestInterceptor;

/**
 * Forwards the caller's JWT to every downstream Feign call so the
 * orchestrator acts on behalf of the user (not anonymously). Without
 * this, downstream services see no Authorization header and reject
 * with 401.
 */
@Configuration
public class FeignTokenForwarder {

    @Bean
    public RequestInterceptor jwtForwardingInterceptor() {
        return template -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                Jwt jwt = jwtAuth.getToken();
                template.header("Authorization", "Bearer " + jwt.getTokenValue());
            }
        };
    }
}
