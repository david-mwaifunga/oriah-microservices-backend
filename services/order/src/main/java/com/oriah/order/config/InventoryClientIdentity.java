package com.oriah.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import feign.RequestInterceptor;

/**
 * InventoryClient's own Feign configuration: order's machine token (the oriah-admin service account), as inventory's reservation endpoints
 * require. Deliberately NOT {@code @Configuration}, for the reason {@link ShippingClientIdentity} gives: Feign builds a child context per client
 * and reads this class only for {@code @FeignClient(name = "inventory")}, so the machine token is sent to inventory and nobody else.
 */
public class InventoryClientIdentity {

    @Bean
    public RequestInterceptor serviceAccountToken(OAuth2AuthorizedClientManager machineClients) {
        return new ServiceAccountTokenInterceptor(machineClients);
    }
}
