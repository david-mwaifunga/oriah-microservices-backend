package com.oriah.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import feign.RequestInterceptor;

/**
 * Forwards the caller's JWT to customer-service so the address list is read as
 * the shopper.
 *
 * <p>This is load-bearing for authorization, not just for getting a 200:
 * {@link com.oriah.order.client.CustomerClient} lists the addresses of whoever
 * the token belongs to, so forwarding the shopper's token is what makes "is
 * this address yours?" a question about THIS shopper. Calling customer with a
 * service identity instead would hand us every customer's addresses and oblige
 * us to re-implement the ownership rule here.
 *
 * <p>Mirrors checkout's forwarder of the same name. Both depend on the call
 * running on the request thread, which is why the circuit breaker stays
 * disabled: Spring Cloud's Resilience4j integration dispatches the call to
 * another thread, where the SecurityContext (a ThreadLocal) is empty.
 */
// Deliberately NOT @Configuration: a component-scanned configuration makes its
// interceptor GLOBAL, and it then reached catalog, shipping and inventory too.
// It is attached to CustomerClient only (@FeignClient(configuration = ...)),
// in its own Feign child context.
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
