package com.oriah.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import feign.RequestInterceptor;

/**
 * Sends the caller's own token on payment's call to order, so order answers
 * as that caller.
 *
 * <p>This is load-bearing for authorization, not just for getting a 200.
 * Order's {@code GET /api/v1/orders/{id}} answers 404 for an order that is not
 * the caller's. Because the call carries the caller's own token, that answer
 * becomes payment's ownership check: an order the caller does not own is
 * indistinguishable from one that does not exist, and both refuse the intent.
 *
 * <p>The same pattern as order's forwarder of the same name. It reads the
 * token from {@code SecurityContextHolder}, which belongs to the request
 * thread -- which is why Feign's circuit breaker stays off in
 * application.yaml: it would run the call on another thread and the token
 * would be lost.
 */
// Deliberately NOT @Configuration: a component-scanned configuration makes its
// interceptor apply to every Feign client. It is attached to OrderClient only
// (@FeignClient(configuration = ...)), in that client's own Feign context.
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
