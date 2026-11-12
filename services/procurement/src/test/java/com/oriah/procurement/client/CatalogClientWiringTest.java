package com.oriah.procurement.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The call procurement makes to catalog, over a real HTTP connection (no mock of the client):
 * who it says it is, how long it waits, and what a failure becomes.
 *
 * <ul>
 *   <li>catalog gets the SIGNED-IN PERSON's own token, once. The lookup is made as them, so
 *       there is no machine role to grant. A circuit breaker would run the call on another
 *       thread and drop the header, so this test is what keeps that switched off;</li>
 *   <li>a catalog that does not answer is given up on after about three seconds, not the
 *       library's sixty: the request holds a database connection while it waits;</li>
 *   <li>any failure (a refused token, an error, silence) is a 503 with {@code Retry-After}
 *       and nothing saved. No answer is ever guessed.</li>
 * </ul>
 *
 * <p>A real server on a free port stands in for catalog. A configured URL bypasses service
 * discovery for that client, so the test needs no Eureka.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogClientWiringTest {

    private static final String LOOKUP = "/api/v1/admin/variants/lookup";
    private static final UUID VARIANT = UUID.fromString("22222222-3333-4444-5555-666666666666");

    /** What the stand-in for catalog saw: one entry per request. */
    private record Seen(String method, String path, List<String> authorization, String contentType, String body) {
    }

    /** What the stand-in answers, changed per test. */
    private interface Answer {
        void to(HttpExchange exchange) throws IOException;
    }

    private static HttpServer server;
    private static final List<Seen> SEEN = new CopyOnWriteArrayList<>();
    private static final AtomicReference<Answer> ANSWER = new AtomicReference<>();

    @BeforeAll
    static void startCatalog() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            List<String> auth = exchange.getRequestHeaders().get("Authorization");
            SEEN.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    auth == null ? List.of() : List.copyOf(auth),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            try {
                ANSWER.get().to(exchange);
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterAll
    static void stopCatalog() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    @DynamicPropertySource
    static void catalogIsTheStandIn(DynamicPropertyRegistry registry) {
        // The context is built after the class's @BeforeAll, so the server is already listening.
        registry.add("spring.cloud.openfeign.client.config.catalog.url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort());
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private String supplier;

    @BeforeEach
    void fresh() throws Exception {
        ProcurementTestData.wipe(jdbc);
        SEEN.clear();
        ANSWER.set(CatalogClientWiringTest::knowsTheVariant);
        supplier = JsonPath.read(mvc.perform(post("/api/v1/admin/procurement/suppliers").with(signedInAs("maya-token"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Dubai Gold\",\"country\":\"AE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor signedInAs(String token) {
        return jwt().jwt(j -> j.tokenValue(token).subject("kc-maya"))
                .authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    private ResultActions placeOrder(String token) throws Exception {
        return mvc.perform(post("/api/v1/admin/procurement/purchase-orders").with(signedInAs(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":\"" + supplier + "\",\"lines\":[{\"variantId\":\"" + VARIANT
                        + "\",\"quantity\":2,\"totalPaid\":100.00}]}"));
    }

    private long ordersSaved() {
        return jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class);
    }

    /** Catalog's real reply shape, with a field procurement does not know about. */
    private static void knowsTheVariant(HttpExchange exchange) throws IOException {
        reply(exchange, 200, "application/json",
                "{\"results\":[{\"variantId\":\"" + VARIANT + "\",\"found\":true,\"productId\":\""
                        + UUID.randomUUID() + "\",\"productTitle\":\"Aurelia Ring\",\"variantTitle\":\"Aurelia Ring - M\","
                        + "\"sku\":\"RING-M\",\"kind\":\"SELLABLE\",\"productStatus\":\"DRAFT\",\"active\":true,"
                        + "\"productDeleted\":false,\"aFieldAddedLater\":\"ignored\"}]}");
    }

    private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---------------------------------------------------------------- identity

    @Test
    @DisplayName("catalog is asked once, as the signed-in person: their own token, one Authorization header, the ids in the body")
    void catalogSeesThePersonsOwnToken() throws Exception {
        placeOrder("maya-token").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].sku").value("RING-M"))
                .andExpect(jsonPath("$.lines[0].variantTitle").value("Aurelia Ring - M"));

        assertThat(SEEN).hasSize(1);
        Seen call = SEEN.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo(LOOKUP);
        assertThat(call.authorization()).as("exactly the caller's token, once").containsExactly("Bearer maya-token");
        assertThat(call.contentType()).startsWith("application/json");
        assertThat(JsonPath.<List<String>>read(call.body(), "$.variantIds")).containsExactly(VARIANT.toString());
    }

    @Test
    @DisplayName("a different person's request carries a different token: nothing is cached or shared between callers")
    void eachCallerIsHeardAsThemselves() throws Exception {
        placeOrder("maya-token").andExpect(status().isCreated());
        placeOrder("zoe-token").andExpect(status().isCreated());

        assertThat(SEEN).extracting(s -> s.authorization().get(0))
                .containsExactly("Bearer maya-token", "Bearer zoe-token");
    }

    // ---------------------------------------------------------------- failures

    @Test
    @DisplayName("catalog refusing the token (401) is a 503 with Retry-After, and nothing is saved")
    void aRefusedTokenIsUnavailable() throws Exception {
        ANSWER.set(exchange -> reply(exchange, 401, "application/json", "{\"error\":\"expired\"}"));

        placeOrder("stale-token").andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value(containsString("nothing was saved")));

        assertThat(ordersSaved()).isZero();
    }

    @Test
    @DisplayName("catalog failing (500) is a 503 with Retry-After, and nothing is saved")
    void aFailingCatalogIsUnavailable() throws Exception {
        ANSWER.set(exchange -> reply(exchange, 500, "text/plain", "boom"));

        placeOrder("maya-token").andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));

        assertThat(ordersSaved()).isZero();
    }

    @Test
    @DisplayName("a reply that is not what catalog says (an empty body) is a 503, not an order built on nothing")
    void anUnreadableReplyIsUnavailable() throws Exception {
        ANSWER.set(exchange -> reply(exchange, 200, "application/json", "{}"));

        placeOrder("maya-token").andExpect(status().isServiceUnavailable());

        assertThat(ordersSaved()).isZero();
    }

    @Test
    @DisplayName("a catalog that never answers is given up on after about three seconds, not the library's sixty")
    void silenceIsGivenUpOnQuickly() throws Exception {
        ANSWER.set(exchange -> {
            try {
                Thread.sleep(6_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long started = System.nanoTime();
        placeOrder("maya-token").andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).as("read timeout is 3 s: it waited %d ms", millis).isBetween(2_500L, 5_500L);
        assertThat(ordersSaved()).isZero();
    }
}
