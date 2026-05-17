package com.oriah.eurekaserver;

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
 * Smoke tests for the Eureka server.
 *
 * <p>Eureka registers its REST endpoints ({@code /eureka/*}) through
 * <b>Jersey</b>, not Spring MVC. MockMvc routes only through Spring's
 * {@code DispatcherServlet}, so it can't reach Jersey-handled paths and
 * the registry endpoints come back 404. We therefore use
 * {@link SpringBootTest.WebEnvironment#RANDOM_PORT} which starts a real
 * embedded server with both Spring's dispatcher and Jersey wired in,
 * and talk to it via Java's standard {@link HttpClient}.
 *
 * <p>Why not {@code TestRestTemplate}? Spring Boot 4 reorganized the
 * test utility packages and the artifact carrying {@code TestRestTemplate}
 * isn't on our minimal test classpath. {@code java.net.http.HttpClient}
 * is on the JDK and has no version-skew risk.
 *
 * <p>{@code server.port=0} (forced by {@code RANDOM_PORT}) keeps the test
 * from colliding with a developer-run Eureka on the well-known 8761.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false"
})
class EurekaServerApplicationTests {

    // Spring Boot's test framework sets local.server.port when
    // webEnvironment = RANDOM_PORT. We could use @LocalServerPort but its
    // package moved in SB 4 and @Value reads the same property with no
    // version-skew risk.
    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("Eureka dashboard at / responds with 2xx")
    void dashboardServes() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isBetween(200, 299);
    }

    @Test
    @DisplayName("Eureka /eureka/apps registry endpoint answers with the applications document")
    void registryEndpointAnswers() throws Exception {
        // Ask Eureka for the registry as JSON; default is XML.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/eureka/apps"))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isBetween(200, 299);
        // Standalone server with no clients registered yet — the response
        // body is a small JSON object with an "applications" root. We only
        // assert it's present; the precise shape is Netflix's contract.
        assertThat(response.body()).contains("applications");
    }

    @Test
    @DisplayName("Actuator health is UP")
    void actuatorHealthUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /** Convenience helper for plain GETs against the test server. */
    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
