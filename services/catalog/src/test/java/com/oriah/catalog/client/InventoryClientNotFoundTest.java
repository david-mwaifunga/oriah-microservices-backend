package com.oriah.catalog.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.catalog.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real Feign client against a real HTTP server, to prove the one thing a mock cannot: an answer of 404 ("inventory
 * has no stock row for this variant", which since Phase 5 is the normal state of anything not yet received) reaches
 * catalog as an ANSWER and does not count as a failure of the call.
 *
 * <p>Counted as a failure it would open the circuit breaker after a handful of such product views (a window of ten
 * calls, half of them failing) and every product page would lose its stock badge for 30 seconds.
 *
 * <p>The server stands in for inventory through the discovery client's static list (Eureka is off in tests).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "oriah.test.stub-inventory=false"
})
class InventoryClientNotFoundTest {

    private static final HttpServer SERVER = start();

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/inventory/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String variant = path.substring(path.lastIndexOf('/') + 1);
                int status;
                String body;
                if (variant.startsWith("00000000-0000-0000-0000-0000000000")) {
                    // The shape inventory answers for a variant it has no row for (problem+json).
                    status = 404;
                    body = "{\"type\":\"https://oriah.dev/problems/not-found\",\"title\":\"Not Found\",\"status\":404,"
                            + "\"detail\":\"No Inventory with variantId '" + variant + "'\"}";
                    exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
                } else if (variant.startsWith("ffffffff")) {
                    status = 500;
                    body = "{\"type\":\"https://oriah.dev/problems/internal\",\"status\":500}";
                    exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
                } else {
                    status = 200;
                    body = "{\"variantId\":\"" + variant + "\",\"onHand\":7,\"reserved\":2,\"available\":5}";
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void inventoryIsThisServer(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.inventory[0].uri",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired
    private InventoryClient inventory;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static UUID missing(int n) {
        return new UUID(0L, n + 1L);
    }

    private static UUID present() {
        return UUID.fromString("11111111-1111-1111-1111-111111111111");
    }

    private static UUID broken() {
        return UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    }

    @Test
    @DisplayName("a variant inventory has no row for is answered as 404, with no stock figure")
    void notFoundIsAnAnswer() {
        ResponseEntity<InventoryDto> answer = inventory.getByVariantId(missing(1));

        assertThat(answer).as("an answer, not the fallback's null").isNotNull();
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a variant with stock is read as it is")
    void aRowIsRead() {
        ResponseEntity<InventoryDto> answer = inventory.getByVariantId(present());

        assertThat(answer).isNotNull();
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answer.getBody()).isNotNull();
        assertThat(answer.getBody().available()).isEqualTo(5);
    }

    @Test
    @DisplayName("any number of 404s never open the breaker: the next variant with stock still gets its real answer")
    void notFoundIsNotAFailureOfTheCall() {
        for (int i = 0; i < 40; i++) {
            assertThat(inventory.getByVariantId(missing(i))).isNotNull();
        }

        ResponseEntity<InventoryDto> answer = inventory.getByVariantId(present());

        assertThat(answer).as("the breaker is still closed: the fallback's null would mean it opened").isNotNull();
        assertThat(answer.getBody().available()).isEqualTo(5);
    }

    @Test
    @DisplayName("a real failure still degrades: inventory's 500 is the fallback's null, which the page reads as UNKNOWN")
    void aServerErrorStillFallsBack() {
        assertThat(inventory.getByVariantId(broken())).isNull();
    }
}
