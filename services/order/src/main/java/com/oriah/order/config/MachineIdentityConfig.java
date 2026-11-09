package com.oriah.order.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * How order obtains its own machine token (MVP_PLAN Phase 0, item 4.2):
 * client credentials, against the {@code oriah-admin} registration.
 *
 * <p>A service-scoped manager ({@link AuthorizedClientServiceOAuth2AuthorizedClientManager}),
 * not the request-scoped one: the token is the shop's, fetched once and reused
 * for every shopper until it expires -- keyed by a fixed principal, so the
 * store holds one token, not one per shopper.
 *
 * <p>Its token request has short timeouts (1s connect, 3s read): it sits on the
 * placement path inside checkout's 15s budget, and the library default would
 * wait far longer on a Keycloak that is up but not answering.
 */
@Configuration
public class MachineIdentityConfig {

    /** How long the token request may take to connect. Public so WritePathBudgetTest reads it, not a copy. */
    public static final Duration TOKEN_CONNECT_TIMEOUT = Duration.ofSeconds(1);
    /** How long the token request may take to answer. */
    public static final Duration TOKEN_READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public OAuth2AuthorizedClientManager machineClients(ClientRegistrationRepository registrations,
                                                        OAuth2AuthorizedClientService authorizedClients) {
        // java.net.http, not HttpURLConnection: the latter drops the body of a
        // 401, so Keycloak's "invalid/unauthorized client" refusal arrived as a
        // body-less "invalid_token_response" and a misconfigured secret read as
        // an outage (OutboundIdentityTest.aWrongClientSecretIsOurFault).
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TOKEN_CONNECT_TIMEOUT).build());
        requests.setReadTimeout(TOKEN_READ_TIMEOUT);

        // Built as Spring Security builds its default one, plus the timeouts.
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
        ClientCredentialsOAuth2AuthorizedClientProvider clientCredentials =
                new ClientCredentialsOAuth2AuthorizedClientProvider();
        clientCredentials.setAccessTokenResponseClient(tokens);

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(clientCredentials);
        return manager;
    }
}