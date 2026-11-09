package com.oriah.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MimeType;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.oriah.events.probe.ProbeApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay against a real Kafka broker, through the real Kafka binder that
 * every producing service runs.
 *
 * <p>Why a real broker here when every other test uses the test binder: the
 * test binder keeps messages in memory and never runs Kafka's serializers, so
 * it cannot tell whether a record Kafka would actually accept. The Kafka
 * binder sends every key through a byte-array serializer; a key of any other
 * type makes every send fail, the relay stops at the first row every round,
 * and nothing is ever published -- while every test-binder test stays green.
 *
 * <p>The same goes for partitions: the test binder has none, so only a real
 * broker shows where Kafka puts a replay on a replay topic of several.
 *
 * <p>The service is started with exactly the binder defaults the module asks
 * each producing service to set (sync sends, JSON content type) and nothing
 * else, so a setting a service forgets cannot be quietly covered here.
 */
class OutboxRelayKafkaTest {

    private static final String TOPIC = "oriah.probe";
    private static final String OUTAGE_TOPIC = "oriah.probe-outage";
    /**
     * The replay test's rows go live here. Every test has its own topic: a
     * test publishes whatever earlier ones left unsent, and the first test
     * reads its topic from the start.
     */
    private static final String REPLAYED_TOPIC = "oriah.probe-replayed";

    /** The producer timeouts the README asks every producing service to set, in milliseconds. */
    private static final int MAX_BLOCK_MS = 10_000;
    private static final int REQUEST_TIMEOUT_MS = 5_000;
    private static final int DELIVERY_TIMEOUT_MS = 10_000;

    /**
     * How long a round may wait for a stopped broker: Kafka's own waits (for
     * the topic's metadata, then for the whole send) plus the 5 seconds the
     * Kafka binder adds to the send wait so that Kafka's error, not its own,
     * is what the relay sees. Unset, Kafka waits 60 seconds for metadata and
     * up to 120 for a send, holding the relay's round all that time.
     */
    private static final Duration SEND_FAILS_WITHIN =
            Duration.ofMillis(MAX_BLOCK_MS + DELIVERY_TIMEOUT_MS + 5_000);

