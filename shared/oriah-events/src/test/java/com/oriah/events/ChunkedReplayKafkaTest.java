package com.oriah.events;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.oriah.events.latecomer.LateConsumerApplication;
import com.oriah.events.probe.ProbeApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A replay answered in steps, end to end on a real broker: a producer
 * service and a late consumer service, each on the real Kafka binder and its
 * own database, and nothing in between but Kafka.
 *
 * <p>The producer has a history several steps long (35 rows; a step sends at
 * most {@code oriah.events.replay.chunk-size} rows, 10 by default). The
 * consumer starts late and its CatchUp asks for the whole log. The producer
 * answers one step per delivery and ends each step with a continuation sent
 * back to the requests topic, which it then reads and answers in turn, until
 * the last step ends with the end-of-replay marker.
 *
 * <p>Two things go wrong on the way, on purpose:
 * <ul>
 *   <li>the second step "crashes" after its ten records and its
 *       continuation have gone out but before it commits, so Kafka delivers
 *       it again: its records are sent again, and its continuation is on the
 *       topic twice;</li>
 *   <li>rows are appended to the outbox while the replay runs.</li>
 * </ul>
 * It must not matter: every row arrives in order with an unbroken chain, the
 * repeated records are applied once, the doubled continuation is answered
 * once, the new rows arrive live and not in the replay, and the consumer's
 * catch-up completes at H.
 *
 * <p>Both topics a replay uses have three partitions here, as a busy service
 * would give them; every record of one replay is keyed by its request id, so
 * each topic's share of the replay sits on one partition, in order.
 */
class ChunkedReplayKafkaTest {

    private static final String PRODUCER = "probe";
    private static final String CONSUMER = "fact-probe";
    private static final String LIVE = "oriah.probe-chunked";
    private static final String REPLAY = "oriah.replay." + CONSUMER;

    private static final int HISTORY = 35;
    private static final int APPENDED_DURING_REPLAY = 3;
    /** The second step: rows 11 to 20, then the continuation after 20. */
    private static final long CRASH_AFTER = 20;
    private static final Duration WAIT = Duration.ofSeconds(120);

    private static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    // The compose image, with the listener fix OutboxRelayKafkaTest explains.
    private static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"))
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");

    @BeforeAll
    static void startInfrastructure() {
        postgres.start();
        kafka.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        kafka.stop();
        postgres.stop();
    }

