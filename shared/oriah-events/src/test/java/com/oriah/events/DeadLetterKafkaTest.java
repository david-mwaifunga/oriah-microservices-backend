package com.oriah.events;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.oriah.events.deadletter.DeadLetterProbeApplication;
import com.oriah.events.deadletter.Deliveries;
import com.oriah.events.deadletter.Deliveries.Delivery;
import com.oriah.events.deadletter.InboxProbe;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module's dead-letter handling on a real broker, through the real Kafka
 * binder: a consumer service ({@link DeadLetterProbeApplication}) with one
 * binding through the Inbox (the module's) and one of its own (not).
 *
 * <p>Why a real broker: what matters here is what Kafka ends up holding --
 * the record on the dead-letter topic, with which key, value and headers --
 * and where the consumer group's committed offset stands. The test binder
 * has neither dead-letter topics nor offsets.
 *
 * <p>The broker never creates a topic by itself
 * ({@code KAFKA_AUTO_CREATE_TOPICS_ENABLE=false}) and neither does the binder
 * ({@code auto-create-topics: false}); each test creates its own topics, so
 * it can leave a dead-letter topic missing and make the dead-letter publish
 * fail.
 */
@ExtendWith(OutputCaptureExtension.class)
class DeadLetterKafkaTest {

    private static final String GROUP = "probe";
    private static final String PLAIN_GROUP = "probe-plain";
    private static final int MAX_ATTEMPTS = 3;
    /** The Inbox binding's back-off max-interval: also the pause after a failed dead-letter publish. */
    private static final long PAUSE_MS = 3_000;
    private static final Duration WAIT = Duration.ofSeconds(120);

    private static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    // The compose image, with the listener fix OutboxRelayKafkaTest explains.
    private static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"))
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

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
    @DisplayName("a refusal (IllegalArgumentException) is delivered once, then lands on the dead-letter topic with "
            + "its original key, value and headers, plus the kafka_dlt-* headers")
    void refusalDeadLetteredOnce() {
        Topics topics = Topics.fresh();
        create(topics.inbox(), topics.inboxDeadLetters(), topics.plain(), topics.plainDeadLetters());
        try (ConfigurableApplicationContext service = startService(topics)) {
            Sent sent = send(topics.inbox(), "probe.undeclared", null);

            ConsumerRecord<byte[], byte[]> dead = readByKey(topics.inboxDeadLetters(), sent.key());
            awaitCommittedPast(GROUP, topics.inbox(), sent.offset());

            assertThat(deliveries(service, sent)).as("deliveries of the refused record").hasSize(1);
            assertThat(dead.key()).isEqualTo(sent.key().getBytes(StandardCharsets.UTF_8));
            assertThat(dead.value()).isEqualTo(sent.value());
            assertThat(header(dead, "event-id")).as("its own headers are kept").isEqualTo(sent.eventId());
            assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(topics.inbox());
            assertThat(ByteBuffer.wrap(dead.headers().lastHeader("kafka_dlt-original-partition").value()).getInt())
                    .isZero();
            assertThat(ByteBuffer.wrap(dead.headers().lastHeader("kafka_dlt-original-offset").value()).getLong())
                    .isEqualTo(sent.offset());
            assertThat(header(dead, "kafka_dlt-original-consumer-group")).isEqualTo(GROUP);
            assertThat(header(dead, "kafka_dlt-exception-fqcn")).isNotBlank();
            assertThat(header(dead, "kafka_dlt-exception-stacktrace"))
                    .contains(IllegalArgumentException.class.getName())
                    .contains("does not declare event type 'probe.undeclared'");
            assertThat(markers(service, sent)).as("nothing recorded for it").isZero();
        }
    }

    @Test
    @DisplayName("a failure that never clears is delivered max-attempts times in all (3 = 3 deliveries), then "
            + "dead-lettered")
    void failureDeadLetteredAfterMaxAttempts() {
        Topics topics = Topics.fresh();
        create(topics.inbox(), topics.inboxDeadLetters(), topics.plain(), topics.plainDeadLetters());
        try (ConfigurableApplicationContext service = startService(topics)) {
            Sent sent = send(topics.inbox(), "probe.happened", "fail-always");

            ConsumerRecord<byte[], byte[]> dead = readByKey(topics.inboxDeadLetters(), sent.key());
            awaitCommittedPast(GROUP, topics.inbox(), sent.offset());

            assertThat(deliveries(service, sent)).as("deliveries").hasSize(MAX_ATTEMPTS);
            assertThat(header(dead, "kafka_dlt-exception-stacktrace"))
                    .contains(IllegalStateException.class.getName())
                    .contains("the probe can never apply event " + sent.eventId());
            assertThat(service.getBean(InboxProbe.class).applied()).doesNotContain(sent.eventId());
            assertThat(markers(service, sent)).isZero();
        }
    }

    @Test
    @DisplayName("a failure that clears on the second try is applied once, and nothing is dead-lettered")
    void failureThatClears() {
        Topics topics = Topics.fresh();
        create(topics.inbox(), topics.inboxDeadLetters(), topics.plain(), topics.plainDeadLetters());
        try (ConfigurableApplicationContext service = startService(topics)) {
            Sent sent = send(topics.inbox(), "probe.happened", "fail-once");

            awaitCommittedPast(GROUP, topics.inbox(), sent.offset());

            assertThat(deliveries(service, sent)).as("deliveries").hasSize(2);
            assertThat(service.getBean(InboxProbe.class).applied()).containsOnlyOnce(sent.eventId());
            assertThat(markers(service, sent)).isEqualTo(1);
            // The dead-letter publish happens before the commit, so with the
            // offset committed past the record, an empty topic is final.
            assertThat(endOffset(topics.inboxDeadLetters())).as("records on the dead-letter topic").isZero();
        }
    }

    @Test
    @DisplayName("the dead-letter topic missing (a retryable failure): the record stays uncommitted and is "
            + "delivered again, an ERROR is logged and the pause is kept; once the topic exists the record lands "
            + "there and the offset moves past it")
    void deadLetterTopicMissingRetryable(CapturedOutput output) {
        Topics topics = Topics.fresh();
        create(topics.inbox(), topics.plain(), topics.plainDeadLetters());
        try (ConfigurableApplicationContext service = startService(topics)) {
            Sent sent = stall(service, topics, "probe.happened", "fail-always", MAX_ATTEMPTS + 1, output);

            create(topics.inboxDeadLetters());

            landsOnceTheTopicExists(topics, sent);
        }
    }

    @Test
    @DisplayName("the dead-letter topic missing (a refusal, never retried): the same, and no hot loop -- every "
            + "redelivery waits at least the pause")
    void deadLetterTopicMissingNotRetryable(CapturedOutput output) {
        Topics topics = Topics.fresh();
        create(topics.inbox(), topics.plain(), topics.plainDeadLetters());
        try (ConfigurableApplicationContext service = startService(topics)) {
            Sent sent = stall(service, topics, "probe.undeclared", null, 3, output);

            List<Delivery> deliveries = deliveries(service, sent);
            for (int i = 1; i < deliveries.size(); i++) {
                assertThat(Duration.between(deliveries.get(i - 1).at(), deliveries.get(i).at()))
                        .as("the wait before redelivery " + i)
                        .isGreaterThanOrEqualTo(Duration.ofMillis(PAUSE_MS));
            }

            create(topics.inboxDeadLetters());

            landsOnceTheTopicExists(topics, sent);
        }
    }

    @Test
    @DisplayName("a binding that is not the module's keeps the binder's own behaviour: the binder's retry "
            + "(max-attempts 3 is four deliveries there) and its own dead-letter headers, not the module's")
    void otherBindingKeepsTheBinders() {
        Topics topics = Topics.fresh();
        create(topics.inbox(), topics.inboxDeadLetters(), topics.plain(), topics.plainDeadLetters());
        try (ConfigurableApplicationContext service = startService(topics)) {
            Sent sent = send(topics.plain(), "probe.happened", null);

            ConsumerRecord<byte[], byte[]> dead = readByKey(topics.plainDeadLetters(), sent.key());
            awaitCommittedPast(PLAIN_GROUP, topics.plain(), sent.offset());

            assertThat(header(dead, "x-original-topic")).as("the binder's own header").isEqualTo(topics.plain());
            assertThat(dead.headers().lastHeader("kafka_dlt-original-topic")).as("the module's header").isNull();
            assertThat(deliveries(service, sent)).as("deliveries").hasSize(MAX_ATTEMPTS + 1);
        }
    }

    @Test
    @DisplayName("a binding's own Kafka setting wins over spring.cloud.stream.kafka.default.consumer in the real "
            + "binder exactly as in the module's startup check: default enable-dlq false, the binding's true -- the "
            + "service starts, and a refusal is dead-lettered")
    void bindingSettingWinsOverTheDefault() {
        Topics topics = Topics.fresh();
        create(topics.inbox(), topics.inboxDeadLetters(), topics.plain(), topics.plainDeadLetters());
        // Had the check read the default over the binding, the service would
        // not start; had the binder, it would build the binding with no
        // dead-letter topic, the module's hook would refuse it, and the
        // binding would never read a record.
        try (ConfigurableApplicationContext service = startService(topics,
                "spring.cloud.stream.kafka.default.consumer.enable-dlq=false",
                "spring.cloud.stream.kafka.bindings.inboxEvents-in-0.consumer.enable-dlq=true")) {
            Sent sent = send(topics.inbox(), "probe.undeclared", null);

            ConsumerRecord<byte[], byte[]> dead = readByKey(topics.inboxDeadLetters(), sent.key());
            awaitCommittedPast(GROUP, topics.inbox(), sent.offset());

            assertThat(deliveries(service, sent)).as("deliveries of the refused record").hasSize(1);
            assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(topics.inbox());
        }
    }

    /**
     * Sends one record that goes through fine (so the group has a committed
     * offset), then the failing one, and waits until it has been delivered
     * this many times while its dead-letter topic is missing. Checks it was
     * never committed, that the module logged its ERROR, and that the next
     * delivery came no sooner than the pause after it.
     */
    private Sent stall(ConfigurableApplicationContext service, Topics topics, String eventType, String behaviour,
                       int deliveries, CapturedOutput output) {
        Sent fine = send(topics.inbox(), "probe.happened", null);
        awaitCommittedPast(GROUP, topics.inbox(), fine.offset());
        Sent sent = send(topics.inbox(), eventType, behaviour);

        List<Delivery> seen = await("delivery " + deliveries + " of the stalled record",
                () -> deliveries(service, sent), list -> list.size() >= deliveries);

        assertThat(committed(GROUP, topics.inbox())).as("the group's committed offset: the next record to read")
                .isEqualTo(sent.offset());
        Instant errorAt = firstError(output, topics.inbox(), sent.offset());
        Delivery next = seen.stream().filter(delivery -> delivery.at().isAfter(errorAt)).findFirst()
                .orElseThrow(() -> new AssertionError("no delivery after the ERROR at " + errorAt));
        // The log line's time has whole milliseconds; the delivery's is finer.
        assertThat(Duration.between(errorAt, next.at())).as("from the ERROR to the next delivery")
                .isGreaterThanOrEqualTo(Duration.ofMillis(PAUSE_MS - 1));
        return sent;
    }

    private void landsOnceTheTopicExists(Topics topics, Sent sent) {
        ConsumerRecord<byte[], byte[]> dead = readByKey(topics.inboxDeadLetters(), sent.key());
        awaitCommittedPast(GROUP, topics.inbox(), sent.offset());
        assertThat(dead.key()).isEqualTo(sent.key().getBytes(StandardCharsets.UTF_8));
        assertThat(dead.value()).isEqualTo(sent.value());
        assertThat(header(dead, "kafka_dlt-original-offset")).isNotNull();
    }

    /** The time of the module's first ERROR for this record, read from the log line itself. */
    private static Instant firstError(CapturedOutput output, String topic, long offset) {
        Matcher line = Pattern.compile("(?m)^(\\S+)\\s+ERROR .*Dead-lettering failed for binding inboxEvents-in-0: "
                + "topic " + Pattern.quote(topic) + ", partition 0, offset " + offset + " ").matcher(output.getAll());
        assertThat(line.find()).as("the module's ERROR for the record, in:%n%s", output.getAll()).isTrue();
        return OffsetDateTime.parse(line.group(1)).toInstant();
    }

    /** One test's topics; every test has its own, so none sees another's records. */
    private record Topics(String inbox, String inboxDeadLetters, String plain, String plainDeadLetters) {

        static Topics fresh() {
            String id = "dl-" + UUID.randomUUID().toString().substring(0, 8);
            return new Topics(id + ".inbox", id + ".inbox.dlq." + GROUP, id + ".plain", id + ".plain.dlq");
        }
    }

    /** A record the test sent: its key (the aggregate id), event id, body and where Kafka put it. */
    private record Sent(String key, String eventId, byte[] value, long offset) {
    }

    /**
     * The probe service on the real Kafka binder, as a consuming service
     * sets it up: the Inbox on its topic, dead-lettering on (the binder-wide
     * default for enable-dlq, a dlq-name per binding), refusals marked not
     * retryable. A database of its own for the Inbox's records. {@code overrides}
     * come last, so each replaces a setting above of the same name.
     */
    private static ConfigurableApplicationContext startService(Topics topics, String... overrides) {
        List<String> properties = new ArrayList<>(List.of(FreshDatabase.in(postgres).properties()));
        String inbox = "spring.cloud.stream.bindings.inboxEvents-in-0.";
        String plain = "spring.cloud.stream.bindings.plainEvents-in-0.";
        properties.addAll(List.of(
                "spring.application.name=dead-letter-probe",
                "spring.flyway.locations=classpath:db/oriah-events",
                "spring.cloud.function.definition=inboxEvents;plainEvents",
                "spring.cloud.stream.kafka.binder.brokers=" + kafka.getBootstrapServers(),
                "spring.cloud.stream.kafka.binder.auto-create-topics=false",
                "oriah.events.inbox.enabled=true",
                "oriah.events.inbox.topics=" + topics.inbox(),
                // From the binder-wide default on purpose: the module's check
                // and the binder must read it the same way.
                "spring.cloud.stream.kafka.default.consumer.enable-dlq=true",
                inbox + "destination=" + topics.inbox(),
                inbox + "group=" + GROUP,
                inbox + "consumer.max-attempts=" + MAX_ATTEMPTS,
                inbox + "consumer.back-off-initial-interval=100",
                inbox + "consumer.back-off-multiplier=2.0",
                inbox + "consumer.back-off-max-interval=" + PAUSE_MS,
                inbox + "consumer.retryable-exceptions.java.lang.IllegalArgumentException=false",
                "spring.cloud.stream.kafka.bindings.inboxEvents-in-0.consumer.dlq-name=" + topics.inboxDeadLetters(),
                plain + "destination=" + topics.plain(),
                plain + "group=" + PLAIN_GROUP,
                plain + "consumer.max-attempts=" + MAX_ATTEMPTS,
                plain + "consumer.back-off-initial-interval=100",
                plain + "consumer.back-off-max-interval=100",
                "spring.cloud.stream.kafka.bindings.plainEvents-in-0.consumer.dlq-name=" + topics.plainDeadLetters()));
        properties.addAll(List.of(overrides));
        return new SpringApplicationBuilder(DeadLetterProbeApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties.toArray(String[]::new))
                .run();
    }

    /** Sends one probe event as a producer's relay would: keyed by the aggregate id, the envelope as headers. */
    private static Sent send(String topic, String eventType, String behaviour) {
        String key = "probe-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        byte[] value = ("{\"eventId\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, key.getBytes(StandardCharsets.UTF_8),
                value);
        Map<String, String> headers = new java.util.LinkedHashMap<>(Map.of(
                "event-id", eventId, "event-type", eventType, "aggregate-type", "probe", "aggregate-id", key,
                "seq", "1", "schema-version", "1"));
        if (behaviour != null) {
            headers.put("probe-behaviour", behaviour);
        }
        headers.forEach((name, text) -> record.headers().add(name, text.getBytes(StandardCharsets.UTF_8)));
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            long offset = producer.send(record).get(30, TimeUnit.SECONDS).offset();
            return new Sent(key, eventId, value, offset);
        } catch (Exception e) {
            throw new AssertionError("could not send to " + topic, e);
        }
    }

    private static List<Delivery> deliveries(ConfigurableApplicationContext service, Sent sent) {
        return service.getBean(Deliveries.class).of(sent.key());
    }

    private static int markers(ConfigurableApplicationContext service, Sent sent) {
        return service.getBean(JdbcTemplate.class).queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = 'dead-letter-probe' AND event_id = ?::uuid",
                Integer.class, sent.eventId());
    }

    /** Reads the topic from the start until the record with this key arrives. */
    private static ConsumerRecord<byte[], byte[]> readByKey(String topic, String key) {
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(new String(record.key(), StandardCharsets.UTF_8))) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("the record " + key + " did not arrive on " + topic + " within " + WAIT);
    }

    private static void create(String... topics) {
        try (Admin admin = admin()) {
            admin.createTopics(java.util.Arrays.stream(topics).map(name -> new NewTopic(name, 1, (short) 1)).toList())
                    .all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("could not create " + List.of(topics), e);
        }
    }

    /** The group's committed offset on partition 0 -- the next record it will read -- or null when none. */
    private static Long committed(String group, String topic) {
        try (Admin admin = admin()) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(30, TimeUnit.SECONDS).get(new TopicPartition(topic, 0));
            return committed == null ? null : committed.offset();
        } catch (Exception e) {
            throw new AssertionError("could not read the offsets of " + group, e);
        }
    }

    private static void awaitCommittedPast(String group, String topic, long offset) {
        await("the committed offset of " + group + " past " + offset, () -> committed(group, topic),
                committed -> committed != null && committed > offset);
    }

    private static long endOffset(String topic) {
        TopicPartition partition = new TopicPartition(topic, 0);
        try (Admin admin = admin()) {
            return admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get(30, TimeUnit.SECONDS)
                    .get(partition).offset();
        } catch (Exception e) {
            throw new AssertionError("could not read the end of " + topic, e);
        }
    }

    private static <T> T await(String what, Supplier<T> read, java.util.function.Predicate<T> done) {
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

    private static String header(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
