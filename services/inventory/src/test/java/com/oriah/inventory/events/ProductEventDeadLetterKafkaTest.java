package com.oriah.inventory.events;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.integration.channel.AbstractMessageChannel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.oriah.events.EventHeaders;
import com.oriah.inventory.InventoryServiceApplication;
import com.oriah.inventory.repository.ProductRefRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to a product event inventory cannot apply, on a real broker,
 * through the real Kafka binder and inventory's own application.yaml.
 *
 * <p>Why a real broker: catalog.product is read through the Inbox, so on Kafka
 * the oriah-events module handles a failing event, in place of the binder's
 * own retry -- and the test binder runs neither. What matters is what Kafka
 * ends up holding: how many times the event was delivered, the record on
 * catalog.product.dlq.inventory, and the consumer group's committed offset
 * (moved past the event only once the dead-letter topic has it).
 *
 * <p>Both topics are created first, with one partition each, so "the
 * committed offset" is partition 0's.
 *
 * <p>Why the service is started by hand and not as a {@code @SpringBootTest}:
 * Spring's test framework pauses a cached context whenever a test switches to
 * another one, and resumes it later. A test-binder context does not come back
 * whole from that -- its binding loses its error handling -- so a context of
 * this class's own in that cache, run between the other event tests, would
 * break the ones after it. Started by hand, it never enters the cache.
 */
class ProductEventDeadLetterKafkaTest {

    private static final String TOPIC = "catalog.product";
    private static final String DEAD_LETTERS = "catalog.product.dlq.inventory";
    private static final String GROUP = "inventory";
    private static final Duration WAIT = Duration.ofSeconds(120);
    /** The envelope catalog's relay sends with every product event, each as a Kafka header. */
    private static final List<String> ENVELOPE = List.of(EventHeaders.EVENT_ID, EventHeaders.EVENT_TYPE,
            EventHeaders.AGGREGATE_TYPE, EventHeaders.AGGREGATE_ID, EventHeaders.SEQ, EventHeaders.OUTBOX_ID,
            EventHeaders.OCCURRED_AT, EventHeaders.RECORDED_AT, EventHeaders.ACTOR, EventHeaders.SCHEMA_VERSION,
            EventHeaders.PRODUCER);

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    // The compose stack's image. Its default listeners advertise an address
    // the host cannot reach; these are the module's Kafka tests' fix.
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"))
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");

    private static ConfigurableApplicationContext inventory;