    @Test
    @DisplayName("35 rows replayed in steps of 10 on a real broker: every row in order with an unbroken chain; a step "
            + "answered again after a crash before its commit is applied once; its continuation, on the topic "
            + "twice, is answered once; rows appended meanwhile go only live; the catch-up completes at H")
    void longHistoryInSteps() {
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(ReplayRequest.TOPIC, 3, (short) 1),
                    new NewTopic(REPLAY, 3, (short) 1), new NewTopic(LIVE, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("could not create the topics", e);
        }
        try (ConfigurableApplicationContext producer = startProducer()) {
            // The listener's settings as the Kafka binder bound them, all from the module: one request per poll,
            // five minutes for it, dead-lettered to a topic of the service's own, a malformed request not retried.
            Map<String, Object> listener = boundListener(producer);
            Map<String, Object> kafkaSettings = map(listener.get("extension"));
            assertThat(map(kafkaSettings.get("configuration"))).containsEntry("max.poll.records", "1")
                    .containsEntry("max.poll.interval.ms", "300000");
            assertThat(kafkaSettings.get("enableDlq")).isEqualTo(true);
            assertThat(kafkaSettings.get("dlqName")).isEqualTo(ReplayRequest.TOPIC + ".dlq." + PRODUCER);
            assertThat(map(listener.get("retryableExceptions")).entrySet())
                    .anySatisfy(entry -> {
                        assertThat(String.valueOf(entry.getKey())).contains(IllegalArgumentException.class.getName());
                        assertThat(entry.getValue()).isEqualTo(false);
                    });
            JdbcTemplate producerDb = producer.getBean(JdbcTemplate.class);
            List<AppendedEvent> history = appendHistory(producer);
            long h = history.get(HISTORY - 1).outboxId();
            assertThat(h).as("H, the newest row when the consumer asks").isEqualTo(HISTORY);
            await("the relay publishing the history", () -> cursor(producerDb), cursor -> cursor == h);

            try (ConfigurableApplicationContext consumer = startConsumer()) {
                JdbcTemplate consumerDb = consumer.getBean(JdbcTemplate.class);
                UUID requestId = consumerDb.queryForObject(
                        "SELECT request_id FROM outbox_catch_up WHERE producer = ?", UUID.class, PRODUCER);
                ReplayHooks hooks = producer.getBean(ReplayHooks.class);

                await("the catch-up completing", () -> completedAt(consumerDb), completed -> completed != null);
                List<AppendedEvent> during = hooks.appended();
                assertThat(during).as("rows appended while the replay ran").hasSize(APPENDED_DURING_REPLAY);
                List<UUID> everySale = new ArrayList<>();
                history.forEach(event -> everySale.add(event.eventId()));
                during.forEach(event -> everySale.add(event.eventId()));
                await("every sale applied, the new ones from the live topic",
                        () -> consumerDb.queryForList("SELECT event_id FROM probe_sale", UUID.class),
                        applied -> applied.size() >= everySale.size());
                // Every request on the topic answered, the second copy of the continuation too.
                await("the producer's group past every request", ChunkedReplayKafkaTest::requestsLeft,
                        left -> left == 0);

                // The requests topic: the consumer's request, then one continuation per step but the last.
                List<ConsumerRecord<byte[], byte[]>> requests = everything(ReplayRequest.TOPIC);
                assertThat(requests).extracting(record -> field(record, "afterOutboxId"))
                        .as("where each step starts after: the crashed step's continuation is there twice")
                        .containsExactly(null, 10L, CRASH_AFTER, CRASH_AFTER, 30L);
                assertThat(requests).extracting(record -> field(record, "throughOutboxId"))
                        .containsExactly(null, h, h, h, h);
                assertThat(requests).extracting(record -> field(record, "fromOutboxId")).containsOnly(1L);
                assertThat(requests).extracting(ChunkedReplayKafkaTest::key)
                        .as("the key of the request and of every continuation").containsOnly(requestId.toString());
                assertThat(requests).extracting(ConsumerRecord::partition).as("one partition for the whole replay")
                        .containsOnly(requests.get(0).partition());
                assertThat(hooks.crashed()).isTrue();

                // The replay topic: steps of ten, the crashed step's ten twice, then the marker.
                List<ConsumerRecord<byte[], byte[]>> replay = everything(REPLAY);
                assertThat(replay).extracting(ChunkedReplayKafkaTest::key)
                        .as("the key of every replayed record and the marker").containsOnly(requestId.toString());
                assertThat(replay).extracting(ConsumerRecord::partition).as("one partition for the whole replay")
                        .containsOnly(replay.get(0).partition());
                List<Long> ids = new ArrayList<>();
                List<Long> previous = new ArrayList<>();
                for (long[] run : new long[][] {{1, 10}, {11, 20}, {11, 20}, {21, 35}}) {
                    LongStream.rangeClosed(run[0], run[1]).forEach(id -> {
                        ids.add(id);
                        previous.add(id - 1);
                    });
                }
                ids.add(null);
                previous.add(null);
                assertThat(replay).extracting(record -> number(record, "outbox-id")).as("the outbox ids, in order")
                        .containsExactlyElementsOf(ids);
                assertThat(replay).extracting(record -> number(record, "replay-prev-outbox-id"))
                        .as("each record names the one before it; the repeated step starts again after 10")
                        .containsExactlyElementsOf(previous);
                assertThat(replay).extracting(record -> header(record, "replay-request-id"))
                        .containsOnly(requestId.toString());
                assertThat(replay).extracting(record -> header(record, "event-id")).as("no row appended during it")
                        .doesNotContainAnyElementsOf(during.stream().map(event -> event.eventId().toString())
                                .toList());
                ConsumerRecord<byte[], byte[]> marker = replay.get(replay.size() - 1);
                assertThat(header(marker, "event-type")).isEqualTo(ReplayRequest.COMPLETED);
                assertThat(number(marker, "through-outbox-id")).as("H").isEqualTo(h);
                assertThat(number(marker, "last-outbox-id")).as("the last row the replay sent").isEqualTo(h);

                // The producer answered each step once: the request, then the continuations after 10, 20, 30.
                ReplayRequest first = new ReplayRequest(requestId, PRODUCER, CONSUMER, 1);
                assertThat(producerDb.queryForList(
                        "SELECT event_id FROM processed_event WHERE consumer = 'replay-requests'", UUID.class))
                        .containsExactlyInAnyOrder(requestId, first.continueAfter(h, 10).stepId(),
                                first.continueAfter(h, CRASH_AFTER).stepId(), first.continueAfter(h, 30).stepId());

                // The rows appended during the replay went out live, after H.
                List<String> live = everything(LIVE).stream().map(record -> header(record, "event-id")).toList();
                for (AppendedEvent event : during) {
                    assertThat(event.outboxId()).isGreaterThan(h);
                    assertThat(live).contains(event.eventId().toString());
                }

                // The consumer: every sale exactly once, and the catch-up complete at H.
                assertThat(consumerDb.queryForList("SELECT event_id FROM probe_sale", UUID.class))
                        .as("every sale applied exactly once").containsExactlyInAnyOrderElementsOf(everySale);
                Map<String, Object> row = consumerDb.queryForMap(
                        "SELECT request_id, replayed_through, completed_through FROM outbox_catch_up "
                                + "WHERE producer = ?", PRODUCER);
                assertThat(row.get("request_id")).isEqualTo(requestId);
                assertThat(((Number) row.get("replayed_through")).longValue()).isEqualTo(h);
                assertThat(((Number) row.get("completed_through")).longValue()).isEqualTo(h);
            }
        }
    }

