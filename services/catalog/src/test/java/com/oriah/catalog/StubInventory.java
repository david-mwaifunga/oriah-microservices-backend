package com.oriah.catalog;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for inventory's held-cost read, shared by every catalog test that prices something. One real HTTP server for the
 * whole test run, reached through the discovery client's static list (see {@link TestcontainersConfiguration}), so every Spring
 * context stays the same context and is built once.
 *
 * <p>A test says what stock a variant holds ({@link #holds}) and may make the stand-in fail ({@link #failWith}); it forgets
 * everything on {@link #reset}. What the stand-in saw (the token, the ids) is in {@link #calls}. {@link #beforeAnswering} runs a
 * test's code at the moment of the call, which is how a change that lands while a price is being checked is made to happen.
 */
public final class StubInventory {

    /** One lot still holding stock. */
    public record Lot(String unitCost, int qtyRemaining, boolean estimated) {
    }

    /** One request the stand-in saw. */
    public record Call(String authorization, List<UUID> ids) {
    }

    private static final Map<UUID, List<Lot>> HELD = new ConcurrentHashMap<>();
    private static final List<Call> CALLS = new CopyOnWriteArrayList<>();
    private static volatile int failStatus = 0;
    private static volatile Runnable before = () -> { };
    private static final HttpServer SERVER = start();

    private StubInventory() {
    }

    public static String uri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    /** Says what a variant holds: its lots, oldest first. */
    public static void holds(UUID variantId, Lot... lots) {
        HELD.put(variantId, List.of(lots));
    }

    public static Lot lot(String unitCost, int qtyRemaining) {
        return new Lot(unitCost, qtyRemaining, false);
    }

    public static Lot estimatedLot(String unitCost, int qtyRemaining) {
        return new Lot(unitCost, qtyRemaining, true);
    }

    /** Answers every following request with this status and no cost. */
    public static void failWith(int status) {
        failStatus = status;
    }

    /** Runs this when a request arrives, before it is answered. */
    public static void beforeAnswering(Runnable action) {
        before = action;
    }

    public static List<Call> calls() {
        return List.copyOf(CALLS);
    }

    public static void reset() {
        HELD.clear();
        CALLS.clear();
        failStatus = 0;
        before = () -> { };
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/admin/costs/held", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                String raw = query == null ? "" : URLDecoder.decode(query.replace("variantIds=", ""), StandardCharsets.UTF_8);
                List<UUID> ids = raw.isEmpty() ? List.of() : java.util.Arrays.stream(raw.split(",")).map(UUID::fromString).toList();
                CALLS.add(new Call(exchange.getRequestHeaders().getFirst("Authorization"), ids));
                before.run();

                int status = failStatus == 0 ? 200 : failStatus;
                String body = status == 200
                        ? "{\"variants\":[" + ids.stream().distinct().map(StubInventory::answer).collect(Collectors.joining(",")) + "]}"
                        : "{\"type\":\"https://oriah.dev/problems/internal-server-error\",\"status\":" + status + "}";
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

    private static String answer(UUID id) {
        List<Lot> lots = HELD.getOrDefault(id, List.of()).stream().filter(l -> l.qtyRemaining() > 0).toList();
        if (lots.isEmpty()) {
            return "{\"variantId\":\"" + id + "\",\"onHand\":0,\"heldUnits\":0,\"highestUnitCost\":null,\"averageUnitCost\":null,"
                    + "\"currency\":null,\"estimated\":false,\"lots\":[]}";
        }
        BigDecimal highest = lots.stream().map(l -> new BigDecimal(l.unitCost())).max(BigDecimal::compareTo).orElseThrow();
        int units = lots.stream().mapToInt(Lot::qtyRemaining).sum();
        BigDecimal value = lots.stream().map(l -> new BigDecimal(l.unitCost()).multiply(BigDecimal.valueOf(l.qtyRemaining())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = value.divide(BigDecimal.valueOf(units), 2, java.math.RoundingMode.HALF_UP);
        String lotJson = lots.stream().map(l -> "{\"lotId\":\"" + UUID.nameUUIDFromBytes((id + l.unitCost()).getBytes(StandardCharsets.UTF_8))
                + "\",\"unitCost\":" + l.unitCost() + ",\"qtyRemaining\":" + l.qtyRemaining() + ",\"estimated\":" + l.estimated()
                + ",\"revision\":1,\"receivedAt\":\"" + Instant.parse("2026-11-09T08:00:00Z") + "\",\"consignmentReference\":\"C-000045\"}")
                .collect(Collectors.joining(","));
        return "{\"variantId\":\"" + id + "\",\"onHand\":" + units + ",\"heldUnits\":" + units + ",\"highestUnitCost\":" + highest.toPlainString()
                + ",\"averageUnitCost\":" + average.toPlainString() + ",\"currency\":\"ZMW\",\"estimated\":"
                + lots.stream().anyMatch(Lot::estimated) + ",\"lots\":[" + lotJson + "]}";
    }
}
