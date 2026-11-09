package com.oriah.events;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.events.latecomer.LateConsumerApplication;

/**
 * A late consumer probe as a running service on its own database, with the
 * test binder: its live binding reads the producer's topic, its replay
 * binding reads {@code oriah.replay.<consumer>}, and CatchUp is configured to
 * catch up from the producer probe. Starting one is "the service starts";
 * closing it and starting another on the same database is a restart.
 */
final class RunningConsumer implements AutoCloseable {

    enum Kind {
        FACT("fact", "fact-probe"),
        PROJECTION("projection", "projection-probe");

        final String property;
        final String consumer;

        Kind(String property, String consumer) {
            this.property = property;
            this.consumer = consumer;
        }
    }

    /** One outbox_catch_up row. */
    record CatchUpRow(UUID requestId, long replayedThrough, Long completedThrough, OffsetDateTime completedAt) {
    }

    /** One row of the projection probe's table. */
    record ProductRow(long seq, String body) {
    }

    private final ConfigurableApplicationContext context;
    private final Kind kind;
    private final InputDestination input;
    private final OutputDestination output;
    private final JdbcTemplate jdbc;

    private RunningConsumer(ConfigurableApplicationContext context, Kind kind) {
        this.context = context;
        this.kind = kind;
        this.input = context.getBean(InputDestination.class);
        this.output = context.getBean(OutputDestination.class);
        this.jdbc = context.getBean(JdbcTemplate.class);
    }

    /**
     * @param extra more properties, after the defaults, so one given here
     *              wins (e.g. {@code probe.poison=<id>} or other producers)
     */
    static RunningConsumer start(Kind kind, FreshDatabase database, String... extra) {
        return start(List.of(), kind, database, extra);
    }

    /** @param sources more configuration for the service, beside its own (e.g. {@link WatchedRequests.Config}) */
    static RunningConsumer start(List<Class<?>> sources, Kind kind, FreshDatabase database, String... extra) {
        List<String> properties = new ArrayList<>(List.of(database.properties()));
        properties.addAll(List.of(
                "spring.application.name=" + kind.consumer,
                "spring.flyway.locations=classpath:db/oriah-events,classpath:db/late-consumer",
                "probe.kind=" + kind.property,
                "oriah.events.inbox.enabled=true",
                "oriah.events.inbox.topics=" + RunningProducer.TOPIC,
                "oriah.events.inbox.catch-up.consumer=" + kind.consumer,
                "oriah.events.inbox.catch-up.producers=" + RunningProducer.PRODUCER,
                "spring.cloud.function.definition=live;replay",
                // A new group reading from the earliest offset Kafka still has.
                "spring.cloud.stream.bindings.live-in-0.destination=" + RunningProducer.TOPIC,
                "spring.cloud.stream.bindings.live-in-0.group=" + kind.consumer,
                "spring.cloud.stream.bindings.replay-in-0.destination=oriah.replay." + kind.consumer,
                "spring.cloud.stream.bindings.replay-in-0.group=" + kind.consumer,
                // One attempt: every failure is marked not retryable, so a
                // failing record fails at once (a service would retry, then
                // dead-letter it -- the outcome is the same). Not
                // max-attempts=1: the module refuses that on its bindings,
                // because the Kafka binder would then dead-letter through
                // its own sender (see DeadLetterStartupCheck).
                "spring.cloud.stream.default.consumer.max-attempts=2",
                "spring.cloud.stream.default.consumer.retryable-exceptions.java.lang.RuntimeException=false",
                // Dead-lettering on both bindings, as a service sets it.
                // Ignored by the test binder; required by the module's
                // startup check, as in a service.
                "spring.cloud.stream.kafka.default.consumer.enable-dlq=true",
                "spring.cloud.stream.kafka.bindings.live-in-0.consumer.dlq-name="
                        + RunningProducer.TOPIC + ".dlq." + kind.consumer,
                "spring.cloud.stream.kafka.bindings.replay-in-0.consumer.dlq-name=oriah.replay." + kind.consumer
                        + ".dlq." + kind.consumer,
                // Ignored by the test binder; required by CatchUp, as in a service.
                "spring.cloud.stream.kafka.default.producer.sync=true"));
        properties.addAll(List.of(extra));
        List<Class<?>> all = new ArrayList<>(List.of(LateConsumerApplication.class,
                TestChannelBinderConfiguration.class));
        all.addAll(sources);
        return new RunningConsumer(new SpringApplicationBuilder(all.toArray(Class<?>[]::new))
                .web(WebApplicationType.NONE)
                .properties(properties.toArray(String[]::new))
                .run(), kind);
    }

