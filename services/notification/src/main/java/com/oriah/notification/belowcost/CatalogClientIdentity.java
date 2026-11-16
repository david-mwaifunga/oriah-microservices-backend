package com.oriah.notification.belowcost;

import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import feign.RequestInterceptor;

/**
 * Makes the one Feign client that calls catalog do so as notification itself: it puts notification's own token on every request.
 *
 * <p>Deliberately NOT a {@code @Configuration}: named on the client ({@code @FeignClient(configuration = ...)}) it applies to that client only.
 * A component-scanned configuration would put this machine token on every Feign client the service ever gets.
 *
 * <p>A token that cannot be had is a {@link BelowCostPoller.TokenUnavailable}: the poller counts the round as failed, and nothing is sent to
 * catalog without one.
 */
public class CatalogClientIdentity {

    @Bean
    public RequestInterceptor notificationsOwnToken(OAuth2AuthorizedClientManager machineClients) {
        return template -> {
            OAuth2AuthorizedClient client;
            try {
                client = machineClients.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(BelowCostConfig.REGISTRATION)
                        .principal(BelowCostConfig.PRINCIPAL).build());
            } catch (RuntimeException failure) {
                // The kind of failure only: Keycloak's own words stay out of the log line the poller writes.
                throw new BelowCostPoller.TokenUnavailable(failure.getClass().getSimpleName());
            }
            if (client == null || client.getAccessToken() == null) {
                throw new BelowCostPoller.TokenUnavailable("no token");
            }
            // Replace, never append: one caller, one identity.
            template.removeHeader("Authorization");
            template.header("Authorization", "Bearer " + client.getAccessToken().getTokenValue());
        };
    }
}