    @BeforeAll
    static void startInventory() {
        POSTGRES.start();
        KAFKA.start();
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(DEAD_LETTERS, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("could not create " + TOPIC + " and " + DEAD_LETTERS, e);
        }
        // The inventory service as it starts in production, on the real Kafka
        // binder, this test's broker and this test's database. Passed as
        // command-line arguments, which win over application.yaml: it names
        // localhost:9092 as the broker, and would override a default set with
        // properties(...).
        inventory = new SpringApplicationBuilder(InventoryServiceApplication.class, NoTokens.class)
                .initializers(context -> context.getBeanFactory()
                        .registerSingleton("leaveOutTestComponents", new LeaveOutTestComponents()))
                .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.cloud.stream.kafka.binder.brokers=" + KAFKA.getBootstrapServers(),
                        "--spring.cloud.config.enabled=false",
                        "--eureka.client.enabled=false",
                        "--server.port=0");
    }

    /** The service first, so its consumer leaves the group before the broker goes. */
    @AfterAll
    static void stopInventory() {
        if (inventory != null) {
            inventory.close();
        }
        KAFKA.stop();
        POSTGRES.stop();
    }

    private ProductRefRepository productRefs;
    private JdbcTemplate jdbc;
    /** The binding's own channel: every delivery Kafka makes reaches the consumer through it. */
    private AbstractMessageChannel binding;
    private final List<ChannelInterceptor> interceptors = new ArrayList<>();
    private final List<String> deliveries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void countDeliveries() {
        productRefs = inventory.getBean(ProductRefRepository.class);
        jdbc = inventory.getBean(JdbcTemplate.class);
        binding = inventory.getBean("productEvents-in-0", AbstractMessageChannel.class);
        intercept(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                deliveries.add(text(message.getHeaders().get(EventHeaders.EVENT_ID)));
                return message;
            }
        });
    }

    @AfterEach
    void stopIntercepting() {
        interceptors.forEach(binding::removeInterceptor);
        interceptors.clear();
    }

    /**
     * The seq that orders snapshots is counted per product, so a body about
     * another product than the envelope names is refused by the consumer's own
     * check (an IllegalArgumentException) -- which runs inside the Inbox's
     * transaction, after it has written the "applied" marker, so the refusal
     * rolls the marker back with it. A refusal fails the same way every time,
     * so it is not tried again.
     */
    @Test
    @DisplayName("a snapshot of one product in an envelope naming another is delivered once, then lands on "
            + "catalog.product.dlq.inventory, with nothing recorded")
    void mismatchedSnapshotDeadLetteredOnFirstDelivery() {
        UUID envelopeProduct = UUID.randomUUID();
        UUID otherProduct = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        Sent sent = send(Relayed.productEvent("product.created", envelopeProduct, 1,
                snapshot(otherProduct, variantId)).build());

        ConsumerRecord<byte[], byte[]> dead = deadLettered(sent);

        assertThat(deliveries(sent)).as("deliveries of the refused event").isEqualTo(1);
        assertThat(header(dead, "kafka_dlt-exception-stacktrace"))
                .contains(IllegalArgumentException.class.getName())
                .contains("snapshot of product " + otherProduct + " arrived in an envelope for product "
                        + envelopeProduct);
        assertThat(markers(sent)).as("no marker survives the refusal").isZero();
        assertThat(seqs(envelopeProduct)).isZero();
        assertThat(productRefs.findById(variantId)).isEmpty();
    }

    @Test
    @DisplayName("a product event whose aggregate-type is not \"product\" is delivered once, then dead-lettered, "
            + "with nothing recorded")
    void otherAggregateTypeDeadLetteredOnFirstDelivery() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        Sent sent = send(Relayed.productEvent("product.created", productId, 1, snapshot(productId, variantId))
                .setHeader(EventHeaders.AGGREGATE_TYPE, "Product").build());

        ConsumerRecord<byte[], byte[]> dead = deadLettered(sent);

        assertThat(deliveries(sent)).as("deliveries of the refused event").isEqualTo(1);
        assertThat(header(dead, "kafka_dlt-exception-stacktrace"))
                .contains(IllegalArgumentException.class.getName())
                .contains("has aggregate-type 'Product'");
        assertThat(markers(sent)).isZero();
        assertThat(productRefs.findById(variantId)).isEmpty();
    }

    /**
     * A body Jackson cannot read fails the same way on every delivery, so
     * application.yaml lists JsonProcessingException as not retryable. The
     * consumer wraps it in an UncheckedIOException, and the binder wraps that
     * again; the module's error handler looks through those wrappers to the
     * cause, so it is still dead-lettered on its first delivery.
     */
    @Test
    @DisplayName("a body that cannot be read is delivered once, then dead-lettered, with nothing recorded")
    void unreadableBodyDeadLetteredOnFirstDelivery() {
        UUID productId = UUID.randomUUID();
        Sent sent = send(Relayed.productEvent("product.created", productId, 1,
                "{\"id\":\"" + productId + "\",\"title\":").build());

        ConsumerRecord<byte[], byte[]> dead = deadLettered(sent);

        assertThat(deliveries(sent)).as("deliveries of the unreadable event").isEqualTo(1);
        assertThat(header(dead, "kafka_dlt-exception-stacktrace"))
                .contains(UncheckedIOException.class.getName())
                .contains("product event body does not match ProductCreatedEvent");
        assertThat(markers(sent)).isZero();
        assertThat(seqs(productId)).isZero();
    }

    @Test
    @DisplayName("a failure that never clears is delivered three times in all (max-attempts 3), then dead-lettered")
    void failureThatNeverClearsDeliveredThreeTimes() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        Message<byte[]> event = Relayed.productEvent("product.created", productId, 1,
                snapshot(productId, variantId)).build();
        String eventId = text(event.getHeaders().get(EventHeaders.EVENT_ID));
        // Stands in for the database staying away: every delivery fails on
        // its way into the consumer.
        intercept(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (eventId.equals(text(message.getHeaders().get(EventHeaders.EVENT_ID)))) {
                    throw new TransientDataAccessResourceException("the database is away");
                }
                return message;
            }
        });

        Sent sent = send(event);
        ConsumerRecord<byte[], byte[]> dead = deadLettered(sent);

        assertThat(deliveries(sent)).as("deliveries").isEqualTo(3);
        assertThat(header(dead, "kafka_dlt-exception-stacktrace"))
                .contains(TransientDataAccessResourceException.class.getName());
        assertThat(markers(sent)).isZero();
        assertThat(productRefs.findById(variantId)).isEmpty();
    }

    /**
     * Waits for the event on the dead-letter topic and for the group's
     * committed offset to move past it, then checks it is the event as sent:
     * the same key and body, its own headers kept, and where it came from.
     */
    private ConsumerRecord<byte[], byte[]> deadLettered(Sent sent) {
        ConsumerRecord<byte[], byte[]> dead = readByKey(DEAD_LETTERS, sent.key());
        await("the committed offset of " + GROUP + " past " + sent.offset(), ProductEventDeadLetterKafkaTest::committed,
                committed -> committed != null && committed > sent.offset());
        assertThat(dead.key()).isEqualTo(sent.key().getBytes(StandardCharsets.UTF_8));
        assertThat(dead.value()).isEqualTo(sent.value());
        assertThat(header(dead, EventHeaders.EVENT_ID)).as("its own headers are kept").isEqualTo(sent.eventId());
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
        assertThat(header(dead, "kafka_dlt-original-consumer-group")).isEqualTo(GROUP);
        return dead;
    }

    /** One event as sent: its key (the product id), event id, body and the offset Kafka gave it. */
    private record Sent(String key, String eventId, byte[] value, long offset) {
    }

    /** Sends the event as catalog's relay does: keyed by the product id, the envelope as headers in UTF-8. */
    private static Sent send(Message<byte[]> event) {
        String key = text(event.getHeaders().get(EventHeaders.AGGREGATE_ID));
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(TOPIC, key.getBytes(StandardCharsets.UTF_8),
                event.getPayload());
        for (String name : ENVELOPE) {
            Object value = event.getHeaders().get(name);
            if (value != null) {
                record.headers().add(name, text(value).getBytes(StandardCharsets.UTF_8));
            }
        }
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            long offset = producer.send(record).get(30, TimeUnit.SECONDS).offset();
            return new Sent(key, text(event.getHeaders().get(EventHeaders.EVENT_ID)), event.getPayload(), offset);
        } catch (Exception e) {
            throw new AssertionError("could not send to " + TOPIC, e);
        }
    }

    /** A product.created body in catalog's shape, with one variant. */
    private static String snapshot(UUID productId, UUID variantId) {
        return """
                {"id":"%s","title":"Aurelia","tags":["rings"],"productType":"JEWELLERY","status":"ACTIVE",
                 "variants":[{"variantId":"%s","sku":"AUR-M","title":"Aurelia - M","unitPrice":4500.00,
                              "currency":"ZMW","active":true}]}""".formatted(productId, variantId);
    }

    private long deliveries(Sent sent) {
        return deliveries.stream().filter(sent.eventId()::equals).count();
    }

    private void intercept(ChannelInterceptor interceptor) {
        binding.addInterceptor(interceptor);
        interceptors.add(interceptor);
    }

    private long markers(Sent sent) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = 'inventory' AND event_id = ?::uuid",
                Long.class, sent.eventId());
    }

    private long seqs(UUID productId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM processed_aggregate WHERE consumer = 'inventory' AND aggregate_id = ?",
                Long.class, productId.toString());
    }

    /** Reads the topic from the start until the record with this key arrives. */
    private static ConsumerRecord<byte[], byte[]> readByKey(String topic, String key) {
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
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

    /** The group's committed offset on partition 0 -- the next record it will read -- or null when none. */
    private static Long committed() {
        try (Admin admin = admin()) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(30, TimeUnit.SECONDS).get(new TopicPartition(TOPIC, 0));
            return committed == null ? null : committed.offset();
        } catch (Exception e) {
            throw new AssertionError("could not read the offsets of " + GROUP, e);
        }
    }

    private static <T> void await(String what, Supplier<T> read, Predicate<T> done) {
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
    }

    private static Admin admin() {
        return Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
    }

    private static String header(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** A header as text: raw UTF-8 bytes as Kafka carries it, or a String as a test builds it. */
    private static String text(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
    }

    /** No request is made here; refuse every token rather than ask Keycloak for its keys. */
    @TestConfiguration(proxyBeanMethods = false)
    static class NoTokens {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("no tokens in this test");
            };
        }
    }

    /**
     * What a @SpringBootTest does for its own contexts: keep the test tree's
     * configurations (TestcontainersConfiguration above all, which would start
     * a database of its own) out of the application's component scan.
     */
    static final class LeaveOutTestComponents extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getAnnotationMetadata().isAnnotated(TestComponent.class.getName());
        }
    }
}
