package com.oriah.order.config;

import org.springframework.context.annotation.Bean;
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
 * <p>The same holds for {@link com.oriah.order.client.CustomerClient}: customer
 * lists the addresses of whoever the token belongs to, so forwarding the
 * shopper's token is what makes "is this address yours?" a question about
 * this shopper.
 *
 * <p>Mirrors checkout's forwarder of the same name. Both depend on the call
 * running on the request thread, which is why the circuit breaker stays
 * disabled -- see {@link com.oriah.order.client.PaymentClient}.
 */
// Deliberately NOT @Configuration: a component-scanned configuration makes its
// interceptor GLOBAL, and it then reached catalog and shipping too. It is
// attached to PaymentClient and CustomerClient only
// (@FeignClient(configuration = ...)), each in its own Feign child context.
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
