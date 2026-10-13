package com.oriah.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import feign.RequestInterceptor;

/**
 * Forwards the caller's JWT to payment-service so the intent read happens as
 * the shopper.
 *
 * <p>This is load-bearing for authorization, not just for getting a 200.
 * Payment's {@code GET /intents/{id}} is owner-scoped and answers 404 for an
 * intent belonging to someone else. Because this interceptor makes the call
 * carry the shopper's own token, that scoping becomes order-service's ownership
 * check: an intent the shopper does not own is indistinguishable from one that
 * does not exist, and both refuse the payment. Calling payment with a service
 * identity instead would hand us every customer's intents and oblige us to
 * re-implement the ownership rule here.
 *
 * <p>Mirrors checkout's forwarder of the same name. Both depend on the call
 * running on the request thread, which is why the circuit breaker stays
 * disabled -- see {@link com.oriah.order.client.PaymentClient}.
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