    private static List<AppendedEvent> appendHistory(ConfigurableApplicationContext producer) {
        OutboxWriter writer = producer.getBean(OutboxWriter.class);
        TransactionTemplate transaction = new TransactionTemplate(producer.getBean(PlatformTransactionManager.class));
        List<AppendedEvent> history = new ArrayList<>();
        for (int i = 0; i < HISTORY; i++) {
            String sale = "sale-" + UUID.randomUUID();
            history.add(transaction.execute(status -> writer.append(new EventDraft(
                    "sale", sale, "sale.recorded", 1, LIVE, Instant.parse("2026-11-09T07:30:00Z"), "system:probe",
                    Map.of("saleId", sale, "amount", "150.00", "currency", "ZMW")))));
        }
        return history;
    }

    /**
     * The producer probe on the real Kafka binder: outbox, relay (on a short
     * schedule of its own) and replay on, with the binder settings the README
     * asks of a producing service, and the {@link ReplayHooks} in front of its
     * StreamBridge.
     */
    private static ConfigurableApplicationContext startProducer() {
        List<String> properties = new ArrayList<>(List.of(FreshDatabase.in(postgres).properties()));
        properties.addAll(List.of(
                "spring.application.name=" + PRODUCER,
                "spring.flyway.locations=classpath:db/oriah-events",
                "spring.cloud.stream.kafka.binder.brokers=" + kafka.getBootstrapServers(),
                "spring.cloud.stream.default.contentType=application/json",
                "spring.cloud.stream.kafka.default.producer.sync=true",
                "spring.cloud.stream.kafka.binder.producer-properties.max.block.ms=10000",
                "spring.cloud.stream.kafka.binder.producer-properties.request.timeout.ms=5000",
                "spring.cloud.stream.kafka.binder.producer-properties.delivery.timeout.ms=10000",
                "oriah.events.producer=" + PRODUCER,
                "oriah.events.topics=" + LIVE,
                "oriah.events.outbox.enabled=true",
                "oriah.events.relay.enabled=true",
                "oriah.events.relay.delay=200",
                "oriah.events.replay.enabled=true",
                // Nothing for the replay-request listener: the module sets
                // its dead-lettering and poll settings (OutboxReplayBinding).
                "hook.topic=" + LIVE,
                "hook.append-during-replay=" + APPENDED_DURING_REPLAY,
                "hook.crash-after-continuation=" + CRASH_AFTER));
        return new SpringApplicationBuilder(ProbeApplication.class, ReplayHooks.Config.class)
                .web(WebApplicationType.NONE)
                .properties(properties.toArray(String[]::new))
                .run();
    }

