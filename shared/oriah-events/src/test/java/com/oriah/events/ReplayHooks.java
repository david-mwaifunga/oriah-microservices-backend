package com.oriah.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MimeType;

/**
 * Stands between a producer's replay and the real StreamBridge, to make two
 * things happen at an exact moment of a replay on a real broker:
 * <ul>
 *   <li>{@code hook.append-during-replay=<n>}: at the replay's first send,
 *       n sales are appended to the outbox, each in its own committed
 *       transaction -- rows written while the replay runs, so after H;</li>
 *   <li>{@code hook.crash-after-continuation=<id>}: the continuation that
 *       carries on after outbox row {@code <id>} is really sent, and then the
 *       send throws, once. The step's records and its continuation are on
 *       Kafka, but the step does not commit -- what a crash between the
 *       sends and the commit leaves behind -- so Kafka delivers the step
 *       again.</li>
 * </ul>
 * Everything else goes to the real StreamBridge untouched. A producer gets it
 * by being started with {@link Config} as an extra source.
 */
final class ReplayHooks implements StreamOperations {

    private static final Instant WHEN = Instant.parse("2026-11-09T07:30:00Z");

    private final StreamOperations real;
    private final ObjectProvider<OutboxWriter> writer;
    private final TransactionTemplate ownTransaction;
    private final String topic;
    private final int appendDuringReplay;
    private final Long crashAfterContinuation;
    private final AtomicBoolean appendedYet = new AtomicBoolean();
    private final AtomicBoolean crashedYet = new AtomicBoolean();
    private final List<AppendedEvent> appended = new CopyOnWriteArrayList<>();

    ReplayHooks(StreamOperations real, ObjectProvider<OutboxWriter> writer, PlatformTransactionManager transactions,
                String topic, int appendDuringReplay, Long crashAfterContinuation) {
        this.real = real;
        this.writer = writer;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.topic = topic;
        this.appendDuringReplay = appendDuringReplay;
        this.crashAfterContinuation = crashAfterContinuation;
    }

    /** The rows appended while the replay ran, oldest first. */
    List<AppendedEvent> appended() {
        return List.copyOf(appended);
    }

    /** Whether the crash after the chosen continuation has happened. */
    boolean crashed() {
        return crashedYet.get();
    }

    @Override
    public boolean send(String bindingName, Object data) {
        return hooked(bindingName, data, () -> real.send(bindingName, data));
    }

    @Override
    public boolean send(String bindingName, Object data, MimeType outputContentType) {
        return hooked(bindingName, data, () -> real.send(bindingName, data, outputContentType));
    }

    @Override
    public boolean send(String bindingName, String binderName, Object data) {
        return hooked(bindingName, data, () -> real.send(bindingName, binderName, data));
    }

    @Override
    public boolean send(String bindingName, String binderName, Object data, MimeType outputContentType) {
        return hooked(bindingName, data, () -> real.send(bindingName, binderName, data, outputContentType));
    }

    private boolean hooked(String bindingName, Object data, BooleanSupplier realSend) {
        if (bindingName.startsWith("oriah.replay.") && appendDuringReplay > 0 && appendedYet.compareAndSet(false, true)) {
            for (int i = 0; i < appendDuringReplay; i++) {
                String sale = "during-" + UUID.randomUUID();
                appended.add(ownTransaction.execute(status -> writer.getObject().append(new EventDraft(
                        "sale", sale, "sale.recorded", 1, topic, WHEN, "system:probe",
                        Map.of("saleId", sale, "amount", "150.00", "currency", "ZMW")))));
            }
        }
        boolean sent = realSend.getAsBoolean();
        if (ReplayRequest.TOPIC.equals(bindingName) && crashAfterContinuation != null
                && crashAfterContinuation.equals(afterOutboxId(data)) && crashedYet.compareAndSet(false, true)) {
            throw new IllegalStateException("simulated crash: the step's records and its continuation after outbox "
                    + crashAfterContinuation + " went out, and the step did not commit");
        }
        return sent;
    }

    private static Long afterOutboxId(Object data) {
        if (!(data instanceof Message<?> message) || !(message.getPayload() instanceof byte[] body)) {
            return null;
        }
        Object after = EventJson.mapper().readValue(body, Map.class).get("afterOutboxId");
        return after == null ? null : ((Number) after).longValue();
    }

    /** Puts the hooks in front of the real StreamBridge, for whatever asks for a StreamOperations (the replayer does). */
    @Configuration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @Primary
        ReplayHooks replayHooks(StreamBridge streamBridge, ObjectProvider<OutboxWriter> writer,
                                PlatformTransactionManager transactions, Environment environment) {
            return new ReplayHooks(streamBridge, writer, transactions, environment.getRequiredProperty("hook.topic"),
                    environment.getProperty("hook.append-during-replay", Integer.class, 0),
                    environment.getProperty("hook.crash-after-continuation", Long.class));
        }
    }
}
