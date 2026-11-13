package com.oriah.catalog.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.exception.CostContractException;
import com.oriah.catalog.exception.CostReadRefusedException;
import com.oriah.catalog.exception.CostUnavailableException;
import com.oriah.catalog.pricing.HeldCost;
import com.oriah.catalog.service.CostLookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Catalog asks inventory what the stock it still holds cost, on behalf of the person pricing a piece: with THEIR token (so
 * inventory itself decides whether they may read cost), on the calling thread, and with every way it can go wrong told
 * apart. The real client against a real HTTP server standing in for inventory, because what matters here is what goes over the
 * wire and what comes back, and a mock would prove neither.
 *
 * <p>The failure that must never happen is silence: an answer that is missing, or a call that could not be made, read as "no
 * cost known" would let a price below cost through unseen. So there is no fallback and no empty answer: a lookup either
 * answers every variant it was asked about or throws.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "oriah.test.stub-inventory=false",
        "spring.cloud.openfeign.client.config.inventory-costs.read-timeout=400"
})
class InventoryCostClientTest {

    /** One request the stand-in inventory saw. */
    private record Seen(String authorization, List<String> ids) {
    }

    private static final List<Seen> SEEN = new CopyOnWriteArrayList<>();
    private static final HttpServer SERVER = start();

    /** Ids whose first hex digit decides what the stand-in does, so one server can play every outcome. */
    private static final String REFUSED = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
    private static final String BROKEN = "ffffffff-ffff-4fff-8fff-ffffffffffff";
    private static final String SLOW = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    private static final String OMITTED = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final String TOO_MANY = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String NOT_FOUND = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/admin/costs/held", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                String raw = query == null ? "" : URLDecoder.decode(query.replace("variantIds=", ""), StandardCharsets.UTF_8);
                List<String> ids = raw.isEmpty() ? List.of() : List.of(raw.split(","));
                SEEN.add(new Seen(exchange.getRequestHeaders().getFirst("Authorization"), ids));

