package com.oriah.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.events.probe.ProbeApplication;

/**
 * The producer probe as a running service on its own database: outbox,
 * relay and replay on, the test binder standing in for Kafka. The test is the
 * broker: it takes what the producer published and decides when, in what
 * order and how often each consumer receives it.
 */
final class RunningProducer implements AutoCloseable {

    static final String PRODUCER = "probe";
    static final String TOPIC = "oriah.probe";

    private static final Instant WHEN = Instant.parse("2026-11-09T07:30:00Z");

    private final ConfigurableApplicationContext context;
    private final OutboxWriter writer;
    private final OutboxRelay relay;
    private final TransactionTemplate transaction;
    private final InputDestination input;
    private final OutputDestination output;
    private final JdbcTemplate jdbc;

    private RunningProducer(ConfigurableApplicationContext context) {
        this.context = context;
        this.writer = context.getBean(OutboxWriter.class);
        this.relay = context.getBean(OutboxRelay.class);
        this.transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        this.input = context.getBean(InputDestination.class);
        this.output = context.getBean(OutputDestination.class);
        this.jdbc = context.getBean(JdbcTemplate.class);
    }

    static RunningProducer start(FreshDatabase database) {
        // The test runs the relay itself, so it decides when a batch goes.
        return start(database, 3_600_000);
    }

    /** @param relayDelayMillis the relay's own schedule: short, and it publishes on its own while the test runs */
    static RunningProducer start(FreshDatabase database, long relayDelayMillis) {
        List<String> properties = new ArrayList<>(List.of(database.properties()));
        properties.addAll(List.of(
                "spring.application.name=" + PRODUCER,
                "spring.flyway.locations=classpath:db/oriah-events",
                "oriah.events.producer=" + PRODUCER,
                "oriah.events.topics=" + TOPIC,
                "oriah.events.outbox.enabled=true",
                "oriah.events.relay.enabled=true",
                "oriah.events.relay.delay=" + relayDelayMillis,
                "oriah.events.replay.enabled=true",
                // Ignored by the test binder; required by the relay, as in a service.
                "spring.cloud.stream.kafka.default.producer.sync=true",
                // The send timeouts the README asks of a producing service.
                // Ignored by the test binder; replay refuses to start without
                // them (a step must fit the listener's poll interval: see
                // ReplayStepTime), as in a service.
                "spring.cloud.stream.kafka.binder.producer-properties.max.block.ms=10000",
                "spring.cloud.stream.kafka.binder.producer-properties.request.timeout.ms=5000",
                "spring.cloud.stream.kafka.binder.producer-properties.delivery.timeout.ms=10000"));
        return new RunningProducer(new SpringApplicationBuilder(ProbeApplication.class,
                TestChannelBinderConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(properties.toArray(String[]::new))
                .run());
    }

    /** A fact: one sale, its own aggregate. */
    AppendedEvent sale(String saleId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("saleId", saleId);
        payload.put("amount", new BigDecimal("150.00"));
        payload.put("currency", "ZMW");
        return append("sale", saleId, "sale.recorded", payload);
    }

    /** A full snapshot of a product as it now is. */
    AppendedEvent productUpdated(String productId, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("title", title);
        payload.put("price", new BigDecimal("2500.00"));
        payload.put("currency", "ZMW");
        return append("product", productId, "product.updated", payload);
    }

    /** The last snapshot of a product: gone. */
    AppendedEvent productDeleted(String productId) {
        return append("product", productId, "product.deleted", Map.of("productId", productId));
    }

    private AppendedEvent append(String aggregateType, String aggregateId, String eventType, Object payload) {
        return transaction.execute(status -> writer.append(new EventDraft(
                aggregateType, aggregateId, eventType, 1, TOPIC, WHEN, "system:probe", payload)));
    }

    /** Runs the relay until the backlog is out; returns what it published to the live topic, in order. */
    List<Message<byte[]>> publish() {
        while (relay.relayBatch() > 0) {
            // keep going until the backlog is published
        }
        return Relayed.all(output, TOPIC);
    }

    /**
     * Delivers a consumer's replay request to this producer's listener, as
     * Kafka would, and returns the replay it sent: every record, then the
     * end-of-replay marker.
     *
     * <p>A replay longer than one step (oriah.events.replay.chunk-size rows)
     * is answered whole all the same: the producer sends each continuation to
     * the requests topic, and the test binder hands it straight back to this
     * same listener (one in-memory channel per topic) before the send
     * returns. ChunkedReplayKafkaTest shows the steps on a real broker.
     */
    List<Message<byte[]>> answer(Message<byte[]> request, String consumer) {
        input.send(request, ReplayRequest.TOPIC);
        return Relayed.all(output, "oriah.replay." + consumer);
    }

    /** The next message the relay published to the live topic, or null if none comes within the wait. */
    Message<byte[]> nextLive(long waitMillis) {
        return output.receive(waitMillis, TOPIC);
    }

    /** How far the relay has published. */
    long relayCursor() {
        return jdbc.queryForObject("SELECT last_outbox_id FROM outbox_relay_cursor WHERE relay = 'main'", Long.class);
    }

    long newestOutboxId() {
        return jdbc.queryForObject("SELECT coalesce(max(outbox_id), 0) FROM outbox", Long.class);
    }

    /** Every row of the log, oldest first: what the consumers must end up agreeing with. */
    List<Map<String, Object>> log() {
        return jdbc.queryForList("""
                SELECT outbox_id, event_id, aggregate_type, aggregate_id, seq, event_type, payload::text AS payload
                FROM outbox ORDER BY outbox_id""");
    }

    @Override
    public void close() {
        context.close();
    }
}
