package com.oriah.order.config;

import java.util.Set;

import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.HttpClientErrorException;

import com.oriah.order.exception.UpstreamRejectedException;
import com.oriah.order.exception.UpstreamUnavailableException;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Puts order's own client-credentials token on a request -- replacing, never
 * appending to, any Authorization header already there.
 *
 * <p>Failures are translated, because the library's own interceptor would
 * answer an unobtainable token with an IllegalStateException the shared advice
 * renders as 409 -- a conflict the shopper did not cause:
 * <ul>
 *   <li>Keycloak refuses order's credentials (invalid_client and friends) ->
 *       {@link UpstreamRejectedException}, 500: a misconfiguration, which a
 *       503 would invite every caller to retry for ever;</li>
 *   <li>anything else -- Keycloak down, slow, or no token -> 503.</li>
 * </ul>
 */
public class ServiceAccountTokenInterceptor implements RequestInterceptor {

    static final String REGISTRATION = "oriah-admin";

    /** A fixed principal: the token is the shop's, one for every shopper. */
    static final String PRINCIPAL = "order-service";

    private static final Set<String> OUR_MISCONFIGURATION =
            Set.of("invalid_client", "unauthorized_client", "invalid_grant", "invalid_scope");

    private final OAuth2AuthorizedClientManager machineClients;

    public ServiceAccountTokenInterceptor(OAuth2AuthorizedClientManager machineClients) {
        this.machineClients = machineClients;
    }

    /**
     * Keycloak ANSWERED with a 4xx: it refused order's own request -- a wrong
     * secret, an unknown client, a grant the client may not use. Judged by the
     * status on the cause chain rather than by the parsed OAuth2 error code,
     * because the error body is not always parsed into one (the refusal then
     * arrives as a generic invalid_token_response).
     */
    private static boolean keycloakRefused(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof HttpClientErrorException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    @Override
    public void apply(RequestTemplate template) {
        OAuth2AuthorizedClient client;
        try {
            client = machineClients.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(REGISTRATION)
                    .principal(PRINCIPAL)
                    .build());
        } catch (OAuth2AuthorizationException e) {
            if (OUR_MISCONFIGURATION.contains(e.getError().getErrorCode()) || keycloakRefused(e)) {
                throw new UpstreamRejectedException("Keycloak refused order's client credentials: "
                        + e.getError().getErrorCode());
            }
            throw new UpstreamUnavailableException("keycloak");
        } catch (RuntimeException e) {
            throw new UpstreamUnavailableException("keycloak");
        }
        if (client == null || client.getAccessToken() == null) {
            throw new UpstreamUnavailableException("keycloak");
        }
        template.removeHeader("Authorization");
        template.header("Authorization", "Bearer " + client.getAccessToken().getTokenValue());
    }
}