                int status = 200;
                String body;
                if (ids.contains(TOO_MANY)) {
                    status = 429;
                    body = "{\"status\":429}";
                } else if (ids.contains(NOT_FOUND)) {
                    status = 404;
                    body = "{\"status\":404}";
                } else if (ids.contains(REFUSED)) {
                    status = 403;
                    body = "{\"type\":\"https://oriah.dev/problems/forbidden\",\"status\":403}";
                } else if (ids.contains(BROKEN)) {
                    status = 500;
                    body = "{\"type\":\"https://oriah.dev/problems/internal-server-error\",\"status\":500}";
                } else {
                    if (ids.contains(SLOW)) {
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    body = "{\"variants\":[" + ids.stream().filter(id -> !id.equals(OMITTED)).map(InventoryCostClientTest::answer)
                            .collect(Collectors.joining(",")) + "]}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "application/problem+json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            // One thread per request: the slow answer must not hold up the others, or their calls would time out behind it.
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String answer(String id) {
        return "{\"variantId\":\"" + id + "\",\"onHand\":3,\"heldUnits\":3,\"highestUnitCost\":1528.99,\"averageUnitCost\":1528.99,"
                + "\"currency\":\"ZMW\",\"estimated\":false,\"lots\":[{\"lotId\":\"" + UUID.randomUUID() + "\",\"unitCost\":1528.99,"
                + "\"qtyRemaining\":3,\"estimated\":false,\"revision\":1,\"receivedAt\":\"2026-11-09T08:00:00Z\","
                + "\"consignmentReference\":\"C-000045\"}]}";
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
    private CostLookup costs;

    @Autowired
    private FeignClientFactory feignClientFactory;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void forgetWhatWasSeen() {
        SEEN.clear();
    }

    private static UUID variant(int n) {
        return new UUID(0x1111_1111_1111_4111L, 0x8111_0000_0000_0000L + n);
    }

    @Test
    @DisplayName("the caller's own token goes to inventory, and the answer is read: the lots, the dearest cost, the currency")
    void theTokenIsRelayedAndTheAnswerRead() {
        UUID id = variant(1);

        Map<UUID, HeldCost> answer = costs.heldCosts(List.of(id), "Bearer the-owners-token");

        assertThat(SEEN).hasSize(1);
        assertThat(SEEN.get(0).authorization()).isEqualTo("Bearer the-owners-token");
        assertThat(SEEN.get(0).ids()).containsExactly(id.toString());
        HeldCost cost = answer.get(id);
        assertThat(cost.highestUnitCost()).isEqualByComparingTo("1528.99");
        assertThat(cost.currency()).isEqualTo("ZMW");
        assertThat(cost.lots()).hasSize(1);
        assertThat(cost.lots().get(0).qtyRemaining()).isEqualTo(3);
        assertThat(cost.lots().get(0).consignmentReference()).isEqualTo("C-000045");
    }

    @Test
    @DisplayName("more than 100 variants are asked about in calls of at most 100, and every one is answered")
    void manyIdsAreChunked() {
        List<UUID> ids = IntStream.range(0, 250).mapToObj(InventoryCostClientTest::variant).toList();

        Map<UUID, HeldCost> answer = costs.heldCosts(ids, "Bearer t");

        assertThat(SEEN).hasSize(3);
        assertThat(SEEN).allSatisfy(call -> assertThat(call.ids().size()).isLessThanOrEqualTo(100));
        assertThat(SEEN.stream().mapToInt(call -> call.ids().size()).sum()).isEqualTo(250);
        assertThat(answer).hasSize(250).containsKeys(ids.toArray(UUID[]::new));
    }

    @Test
    @DisplayName("a variant named twice is asked about once")
    void duplicatesAreAskedOnce() {
        UUID id = variant(7);

        Map<UUID, HeldCost> answer = costs.heldCosts(List.of(id, id, id), "Bearer t");

        assertThat(SEEN).hasSize(1);
        assertThat(SEEN.get(0).ids()).containsExactly(id.toString());
        assertThat(answer).hasSize(1);
    }

    @Test
    @DisplayName("nothing to ask about is no call at all")
    void nothingToAsk() {
        assertThat(costs.heldCosts(List.of(), "Bearer t")).isEmpty();
        assertThat(SEEN).isEmpty();
    }

    @Test
    @DisplayName("an answer that leaves a variant out is an error, never 'no cost known': the one thing that would let a price through unseen. It is a 500 of its own, not a conflict")
    void aMissingAnswerIsAnError() {
        UUID omitted = UUID.fromString(OMITTED);

        assertThatThrownBy(() -> costs.heldCosts(List.of(variant(2), omitted), "Bearer t"))
                .isInstanceOf(CostContractException.class)
                .hasMessageContaining(omitted.toString())
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Test
    @DisplayName("an answer catalog does not expect (inventory 404 after a version skew) is the same 500 contract fault, and a busy inventory (429) is a 503 to try again")
    void otherStatuses() {
        assertThatThrownBy(() -> costs.heldCosts(List.of(UUID.fromString(NOT_FOUND)), "Bearer t")).isInstanceOf(CostContractException.class);
        assertThatThrownBy(() -> costs.heldCosts(List.of(UUID.fromString(TOO_MANY)), "Bearer t")).isInstanceOf(CostUnavailableException.class);
    }

    @Test
    @DisplayName("inventory refusing the token (401 or 403) is a 502 that says so: not 'try again', and never passed on to the browser as a sign-in problem")
    void aRefusalIsABadGateway() {
        assertThatThrownBy(() -> costs.heldCosts(List.of(UUID.fromString(REFUSED)), "Bearer t"))
                .isInstanceOf(CostReadRefusedException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse());
    }

    @Test
    @DisplayName("inventory failing (500) is a 503 with Retry-After: nothing was compared, so nothing is saved")
    void aServerErrorIsUnavailable() {
        assertThatThrownBy(() -> costs.heldCosts(List.of(UUID.fromString(BROKEN)), "Bearer t"))
                .isInstanceOf(CostUnavailableException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank());
    }

    @Test
    @DisplayName("inventory too slow to answer is the same 503, after the client's own timeout")
    void aSlowAnswerIsUnavailable() {
        assertThatThrownBy(() -> costs.heldCosts(List.of(UUID.fromString(SLOW)), "Bearer t"))
                .isInstanceOf(CostUnavailableException.class);
    }

    @Test
    @DisplayName("this client is NOT wrapped in Spring's circuit breaker, which would move the call to another thread and drop the caller's context")
    void noThreadHoppingBreaker() {
        Feign.Builder builder = feignClientFactory.getInstance("inventory-costs", Feign.Builder.class);

        assertThat(builder.getClass().getName())
                .as("a circuit-breaker builder dispatches each call on a pool thread (and applies a time limiter there)")
                .doesNotContain("CircuitBreaker");
    }

    @Test
    @DisplayName("a lookup without a token is refused before anything is sent")
    void aTokenIsRequired() {
        assertThatThrownBy(() -> costs.heldCosts(new ArrayList<>(List.of(variant(3))), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SEEN).isEmpty();
    }
}