    String name() {
        return kind.consumer;
    }

    /** The replay requests this consumer has sent since it started (or since the last call). */
    List<Message<byte[]>> requests() {
        return Relayed.all(output, ReplayRequest.TOPIC);
    }

    /** One delivery from the producer's live topic. A failure is the binder's to handle, as in a service. */
    void live(Message<byte[]> message) {
        input.send(asDelivered(message), RunningProducer.TOPIC);
    }

    /** One delivery from this consumer's replay topic. */
    void replay(Message<byte[]> message) {
        input.send(asDelivered(message), "oriah.replay." + kind.consumer);
    }

    /**
     * A message of its own for each delivery, as Kafka gives each consumer
     * and each redelivery a record of its own.
     *
     * <p>Not a nicety. Spring Cloud Stream's inbound content-type interceptor
     * rewrites the {@code contentType} header of the message it is handed, in
     * place (a String becomes a MimeType). When two consumers, or a consumer's
     * live and replay threads, are handed the very same message object at the
     * same moment, one thread can read the header as a String and the other
     * replace it before the cast: {@code ClassCastException: MimeType cannot
     * be cast to String}, inside the binder, before the module sees the
     * message. The test binder then logs the failure and drops the message,
     * and {@code LateConsumerTest.concurrentProducerAndBothStreams} lost one
     * sale that way (2026-11-09). Kafka never shares a record between
     * deliveries, so a copy here is what the real binder does.
     */
    private static Message<byte[]> asDelivered(Message<byte[]> message) {
        return MessageBuilder.fromMessage(message).build();
    }

    void live(List<Message<byte[]>> messages) {
        messages.forEach(this::live);
    }

    void replay(List<Message<byte[]>> messages) {
        messages.forEach(this::replay);
    }

    CatchUpRow catchUp(String producer) {
        return catchUp(jdbc, producer);
    }

    /** The row as it is in a consumer's database while no consumer runs on it (e.g. after a start that failed). */
    static CatchUpRow catchUp(FreshDatabase database, String producer) {
        return catchUp(new JdbcTemplate(new DriverManagerDataSource(
                database.url(), database.username(), database.password())), producer);
    }

    private static CatchUpRow catchUp(JdbcTemplate jdbc, String producer) {
        List<CatchUpRow> rows = jdbc.query("""
                SELECT request_id, replayed_through, completed_through, completed_at
                FROM outbox_catch_up WHERE producer = ?""",
                (rs, n) -> new CatchUpRow(rs.getObject("request_id", UUID.class), rs.getLong("replayed_through"),
                        rs.getObject("completed_through", Long.class),
                        rs.getObject("completed_at", OffsetDateTime.class)),
                producer);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The fact probe's applied sales: one event id per application, duplicates kept. */
    List<UUID> appliedSales() {
        return jdbc.queryForList("SELECT event_id FROM probe_sale", UUID.class);
    }

    /** The projection probe's table, by product id. */
    Map<String, ProductRow> products() {
        Map<String, ProductRow> products = new LinkedHashMap<>();
        jdbc.query("SELECT product_id, seq, body FROM probe_product ORDER BY product_id",
                rs -> {
                    products.put(rs.getString("product_id"), new ProductRow(rs.getLong("seq"), rs.getString("body")));
                });
        return products;
    }

    int markers(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE consumer = ? AND event_id = ?",
                Integer.class, kind.consumer, eventId);
    }

    CatchUp catchUpHelper() {
        return context.getBean(CatchUp.class);
    }

    <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    TransactionTemplate transaction() {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @Override
    public void close() {
        context.close();
    }
}