    /**
     * The fact probe as a late consumer on the real Kafka binder, set up as a
     * consuming service with CatchUp is: the live topic and its replay topic,
     * both dead-lettered, and the producer settings for its replay request.
     */
    private static ConfigurableApplicationContext startConsumer() {
        List<String> properties = new ArrayList<>(List.of(FreshDatabase.in(postgres).properties()));
        properties.addAll(List.of(
                "spring.application.name=" + CONSUMER,
                "spring.flyway.locations=classpath:db/oriah-events,classpath:db/late-consumer",
                "probe.kind=fact",
                "spring.cloud.stream.kafka.binder.brokers=" + kafka.getBootstrapServers(),
                "spring.cloud.stream.default.contentType=application/json",
                "spring.cloud.stream.kafka.default.producer.sync=true",
                "spring.cloud.stream.kafka.binder.producer-properties.max.block.ms=10000",
                "spring.cloud.stream.kafka.binder.producer-properties.request.timeout.ms=5000",
                "spring.cloud.stream.kafka.binder.producer-properties.delivery.timeout.ms=10000",
                "oriah.events.inbox.enabled=true",
                "oriah.events.inbox.topics=" + LIVE,
                "oriah.events.inbox.catch-up.consumer=" + CONSUMER,
                "oriah.events.inbox.catch-up.producers=" + PRODUCER,
                "spring.cloud.function.definition=live;replay",
                "spring.cloud.stream.bindings.live-in-0.destination=" + LIVE,
                "spring.cloud.stream.bindings.live-in-0.group=" + CONSUMER,
                "spring.cloud.stream.bindings.replay-in-0.destination=" + REPLAY,
                "spring.cloud.stream.bindings.replay-in-0.group=" + CONSUMER,
                "spring.cloud.stream.default.consumer.max-attempts=3",
                "spring.cloud.stream.default.consumer.retryable-exceptions.java.lang.IllegalArgumentException=false",
                "spring.cloud.stream.kafka.default.consumer.enable-dlq=true",
                "spring.cloud.stream.kafka.bindings.live-in-0.consumer.dlq-name=" + LIVE + ".dlq." + CONSUMER,
                "spring.cloud.stream.kafka.bindings.replay-in-0.consumer.dlq-name=" + REPLAY + ".dlq." + CONSUMER));
        return new SpringApplicationBuilder(LateConsumerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties.toArray(String[]::new))
                .run();
    }

