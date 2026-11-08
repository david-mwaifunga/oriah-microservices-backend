package com.oriah.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import feign.RequestInterceptor;

/**
 * ShippingClient's own Feign configuration: order's machine token.
 *
 * <p>Deliberately NOT {@code @Configuration}. Feign builds a child context per
 * client and reads this class only for {@code @FeignClient(name = "shipping")};
 * a component-scanned configuration would make the interceptor global and
 * send the machine token to payment as well.
 */
public class ShippingClientIdentity {

    @Bean
    public RequestInterceptor serviceAccountToken(OAuth2AuthorizedClientManager machineClients) {
        return new ServiceAccountTokenInterceptor(machineClients);
    }
}