package com.oriah.catalog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.oriah.catalog.promotion.PromotionDefinition;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for promotion's in-force read, shared by every catalog test that prices a piece. One real HTTP server for the whole test run,
 * reached through the discovery client's static list (see {@link TestcontainersConfiguration}), so every Spring context stays the same
 * context. With nothing registered it answers "no promotion in force" at every instant, so tests that are not about promotions see none.
 *
 * <p>A test registers a promotion with the window it is in force for ({@link #promote}); the stand-in answers with those whose window
 * holds the instant asked about ({@code at}, or now when none is sent), which is how a test proves the right instant was asked. It may be
 * made to fail ({@link #failWith}) or to answer slowly ({@link #delayBy}); it forgets everything on {@link #reset}.
 */
public final class StubPromotion {

    /** One promotion, in force from {@code from} (inclusive) until {@code until} (exclusive). */
    private record Registered(PromotionDefinition definition, Instant from, Instant until) {
    }

    /** One request the stand-in saw: the {@code at} it was asked about (null when none was sent) and the Authorization header (null when none). */
    public record Call(String at, String authorization) {
    }

    private static final List<Registered> REGISTERED = new CopyOnWriteArrayList<>();
    private static final List<Call> CALLS = new CopyOnWriteArrayList<>();
    private static volatile int failStatus = 0;
    private static volatile long delayMillis = 0;
    private static volatile String rawBody = null;
    private static final HttpServer SERVER = start();

    private StubPromotion() {
    }

    public static String uri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    /** A promotion in force over a window. */
    public static void promote(PromotionDefinition definition, Instant from, Instant until) {
        REGISTERED.add(new Registered(definition, from, until));
    }

    /** A promotion in force from the beginning of time until the end of it. */
    public static void promote(PromotionDefinition definition) {
        promote(definition, Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2999-01-01T00:00:00Z"));
    }

    public static void failWith(int status) {
        failStatus = status;
    }

    public static void delayBy(long millis) {
        delayMillis = millis;
    }

    /** Answers every following request with this exact body (for an answer that does not read). */
    public static void answerWith(String body) {
        rawBody = body;
    }

    public static List<Call> calls() {
        return List.copyOf(CALLS);
    }

    public static void reset() {
        REGISTERED.clear();
        CALLS.clear();
        failStatus = 0;
        delayMillis = 0;
        rawBody = null;
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/promotions/in-force", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                String at = query == null || !query.startsWith("at=") ? null : java.net.URLDecoder.decode(query.substring(3), StandardCharsets.UTF_8);
                CALLS.add(new Call(at, exchange.getRequestHeaders().getFirst("Authorization")));
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                Instant when = at == null ? Instant.now() : Instant.parse(at);
                int status = failStatus == 0 ? 200 : failStatus;
                String body = status != 200
                        ? "{\"type\":\"https://oriah.dev/problems/internal-server-error\",\"status\":" + status + "}"
                        : rawBody != null ? rawBody
                        : "{\"at\":\"" + when + "\",\"promotions\":["
                                + REGISTERED.stream().filter(r -> !when.isBefore(r.from()) && when.isBefore(r.until()))
                                        .map(r -> json(r.definition())).collect(Collectors.joining(",")) + "]}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "application/problem+json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The definition as promotion's own read states it (more fields than catalog uses, on purpose). */
    private static String json(PromotionDefinition d) {
        UUID scope = d.scopeId();
        return "{\"id\":\"" + d.id() + "\",\"name\":\"Promotion " + d.id().toString().substring(0, 8) + "\",\"scopeType\":\"" + d.scopeType()
                + "\",\"scopeId\":" + (scope == null ? "null" : "\"" + scope + "\"") + ",\"discountType\":\"" + d.discountType()
                + "\",\"amount\":" + d.amount().toPlainString() + ",\"startsOn\":\"" + d.startsOn() + "\",\"endsOn\":\"2999-12-31\","
                + "\"stoppedAt\":null,\"stoppedBy\":null,\"replacesId\":null,\"createdAt\":\"" + d.createdAt() + "\",\"createdBy\":\"user:kc-maya\"}";
    }

    /** For a test that wants to build a definition without spelling out every field. */
    public static PromotionDefinition definition(String scopeType, UUID scopeId, String discountType, String amount, String startsOn) {
        return new PromotionDefinition(UUID.randomUUID(), scopeType, scopeId, discountType, new java.math.BigDecimal(amount),
                java.time.LocalDate.parse(startsOn), Instant.parse("2026-11-01T08:00:00Z"));
    }
}
