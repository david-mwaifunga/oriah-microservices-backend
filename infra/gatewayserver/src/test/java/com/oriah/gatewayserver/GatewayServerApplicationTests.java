package com.oriah.gatewayserver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the Gateway over a real reactive server on a random port.
 *
 * <p>Route-table correctness itself lives in {@link RouteSnapshotTest}, which
 * inspects the resolved {@code RouteLocator} bean. This class covers the
 * actuator surface instead: that the real
 * {@code /actuator/gateway/routes} endpoint serves the table, and that health
 * is UP.
 *
 * <p><strong>What this replaced, and why.</strong> There used to be a
 * {@code routesAreConfigured} test here that scraped {@code /actuator/env} for
 * the strings {@code catalog-public-route} and {@code lb://catalog}, on the
 * reasoning that "the dedicated /actuator/gateway/routes endpoint was
 * renamed/gated in Spring Cloud Gateway 5.0.x ... rather than chase the moving
 * target, we verify the routes were loaded via the always-on /actuator/env".
 * Three things were wrong with that:
 *
 * <ol>
 *   <li>The endpoint was never renamed. The id is still {@code gateway}. What
 *       changed between 4.1.x and 5.0.x is the gating annotation —
 *       {@code enableByDefault = false} became
 *       {@code defaultAccess = Access.NONE} — and in Boot 4 <em>exposure</em>
 *       and <em>access</em> are separate gates, so
 *       {@code exposure.include: '*'} alone could never have reached it. It
 *       needs {@code management.endpoint.gateway.access} too, which is what
 *       this test now sets.</li>
 *   <li>Scraping {@code /actuator/env} tested that the routes were in the
 *       <em>Environment</em>, which was only ever a proxy for "the route table
 *       is right". Once the table moved from YAML into a {@code RouteLocator}
 *       bean the routes correctly vanished from the Environment and the test
 *       failed while nothing was broken.</li>
 *   <li>It required {@code management.endpoint.env.show-values=ALWAYS} to
 *       unmask values — a setting worth not normalising even in tests.</li>
 * </ol>
 *
 * <p>Eureka is disabled so the gateway does not try to register during tests
 * (nothing is listening, and without this the boot sits in retry loops).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        // The gateway actuator endpoint is deliberately NOT exposed in
        // application.yaml: its route table names every internal service and
        // path prefix, and the gateway's actuator is served on the public edge
        // port. Enabling it here, for this boot only, is what lets this test
        // assert on the real endpoint rather than on a proxy for it.
        //
        // BOTH properties are required. Exposure alone leaves the endpoint at
        // Access.NONE and it answers 404.
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus,gateway",
        "management.endpoint.gateway.access=read-only",
        // RedisRateLimiter needs a live Redis; the test boot has none. Disable
        // the health indicator so /actuator/health does not drop to DOWN, and
        // the Redis repositories auto-config to keep the slice lean. The
        // rate-limiter beans still wire — this test never drives a
        // rate-limited route.
        "management.health.redis.enabled=false",
        "spring.data.redis.repositories.enabled=false"
})
class GatewayServerApplicationTests {

    /** The 3 routes whose behaviour is not simply "prefix goes to service". */
    private static final String REVIEWS_ROUTE = "product-reviews-route";
    private static final String RATE_LIMITED_ROUTE = "catalog-public-route";
    private static final String DOCS_ROUTE = "catalog-docs";

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("/actuator/gateway/routes serves the whole table from the RouteLocator bean")
    void gatewayActuatorListsAllRoutes() throws Exception {
        HttpResponse<String> response = get("/actuator/gateway/routes");

        assertThat(response.statusCode())
                .as("the gateway actuator endpoint needs BOTH exposure and "
                        + "management.endpoint.gateway.access; a 404 here usually means only "
                        + "one of the two was set")
                .isBetween(200, 299);

        String body = response.body();

        // One representative of each route shape, rather than all 29 — the
        // exhaustive id/target/resolution assertions belong to
        // RouteSnapshotTest, which reads the bean directly and does not need a
        // server at all.
        assertThat(body)
                .as("the rate-limited storefront route")
                .contains(RATE_LIMITED_ROUTE);
        assertThat(body)
                .as("the order:-10 route that must out-rank catalog-public-route")
                .contains(REVIEWS_ROUTE);
        assertThat(body)
                .as("an OpenAPI aggregation route")
                .contains(DOCS_ROUTE);
        assertThat(body)
                .as("routes must still resolve through the load balancer, not a fixed host")
                .contains("lb://catalog");
    }

    @Test
    @DisplayName("actuator health is UP")
    void actuatorHealthUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("env, beans and configprops stay unreachable even when gateway is exposed")
    void sensitiveEndpointsAreNotExposed() throws Exception {
        // The curated exposure list is part of the contract, not an accident:
        // this actuator is served on the public edge port. Asserted here at
        // unit level as well as in smoke-test.ps1 step 18, because the smoke
        // test only runs against a live stack.
        for (String endpoint : new String[] {"env", "beans", "configprops", "mappings", "threaddump"}) {
            int status = get("/actuator/" + endpoint).statusCode();
            // Dividing by 100 asserts "not 2xx" without enumerating codes:
            // 404 (not exposed) and 401/403 (secured) are both acceptable
            // outcomes, a 2xx is not.
            assertThat(status / 100)
                    .as("/actuator/%s must not be served from the edge, got %d", endpoint, status)
                    .isNotEqualTo(2);
        }
    }

    /** Plain GET against the test server. */
    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
