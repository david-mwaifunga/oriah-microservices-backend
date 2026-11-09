package com.oriah.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.messaging.Message;
import org.springframework.util.MimeType;

/**
 * Stands between CatchUp and the real StreamBridge in a late consumer, to
 * check the two promises CatchUp makes about its replay request:
 * <ul>
 *   <li>it is sent only after its outbox_catch_up row commits, so no
 *       replayed record can arrive before the row names its request. On
 *       every request this reads the row through a connection of its own --
 *       never the one CatchUp's transaction holds -- so it sees only what has
 *       committed, and keeps what it saw ({@link #seen()});</li>
 *   <li>a request the binder refuses fails the start. With
 *       {@code watch.refuse=ANSWER_FALSE} or {@code THROW} it refuses every
 *       replay request, the two ways a broken binder or a stopped broker
 *       would.</li>
 * </ul>
 * Everything else goes to the real StreamBridge untouched. A consumer gets it
 * by being started with {@link Config} as an extra source.
 */
final class WatchedRequests implements StreamOperations {

    enum Refusal { NONE, ANSWER_FALSE, THROW }

    /** One replay request: the id it carried, and the id the committed row named as it went out (null: no row). */
    record Seen(UUID sent, UUID committed) {
    }

    private final StreamOperations real;
    private final DataSource dataSource;
    private final Refusal refusal;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    WatchedRequests(StreamOperations real, DataSource dataSource, Refusal refusal) {
        this.real = real;
        this.dataSource = dataSource;
        this.refusal = refusal;
    }

    List<Seen> seen() {
        return List.copyOf(seen);
    }

    @Override
    public boolean send(String bindingName, Object data) {
        return watch(bindingName, data, () -> real.send(bindingName, data));
    }

    @Override
    public boolean send(String bindingName, Object data, MimeType outputContentType) {
        return watch(bindingName, data, () -> real.send(bindingName, data, outputContentType));
    }

    @Override
    public boolean send(String bindingName, String binderName, Object data) {
        return watch(bindingName, data, () -> real.send(bindingName, binderName, data));
    }

    @Override
    public boolean send(String bindingName, String binderName, Object data, MimeType outputContentType) {
        return watch(bindingName, data, () -> real.send(bindingName, binderName, data, outputContentType));
    }

    private boolean watch(String bindingName, Object data, BooleanSupplier realSend) {
        if (!ReplayRequest.TOPIC.equals(bindingName) || !(data instanceof Message<?> message)) {
            return realSend.getAsBoolean();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = EventJson.mapper().readValue((byte[]) message.getPayload(), Map.class);
        UUID sent = UUID.fromString((String) body.get("requestId"));
        seen.add(new Seen(sent, committedRequest((String) body.get("producer"))));
        return switch (refusal) {
            case NONE -> realSend.getAsBoolean();
            case ANSWER_FALSE -> false;
            case THROW -> throw new IllegalStateException("simulated broker failure");
        };
    }

    /**
     * The request id the producer's row names, as any other connection sees
     * it now. A connection straight from the pool, not through Spring: inside
     * a transaction Spring would hand back the transaction's own connection,
     * which also sees what has not committed.
     */
    private UUID committedRequest(String producer) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT request_id FROM outbox_catch_up WHERE producer = ?")) {
            select.setString(1, producer);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getObject("request_id", UUID.class) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read outbox_catch_up", e);
        }
    }

    /** Puts the watch in front of the real StreamBridge, for whatever asks for a StreamOperations (CatchUp does). */
    @Configuration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @Primary
        WatchedRequests watchedRequests(StreamBridge streamBridge, DataSource dataSource, Environment environment) {
            return new WatchedRequests(streamBridge, dataSource,
                    environment.getProperty("watch.refuse", Refusal.class, Refusal.NONE));
        }
    }
}
