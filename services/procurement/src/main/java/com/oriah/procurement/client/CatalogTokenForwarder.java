package com.oriah.procurement.client;

import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import feign.RequestInterceptor;

/**
 * Sends the signed-in person's own JWT to catalog, so the lookup is made AS them.
 *
 * <p>Deliberately NOT a {@code @Configuration}: a component-scanned configuration makes
 * its interceptor global. It is attached to {@link CatalogClient} alone
 * ({@code @FeignClient(configuration = ...)}), each client in its own child context.
 *
 * <p>Relies on the call running on the request thread (the token comes from a
 * ThreadLocal), which is why no circuit breaker wraps the client: Spring Cloud's
 * Resilience4j integration would run the call on another thread and drop the header.
 */
public class CatalogTokenForwarder {

    @Bean
    public RequestInterceptor catalogJwtForwardingInterceptor() {
        return template -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                Jwt jwt = jwtAuth.getToken();
                template.header("Authorization", "Bearer " + jwt.getTokenValue());
            }
        };
    }
}