    private static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    // The image the compose stack runs. Kafka 3.9 also advertises its
    // controller listener, and Testcontainers binds every listener to
    // 0.0.0.0, which Kafka refuses to advertise -- the broker exits at
    // start. Listeners with no host (as in the compose file) bind to every
    // interface and advertise the container's own name instead.
    //
    // On a fixed host port, so the broker can be stopped and started again
    // at the same address: clients reconnect to the address it advertised
    // when it first started, and a restarted container would otherwise be
    // given a new random port.
    private static final KafkaContainer kafka = onFixedPort(
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"))
                    .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094"),
            freePort());

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
    @DisplayName("on a real broker the record's key is the aggregate id, its body is the stored payload, "
            + "and every envelope header arrives")
    void keyBodyAndHeadersOnARealBroker() {
        try (ConfigurableApplicationContext service = startService()) {
            OutboxWriter writer = service.getBean(OutboxWriter.class);
            OutboxRelay relay = service.getBean(OutboxRelay.class);
            JdbcTemplate jdbc = service.getBean(JdbcTemplate.class);
            TransactionTemplate transaction =
                    new TransactionTemplate(service.getBean(PlatformTransactionManager.class));
            while (relay.relayBatch() > 0) {
                // whatever an earlier test left unsent goes first, so the round below is ours alone
            }
            String order = UUID.randomUUID().toString();
            AppendedEvent appended = transaction.execute(status -> writer.append(new EventDraft(
                    "order", order, "order.created", 1, TOPIC,
                    Instant.parse("2026-11-09T07:30:00Z"), "user:5b1c-subject",
                    Map.of("orderId", order))));
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT payload::text AS payload, recorded_at FROM outbox WHERE outbox_id = ?",
                    appended.outboxId());

            assertThat(relay.relayBatch()).as("rows the broker accepted this round").isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT last_outbox_id FROM outbox_relay_cursor WHERE relay = 'main'", Long.class))
                    .isEqualTo(appended.outboxId());

            ConsumerRecord<byte[], byte[]> record = readOne(TOPIC);
            // Kafka partitions by the key: every event of one order on one
            // partition, read in the order it was written.
            assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo(order);
            assertThat(new String(record.value(), StandardCharsets.UTF_8)).isEqualTo((String) row.get("payload"));
            assertThat(header(record, "event-id")).isEqualTo(appended.eventId().toString());
            assertThat(header(record, "event-type")).isEqualTo("order.created");
            assertThat(header(record, "aggregate-type")).isEqualTo("order");
            assertThat(header(record, "aggregate-id")).isEqualTo(order);
            assertThat(header(record, "seq")).isEqualTo("1");
            assertThat(header(record, "outbox-id")).isEqualTo(Long.toString(appended.outboxId()));
            assertThat(header(record, "occurred-at")).isEqualTo("2026-11-09T07:30:00Z");
            assertThat(header(record, "recorded-at"))
                    .isEqualTo(((java.sql.Timestamp) row.get("recorded_at")).toInstant().toString());
            assertThat(header(record, "actor")).isEqualTo("user:5b1c-subject");
            assertThat(header(record, "schema-version")).isEqualTo("1");
            assertThat(header(record, "producer")).isEqualTo("probe");
            assertThat(MimeType.valueOf(header(record, "contentType")))
                    .isEqualTo(MimeType.valueOf("application/json"));
        }
    }

    @Test
    @DisplayName("broker stopped: the send fails within the configured timeout and the cursor does not move; "
            + "broker started again: the relay's next round delivers the row")
    void brokerStoppedThenStarted() {
        try (ConfigurableApplicationContext service = startService()) {
            OutboxWriter writer = service.getBean(OutboxWriter.class);
            OutboxRelay relay = service.getBean(OutboxRelay.class);
            JdbcTemplate jdbc = service.getBean(JdbcTemplate.class);
            TransactionTemplate transaction =
                    new TransactionTemplate(service.getBean(PlatformTransactionManager.class));
            String order = UUID.randomUUID().toString();
            // One row through first: the backlog is out, and the producer has
            // met the topic, so the round below has exactly one row to send.
            AppendedEvent first = transaction.execute(status -> writer.append(outageDraft(order)));
            while (relay.relayBatch() > 0) {
                // publish the backlog
            }
            assertThat(cursor(jdbc)).isEqualTo(first.outboxId());
            AppendedEvent stranded = transaction.execute(status -> writer.append(outageDraft(order)));

            stopBroker();
            try {
                long started = System.nanoTime();
                int sent = relay.relayBatch();
                Duration waited = Duration.ofNanos(System.nanoTime() - started);

                assertThat(sent).as("rows the stopped broker accepted").isZero();
                assertThat(waited).as("how long the round waited for the stopped broker")
                        .isLessThan(SEND_FAILS_WITHIN);
                assertThat(cursor(jdbc)).as("the cursor stays on the last row the broker took")
                        .isEqualTo(first.outboxId());
            } finally {
                startBroker();
            }

            // The relay's next rounds, as its schedule would run them, until the broker answers again.
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            int sent = 0;
            while (sent == 0 && System.nanoTime() < deadline) {
                sent = relay.relayBatch();
            }
            assertThat(sent).as("the stranded row, once the broker is back").isEqualTo(1);
            assertThat(cursor(jdbc)).isEqualTo(stranded.outboxId());
            ConsumerRecord<byte[], byte[]> record = readEvent(OUTAGE_TOPIC, stranded.eventId());
            assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo(order);
            assertThat(header(record, "outbox-id")).isEqualTo(Long.toString(stranded.outboxId()));
            assertThat(header(record, "seq")).isEqualTo(Long.toString(stranded.seq()));
        }
    }

    @Test
    @DisplayName("a replay keeps its order on a replay topic of several partitions: every record and the marker "
            + "land on one partition, in outbox order")
    void replayStaysInOrderOnSeveralPartitions() throws Exception {
        // The consumer's binder creates its replay topic when the replay
        // binding starts, with as many partitions as that binding has
        // consumer threads (concurrency) times instances, or its
        // minPartitionCount. Three here, as a finance service running three
        // threads would get.
        String consumer = "kafka-probe-" + UUID.randomUUID().toString().substring(0, 8);
        String replayTopic = "oriah.replay." + consumer;
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(replayTopic, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        try (ConfigurableApplicationContext service = startService(
                "spring.application.name=probe", "oriah.events.replay.enabled=true")) {
            OutboxWriter writer = service.getBean(OutboxWriter.class);
            TransactionTemplate transaction =
                    new TransactionTemplate(service.getBean(PlatformTransactionManager.class));
            // Two events for each of three orders, the orders chosen so that
            // keyed by order their records would go to all three partitions.
            List<String> orders = onePerPartition(3);
            List<AppendedEvent> appended = new ArrayList<>();
            for (int round = 0; round < 2; round++) {
                for (String order : orders) {
                    appended.add(transaction.execute(status -> writer.append(new EventDraft(
                            "order", order, "order.created", 1, REPLAYED_TOPIC, Instant.parse("2026-11-09T07:30:00Z"),
                            "system:probe", Map.of("orderId", order)))));
                }
            }
            UUID requestId = UUID.randomUUID();

            service.getBean(OutboxReplayer.class).handle(replayRequest(requestId, consumer,
                    appended.get(0).outboxId()));

            List<ConsumerRecord<byte[], byte[]>> replay = readAll(replayTopic, appended.size() + 1);
            assertThat(replay).extracting(ConsumerRecord::partition).as("the partition of every record")
                    .containsOnly(replay.get(0).partition());
            replay.sort(Comparator.comparingLong(ConsumerRecord::offset));
            List<String> outboxIds = new ArrayList<>();
            appended.forEach(event -> outboxIds.add(Long.toString(event.outboxId())));
            outboxIds.add(null);
            assertThat(replay).extracting(record -> header(record, "outbox-id")).as("in outbox order, marker last")
                    .containsExactlyElementsOf(outboxIds);
            assertThat(header(replay.get(replay.size() - 1), "event-type")).isEqualTo("outbox.replay.completed");
            assertThat(replay).extracting(record -> new String(record.key(), StandardCharsets.UTF_8))
                    .as("the key of every record").containsOnly(requestId.toString());
        }
    }

    /**
     * Aggregate ids that Kafka would put on different partitions of a topic
     * with this many, one for each: Kafka places a keyed record by the
     * murmur2 hash of its key, modulo the partition count.
     */
    private static List<String> onePerPartition(int partitions) {
        String[] chosen = new String[partitions];
        int found = 0;
        while (found < partitions) {
            String id = UUID.randomUUID().toString();
            int partition = Utils.toPositive(Utils.murmur2(id.getBytes(StandardCharsets.UTF_8))) % partitions;
            if (chosen[partition] == null) {
                chosen[partition] = id;
                found++;
            }
        }
        return List.of(chosen);
    }

    /** A replay request as a consumer's CatchUp sends it. */
    private static Message<byte[]> replayRequest(UUID requestId, String consumer, long fromOutboxId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId.toString());
        body.put("producer", "probe");
        body.put("consumer", consumer);
        body.put("fromOutboxId", fromOutboxId);
        return MessageBuilder.withPayload(EventJson.write(body).getBytes(StandardCharsets.UTF_8)).build();
    }

    private static Admin admin() {
        return Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000));
    }

    private static EventDraft outageDraft(String order) {
        return new EventDraft("order", order, "order.created", 1, OUTAGE_TOPIC,
                Instant.parse("2026-11-09T07:30:00Z"), "system:probe", Map.of("orderId", order));
    }

    private static long cursor(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT last_outbox_id FROM outbox_relay_cursor WHERE relay = 'main'", Long.class);
    }

    /**
     * docker stop: the broker goes away and its port closes; the container,
     * its data and its port binding are kept. The container's first process
     * is a shell that does not pass the stop signal on, so docker kills the
     * broker when the wait runs out -- a crash, the harsher case, from which
     * Kafka recovers its log on the next start.
     */
    private static void stopBroker() {
        kafka.getDockerClient().stopContainerCmd(kafka.getContainerId()).withTimeout(5).exec();
    }

    /**
     * docker start: the same broker, with the same data, on the same host
     * port -- and waits until it answers, so whatever runs next meets a
     * working broker.
     */
    private static void startBroker() {
        kafka.getDockerClient().startContainerCmd(kafka.getContainerId()).exec();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000))) {
            while (true) {
                try {
                    admin.describeTopics(List.of(OUTAGE_TOPIC)).allTopicNames().get(10, TimeUnit.SECONDS);
                    return;
                } catch (ExecutionException | TimeoutException e) {
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("the broker did not come back within 120 seconds", e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }
    }

    private static KafkaContainer onFixedPort(KafkaContainer container, int hostPort) {
        container.setPortBindings(new ArrayList<>(List.of(hostPort + ":9092")));
        return container;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The probe service on the real Kafka binder: no test binder imported,
     * so Kafka is the only binder it has.
     */
    private static ConfigurableApplicationContext startService(String... extra) {
        return new SpringApplicationBuilder(ProbeApplication.class)
                .web(WebApplicationType.NONE)
                .properties(extra)
                .properties(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.flyway.locations=classpath:db/oriah-events",
                        "spring.cloud.stream.kafka.binder.brokers=" + kafka.getBootstrapServers(),
                        // The binder defaults the README asks every producing service to set.
                        "spring.cloud.stream.kafka.default.producer.sync=true",
                        "spring.cloud.stream.default.contentType=application/json",
                        "spring.cloud.stream.kafka.binder.producer-properties.max.block.ms=" + MAX_BLOCK_MS,
                        "spring.cloud.stream.kafka.binder.producer-properties.request.timeout.ms=" + REQUEST_TIMEOUT_MS,
                        "spring.cloud.stream.kafka.binder.producer-properties.delivery.timeout.ms=" + DELIVERY_TIMEOUT_MS,
                        "oriah.events.producer=probe",
                        "oriah.events.topics=" + TOPIC + "," + OUTAGE_TOPIC + "," + REPLAYED_TOPIC,
                        "oriah.events.outbox.enabled=true",
                        "oriah.events.relay.enabled=true",
                        "oriah.events.relay.delay=3600000")
                .run();
    }

    /** Reads the topic from the start with a plain Kafka consumer: exactly the bytes on the broker. */
    private static ConsumerRecord<byte[], byte[]> readOne(String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "probe-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    return record;
                }
            }
        }
        throw new AssertionError("nothing arrived on " + topic + " within 30 seconds");
    }

    /** Reads the topic, every partition, from the start until this many records have arrived. */
    private static List<ConsumerRecord<byte[], byte[]>> readAll(String topic, int count) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "probe-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (records.size() < count && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        assertThat(records).as("records on " + topic + " within 60 seconds").hasSize(count);
        return records;
    }

    /** Reads the topic from the start until the record of this event arrives. */
    private static ConsumerRecord<byte[], byte[]> readEvent(String topic, UUID eventId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "probe-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    if (eventId.toString().equals(header(record, "event-id"))) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("event " + eventId + " did not arrive on " + topic + " within 60 seconds");
    }

    private static String header(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
