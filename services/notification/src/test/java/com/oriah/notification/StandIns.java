package com.oriah.notification;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * The two services notification talks to, as stand-ins inside the test JVM: Keycloak's token endpoint and catalog. They are started once and
 * wired into the ONE shared test context ({@link ServiceTest}), because a second Spring context with the stream test binder breaks the first
 * one's error channel. A test that uses them resets them first; nothing calls them unless a test makes the below-cost poller bean tick.
 */
public final class StandIns {

    public static final WireMockServer KEYCLOAK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    public static final WireMockServer CATALOG = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    public static final String CLIENT_SECRET = "test-secret";

    static {
        KEYCLOAK.start();
        CATALOG.start();
    }

    private StandIns() {
    }

    /** Points the service's own sign-in and its one Feign client at the stand-ins. */
    public static final class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "spring.security.oauth2.client.provider.keycloak.token-uri=" + KEYCLOAK.baseUrl() + "/realms/oriah/protocol/openid-connect/token",
                    "spring.security.oauth2.client.registration.oriah-notification.client-secret=" + CLIENT_SECRET,
                    "spring.cloud.openfeign.client.config.catalogBelowCost.url=" + CATALOG.baseUrl()).applyTo(context);
        }
    }
}
