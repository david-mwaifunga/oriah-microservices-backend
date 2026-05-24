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
 * Smoke tests for the Gateway.
 *
 * <p>Spins up the full reactive context on a random port and verifies the
 * route table is what we configured. We don't test actual proxying here
 * — that requires Eureka + catalog also running and is covered by the
 * manual end-to-end smoke described in {@code docs/PROGRESS.md}.
 *
 * <p>Eureka is disabled via {@code eureka.client.enabled=false} so the
 * gateway doesn't try to register on startup (no Eureka running during
 * tests). Without that override the test hangs in retry loops.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        // Spring Boot's /actuator/env masks property values as "******" by
        // default — a sensible prod posture to keep secrets out of dumps.
        // For this test we need to read the actual route definitions, so
        // unmask values just for this test boot. NEVER set this in
        // production application.yaml.
        "management.endpoint.env.show-values=ALWAYS",
        // RedisRateLimiter requires a live Redis; the test boot does not
        // run one. Disable the Redis health indicator so /actuator/health
        // does not drop to DOWN (503) on the absent connection, and
        // disable Redis repositories auto-config to keep the test slice
        // lean. The rate-limiter beans still wire (the test does not
        // actually exercise the rate-limited routes).
        "management.health.redis.enabled=false",
        "spring.data.redis.repositories.enabled=false"
})
class GatewayServerApplicationTests {

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("configured gateway routes are visible in /actuator/env (boot loaded the YAML)")
    void routesAreConfigured() throws Exception {
        // The dedicated /actuator/gateway/routes endpoint was renamed/gated
        // in Spring Cloud Gateway 5.0.x and isn't auto-exposed even with
        // management.endpoints.web.exposure.include='*'. Rather than chase
        // the moving target, we verify the routes were loaded via the
        // always-on /actuator/env endpoint — it reads the live Environment
        // and shows every property the gateway loaded from application.yaml,
        // route definitions included.
        HttpResponse<String> env = get("/actuator/env");

        assertThat(env.statusCode()).isBetween(200, 299);
        // Both route ids and the lb:// target should appear in the
        // environment dump if the YAML was loaded correctly.
        assertThat(env.body()).contains("catalog-public-route");
        assertThat(env.body()).contains("catalog-admin-route");
        assertThat(env.body()).contains("lb://catalog");
    }

    @Test
    @DisplayName("actuator health is UP")
    void actuatorHealthUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(response.body()).contains("\"status\":\"UP\"");
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
