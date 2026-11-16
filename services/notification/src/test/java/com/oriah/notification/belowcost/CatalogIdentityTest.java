package com.oriah.notification.belowcost;

import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.oriah.notification.MutableClock;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.StandIns;
import com.oriah.notification.TestData;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notification calls catalog as ITSELF: it signs in at Keycloak with its own client's id and secret (client credentials), keeps the token, and
 * puts it on the one request it makes. WireMock stands in for Keycloak and for catalog; the service is the real one, wired as it ships.
 */
@ServiceTest
class CatalogIdentityTest {

    private static final WireMockServer keycloak = StandIns.KEYCLOAK;
    private static final WireMockServer catalog = StandIns.CATALOG;

    @Autowired
    private BelowCostPoller poller;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
        // No token left over from another test: each starts by signing in.
        context.getBean(org.springframework.security.oauth2.client.OAuth2AuthorizedClientService.class)
                .removeAuthorizedClient("oriah-notification", "notification-service");
        keycloak.resetAll();
        catalog.resetAll();
        keycloak.stubFor(post(urlPathEqualTo("/realms/oriah/protocol/openid-connect/token")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"notifications-own-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        catalog.stubFor(get(urlPathEqualTo("/api/v1/admin/pricing/below-cost")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"at":"2026-11-09T07:31:00Z","items":[{"variantId":"22222222-2222-4222-8222-222222222222","productId":"11111111-1111-4111-8111-111111111111",
                          "productTitle":"Aurelia Solitaire Ring","variantTitle":"Aurelia Solitaire Ring - Ring Size M","sku":"RING-M","productStatus":"ACTIVE",
                          "price":1500.00,"currency":"ZMW","discountPerPiece":0.00,"netPrice":1500.00,"assessment":"BELOW","floor":1607.98,"shortfall":107.98,
                          "goodsHighest":1528.99,"boxHighest":78.99,"boxBasis":"COUNTED","estimated":false}],
                         "counts":{"OK":3,"BELOW":1,"NO_COST_KNOWN":0,"CURRENCY_MISMATCH":0},"page":0,"size":100,"totalItems":1}""")));
    }

    @Test
    @DisplayName("it signs in with its own client's id and secret, asks catalog for the pieces below cost with that token, and keeps no figure")
    void signsInAsItsOwnClientWithClientCredentials() {
        poller.tick(clock.instant());

        keycloak.verify(1, postRequestedFor(urlPathEqualTo("/realms/oriah/protocol/openid-connect/token"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withHeader("Authorization", equalTo("Basic " + Base64.getEncoder().encodeToString("oriah-notification:test-secret".getBytes()))));
        catalog.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/admin/pricing/below-cost"))
                .withQueryParam("status", equalTo("BELOW")).withQueryParam("page", equalTo("0")).withQueryParam("size", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer notifications-own-token")));

        assertThat(jdbc.queryForObject("SELECT summary FROM staff_alert WHERE kind = 'BELOW_COST'", String.class))
                .isEqualTo("Aurelia Solitaire Ring - Ring Size M (SKU RING-M) is priced below cost");
        String everythingKept = jdbc.queryForList("SELECT summary || ' ' || detail FROM staff_alert", String.class) + " "
                + jdbc.queryForList("SELECT subject || ' ' || body FROM notification_log", String.class);
        assertThat(everythingKept).as("no price, floor, shortfall or lot cost").doesNotContain("1500").doesNotContain("1607").doesNotContain("107.98")
                .doesNotContain("1528").doesNotContain("78.99");
    }

    @Test
    @DisplayName("the token is fetched once and reused; when catalog refuses it, it is dropped and fetched again on the next round")
    void theTokenIsReusedAndDroppedWhenRefused() {
        poller.tick(clock.instant());
        poller.tick(clock.instant());
        keycloak.verify(1, postRequestedFor(urlPathEqualTo("/realms/oriah/protocol/openid-connect/token")));

        catalog.stubFor(get(urlPathEqualTo("/api/v1/admin/pricing/below-cost")).willReturn(aResponse().withStatus(401)));
        poller.tick(clock.instant());
        assertThat(jdbc.queryForObject("SELECT last_failure FROM below_cost_poll", String.class)).isEqualTo("REFUSED");

        poller.tick(clock.instant());
        keycloak.verify(2, postRequestedFor(urlPathEqualTo("/realms/oriah/protocol/openid-connect/token")));
    }

    @Test
    @DisplayName("Keycloak refusing the secret, and catalog answering 503, are both 'unknown': nothing opens, and the reason is kept")
    void failuresAreUnknown() {
        keycloak.stubFor(post(urlPathEqualTo("/realms/oriah/protocol/openid-connect/token")).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_client\"}")));
        context.getBean(org.springframework.security.oauth2.client.OAuth2AuthorizedClientService.class)
                .removeAuthorizedClient("oriah-notification", "notification-service");
        poller.tick(clock.instant());
        assertThat(jdbc.queryForObject("SELECT last_failure FROM below_cost_poll", String.class)).isEqualTo("TOKEN");
        catalog.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/admin/pricing/below-cost")));

        keycloak.stubFor(post(urlPathEqualTo("/realms/oriah/protocol/openid-connect/token")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"notifications-own-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        catalog.stubFor(get(urlPathEqualTo("/api/v1/admin/pricing/below-cost")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "5")));
        poller.tick(clock.instant());
        assertThat(jdbc.queryForObject("SELECT last_failure FROM below_cost_poll", String.class)).isEqualTo("UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert", Long.class)).isZero();
    }

    @Test
    @DisplayName("catalog is the only service notification calls: there is exactly one Feign client, and no code path to a phone")
    void theOnlyFeignClientIsCatalog() {
        assertThat(context.getBeansWithAnnotation(FeignClient.class).values()).singleElement()
                .satisfies(client -> assertThat(client).isInstanceOf(CatalogBelowCostClient.class));
    }
}
