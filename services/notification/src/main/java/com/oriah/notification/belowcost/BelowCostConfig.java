package com.oriah.notification.belowcost;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import com.oriah.notification.alerts.StaffAlerts;
import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.NotificationSettings;

/**
 * The below-cost check, put together: notification's own sign-in at Keycloak (client credentials: the service signs in as itself, with its
 * client's id and secret, not on behalf of a person), the poller, and its schedule.
 */
@Configuration(proxyBeanMethods = false)
public class BelowCostConfig {

    /** The registration notification signs in with ({@code spring.security.oauth2.client.registration.oriah-notification}). */
    static final String REGISTRATION = "oriah-notification";
    /** Who the token is kept under in memory: one token for the whole service, reused until it expires. */
    static final String PRINCIPAL = "notification-service";
    /** A token request never holds the poller longer than this. */
    public static final Duration TOKEN_CONNECT_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration TOKEN_READ_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Fetches and keeps notification's own token. Service-scoped (not request-scoped: a scheduled job has no request), with timeouts of its
     * own, and on java.net.http, which keeps the body of a 401 so that a refused secret reads as "refused" and not as "no answer".
     */
    @Bean
    OAuth2AuthorizedClientManager machineClients(ClientRegistrationRepository registrations, OAuth2AuthorizedClientService authorizedClients) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TOKEN_CONNECT_TIMEOUT).build());
        requests.setReadTimeout(TOKEN_READ_TIMEOUT);
        @SuppressWarnings("deprecation")
        RestClient tokenEndpoint = RestClient.builder()
                .requestFactory(requests)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        RestClientClientCredentialsTokenResponseClient tokens = new RestClientClientCredentialsTokenResponseClient();
        tokens.setRestClient(tokenEndpoint);
        ClientCredentialsOAuth2AuthorizedClientProvider clientCredentials = new ClientCredentialsOAuth2AuthorizedClientProvider();
        clientCredentials.setAccessTokenResponseClient(tokens);
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(clientCredentials);
        return manager;
    }

    @Bean
    BelowCostPoller belowCostPoller(CatalogBelowCostClient catalog, JdbcTemplate jdbc, PlatformTransactionManager transactions, StaffAlerts alerts,
                                    EmailSetup setup, NotificationSettings settings, OAuth2AuthorizedClientService authorizedClients,
                                    @Value("${spring.security.oauth2.client.registration.oriah-notification.client-secret:}") String secret) {
        return new BelowCostPoller(catalog, new BelowCostMemory(jdbc, transactions, alerts, setup, settings), alerts, jdbc, settings, secret,
                () -> authorizedClients.removeAuthorizedClient(REGISTRATION, PRINCIPAL));
    }

    /**
     * Runs the check on its schedule, on a scheduling thread of its own. It exists only while {@code oriah.notification.below-cost.enabled}
     * is true, which it is as shipped; the test configuration turns it off and calls the poller itself.
     */
    @Component
    @ConditionalOnProperty(name = "oriah.notification.below-cost.enabled", havingValue = "true")
    public static class BelowCostSchedule {

        private final BelowCostPoller poller;
        private final Clock clock;

        public BelowCostSchedule(BelowCostPoller poller, Clock clock) {
            this.poller = poller;
            this.clock = clock;
        }

        @Scheduled(fixedDelayString = "${oriah.notification.below-cost.delay}", initialDelayString = "${oriah.notification.below-cost.initial-delay}")
        void tick() {
            poller.tick(clock.instant());
        }
    }
}