    /**
     * The replay-request listener's consumer properties exactly as the Kafka
     * binder bound them -- what it builds the listener's Kafka consumer from.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> boundListener(ConfigurableApplicationContext producer) {
        List<Binding<?>> bindings = producer.getBean(BindingService.class)
                .getConsumerBindings(OutboxReplayBinding.BINDING_NAME);
        assertThat(bindings).as("the replay-request listener's binding").hasSize(1);
        return (Map<String, Object>) bindings.get(0).getExtendedInfo().get("ExtendedConsumerProperties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static long cursor(JdbcTemplate producerDb) {
        return producerDb.queryForObject("SELECT last_outbox_id FROM outbox_relay_cursor WHERE relay = 'main'",
                Long.class);
    }

    private static Object completedAt(JdbcTemplate consumerDb) {
        List<Object> completed = consumerDb.queryForList(
                "SELECT completed_at FROM outbox_catch_up WHERE producer = ?", Object.class, PRODUCER);
        return completed.isEmpty() ? null : completed.get(0);
    }

    /** How many requests on the requests topic the producer's group has not yet committed past. */
    private static long requestsLeft() {
        try (Admin admin = admin()) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(PRODUCER)
                    .partitionsToOffsetAndMetadata().get(30, TimeUnit.SECONDS);
            long left = 0;
            for (Map.Entry<TopicPartition, Long> end : endOffsets(admin, ReplayRequest.TOPIC).entrySet()) {
                OffsetAndMetadata at = committed.get(end.getKey());
                left += end.getValue() - (at == null ? 0 : at.offset());
            }
            return left;
        } catch (Exception e) {
            throw new AssertionError("could not read the offsets of " + PRODUCER, e);
        }
    }

    private static Map<TopicPartition, Long> endOffsets(Admin admin, String topic) throws Exception {
        int partitions = admin.describeTopics(List.of(topic)).allTopicNames().get(30, TimeUnit.SECONDS)
                .get(topic).partitions().size();
        Map<TopicPartition, OffsetSpec> latest = new java.util.HashMap<>();
        for (int p = 0; p < partitions; p++) {
            latest.put(new TopicPartition(topic, p), OffsetSpec.latest());
        }
        Map<TopicPartition, Long> ends = new java.util.HashMap<>();
        admin.listOffsets(latest).all().get(30, TimeUnit.SECONDS)
                .forEach((partition, info) -> ends.put(partition, info.offset()));
        return ends;
    }

    /** Every record on the topic now, all partitions, by partition then offset: exactly the bytes on the broker. */
    private static List<ConsumerRecord<byte[], byte[]>> everything(String topic) {
        Map<TopicPartition, Long> ends;
        try (Admin admin = admin()) {
            ends = endOffsets(admin, topic);
        } catch (Exception e) {
            throw new AssertionError("could not read the end of " + topic, e);
        }
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> reader = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            reader.assign(ends.keySet());
            reader.seekToBeginning(ends.keySet());
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (ends.entrySet().stream().anyMatch(end -> reader.position(end.getKey()) < end.getValue())) {
                assertThat(System.nanoTime()).as("reading " + topic + " to its end").isLessThan(deadline);
                reader.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        records.sort(Comparator.<ConsumerRecord<byte[], byte[]>>comparingInt(ConsumerRecord::partition)
                .thenComparingLong(ConsumerRecord::offset));
        return records;
    }

    private static String key(ConsumerRecord<byte[], byte[]> record) {
        return record.key() == null ? null : new String(record.key(), StandardCharsets.UTF_8);
    }

    /** A number in a request's JSON body, or null when the field is not there. */
    private static Long field(ConsumerRecord<byte[], byte[]> record, String name) {
        Object value = EventJson.mapper().readValue(record.value(), Map.class).get(name);
        return value == null ? null : ((Number) value).longValue();
    }

    private static Long number(ConsumerRecord<byte[], byte[]> record, String name) {
        String value = header(record, name);
        return value == null ? null : Long.valueOf(value);
    }

    private static String header(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static <T> T await(String what, Supplier<T> read, Predicate<T> done) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        T value = read.get();
        while (!done.test(value)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(what + ": not reached within " + WAIT + "; last seen " + value);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            value = read.get();
        }
        return value;
    }

    private static Admin admin() {
        return Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
    }
}
