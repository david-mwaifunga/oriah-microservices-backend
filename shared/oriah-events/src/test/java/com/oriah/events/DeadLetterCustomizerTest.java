package com.oriah.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.transaction.KafkaAwareTransactionManager;
import org.springframework.util.backoff.BackOff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The module's hook into the Kafka binder, one listener container at a time,
 * with no broker: which bindings it takes over, what it refuses, and what it
 * installs.
 *
 * <p>Why the binder needs a hook at all: Spring Cloud Stream 5.0.1's own
 * dead-letter sender only logs a failed publish to the dead-letter topic,
 * and the record's offset is then committed -- the message is gone. The
 * module's handler keeps the record uncommitted until the dead-letter topic
 * has it (proved against a real broker in {@code DeadLetterKafkaTest}).
 */
class DeadLetterCustomizerTest {

    private static final String ORDER_TOPIC = "oriah.order";
    private static final String DEAD_LETTERS = "oriah.order.dlq.notification";
    private static final BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> TO_DEAD_LETTERS =
            (record, exception) -> new TopicPartition(DEAD_LETTERS, record.partition());

    @Nested
    @DisplayName("which bindings the module takes over")
    class Scope {

        @Test
        @DisplayName("Inbox on: a binding on one of oriah.events.inbox.topics is the module's; any other "
                + "binding keeps the binder's own retry and dead-lettering")
        void inboxTopics() {
            DeadLetterCustomizer customizer = customizer(inbox(Set.of(ORDER_TOPIC, "catalog.product"), null),
                    false);

            assertThat(customizer.retryAndDlqInBinding(ORDER_TOPIC, "notification")).isFalse();
            assertThat(customizer.retryAndDlqInBinding("catalog.product", "notification")).isFalse();
            assertThat(customizer.retryAndDlqInBinding("oriah.payment", "notification")).isTrue();
        }

        @Test
        @DisplayName("Inbox off: its topics are not taken over, even if listed")
        void inboxOff() {
            DeadLetterCustomizer customizer = new DeadLetterCustomizer(DeadLetterScope.of(properties(
                    new EventsProperties.InboxSwitch(false, Set.of(ORDER_TOPIC),
                            new EventsProperties.CatchUpSettings(null, Set.of())),
                    false)));

            assertThat(customizer.retryAndDlqInBinding(ORDER_TOPIC, "notification")).isTrue();
        }

        @Test
        @DisplayName("replay on: the replay-request topic the module binds itself is the module's")
        void replayRequests() {
            assertThat(customizer(inbox(Set.of(), null), true)
                    .retryAndDlqInBinding(ReplayRequest.TOPIC, "order")).isFalse();
            assertThat(customizer(inbox(Set.of(), null), false)
                    .retryAndDlqInBinding(ReplayRequest.TOPIC, "order")).isTrue();
        }

        @Test
        @DisplayName("CatchUp on: the consumer's own replay topic, oriah.replay.<consumer>, is the module's")
        void catchUpTopic() {
            assertThat(customizer(inbox(Set.of(ORDER_TOPIC), "finance"), false)
                    .retryAndDlqInBinding("oriah.replay.finance", "finance")).isFalse();
            assertThat(customizer(inbox(Set.of(ORDER_TOPIC), null), false)
                    .retryAndDlqInBinding("oriah.replay.finance", "finance")).isTrue();
        }

        @Test
        @DisplayName("a binding that is not the module's is left exactly as the binder built it")
        void otherBindingUntouched() {
            DeadLetterCustomizer customizer = customizer(inbox(Set.of(ORDER_TOPIC), null), false);
            ConcurrentMessageListenerContainer<byte[], byte[]> container = container(Map.of());
            CommonErrorHandler binders = new DefaultErrorHandler();
            container.setCommonErrorHandler(binders);

            customizer.configure(container, "oriah.payment", "notification", TO_DEAD_LETTERS, backOff(3),
                    binding("paymentEvents-in-0", 3));

            assertThat(container.getCommonErrorHandler()).isSameAs(binders);
            assertThat(customizer.producerFactories()).isEmpty();
        }

        @Test
        @DisplayName("a binding of the service's own in batch mode with enable-dlq is refused (the backstop): with "
                + "this hook present the Kafka binder dead-letters no batch itself, and the hook does not take the "
                + "service's own bindings over, so a failing batch would be committed undelivered")
        void otherBatchBindingWithDeadLettering() {
            DeadLetterCustomizer customizer = customizer(inbox(Set.of(ORDER_TOPIC), null), false);
            ExtendedConsumerProperties<KafkaConsumerProperties> binding = binding("paymentEvents-in-0", 3);
            binding.setBatchMode(true);

            assertThatThrownBy(() -> customizer.configure(container(Map.of()), "oriah.payment", "notification",
                    TO_DEAD_LETTERS, backOff(3), binding))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("paymentEvents-in-0")
                    .hasMessageContaining("batch-mode")
                    .hasMessageContaining("enable-dlq");
        }

        @Test
        @DisplayName("a binding of the service's own in batch mode without enable-dlq is left as the binder built it")
        void otherBatchBindingWithoutDeadLettering() {
            DeadLetterCustomizer customizer = customizer(inbox(Set.of(ORDER_TOPIC), null), false);
            ConcurrentMessageListenerContainer<byte[], byte[]> container = container(Map.of());
            CommonErrorHandler binders = new DefaultErrorHandler();
            container.setCommonErrorHandler(binders);
            ExtendedConsumerProperties<KafkaConsumerProperties> binding = binding("paymentEvents-in-0", 3);
            binding.setBatchMode(true);
            binding.getExtension().setEnableDlq(false);

            customizer.configure(container, "oriah.payment", "notification", null, backOff(3), binding);

            assertThat(container.getCommonErrorHandler()).isSameAs(binders);
            assertThat(customizer.producerFactories()).isEmpty();
        }
    }

    @Nested
    @DisplayName("refusals: a binding the module cannot make safe is refused with a plain reason")
    class Refusals {

        private final DeadLetterCustomizer customizer = customizer(inbox(Set.of(ORDER_TOPIC), null), false);

        @Test
        @DisplayName("no dead-letter topic (enable-dlq false): the binder passes no resolver")
        void noResolver() {
            ExtendedConsumerProperties<KafkaConsumerProperties> binding = binding("orderEvents-in-0", 3);
            binding.getExtension().setEnableDlq(false);

            assertThatThrownBy(() -> customizer.configure(container(Map.of()), ORDER_TOPIC, "notification", null,
                    backOff(3), binding))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("orderEvents-in-0")
                    .hasMessageContaining("enable-dlq");
        }

        @Test
        @DisplayName("enable-dlq true but no dlq-name")
        void noDeadLetterName() {
            ExtendedConsumerProperties<KafkaConsumerProperties> binding = binding("orderEvents-in-0", 3);
            binding.getExtension().setDlqName(" ");

            assertThatThrownBy(() -> customizer.configure(container(Map.of()), ORDER_TOPIC, "notification",
                    TO_DEAD_LETTERS, backOff(3), binding))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dlq-name");
        }

        @Test
        @DisplayName("a dlq-name that is the topic the binding reads: Spring Kafka's dead-letter publisher skips a "
                + "publish back to the same topic for a failure it never retries, and the record would be committed")
        void deadLettersToItsOwnTopic() {
            ExtendedConsumerProperties<KafkaConsumerProperties> binding = binding("orderEvents-in-0", 3);
            binding.getExtension().setDlqName(ORDER_TOPIC);

            assertThatThrownBy(() -> customizer.configure(container(Map.of()), ORDER_TOPIC, "notification",
                    (record, exception) -> new TopicPartition(ORDER_TOPIC, record.partition()), backOff(3), binding))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("orderEvents-in-0")
                    .hasMessageContaining("dlq-name " + ORDER_TOPIC)
                    .hasMessageContaining("the topic it reads");
        }

        @Test
        @DisplayName("a key deserializer other than bytes: the dead-letter publish sends the key back as bytes, "
                + "so every publish would fail for ever")
        void keyNotBytes() {
            assertThatThrownBy(() -> customizer.configure(
                    container(Map.of(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)),
                    ORDER_TOPIC, "notification", TO_DEAD_LETTERS, backOff(3), binding("orderEvents-in-0", 3)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("key deserializer")
                    .hasMessageContaining(StringDeserializer.class.getName());
        }

        @Test
        @DisplayName("a value deserializer other than bytes, given by name as a service's yaml would")
        void valueNotBytes() {
            assertThatThrownBy(() -> customizer.configure(
                    container(Map.of(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            StringDeserializer.class.getName())),
                    ORDER_TOPIC, "notification", TO_DEAD_LETTERS, backOff(3), binding("orderEvents-in-0", 3)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("value deserializer")
                    .hasMessageContaining(StringDeserializer.class.getName());
        }

        @Test
        @DisplayName("max-attempts 1 (the binder then passes no back-off): the binder sends every failure to its "
                + "own dead-letter sender before the module's handler sees it")
        void singleAttempt() {
            assertThatThrownBy(() -> customizer.configure(container(Map.of()), ORDER_TOPIC, "notification",
                    TO_DEAD_LETTERS, null, binding("orderEvents-in-0", 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max-attempts");
        }

        @Test
        @DisplayName("batch mode: the binder hands failures of a batch to its own error channel")
        void batchMode() {
            ExtendedConsumerProperties<KafkaConsumerProperties> binding = binding("orderEvents-in-0", 3);
            binding.setBatchMode(true);

            assertThatThrownBy(() -> customizer.configure(container(Map.of()), ORDER_TOPIC, "notification",
                    TO_DEAD_LETTERS, backOff(3), binding))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("batch");
        }

        @Test
        @DisplayName("Kafka transactions: the binder hands failures to its own after-rollback dead-lettering")
        void transactions() {
            ConcurrentMessageListenerContainer<byte[], byte[]> container = container(Map.of());
            container.getContainerProperties().setKafkaAwareTransactionManager(
                    mock(KafkaAwareTransactionManager.class));

            assertThatThrownBy(() -> customizer.configure(container, ORDER_TOPIC, "notification", TO_DEAD_LETTERS,
                    backOff(3), binding("orderEvents-in-0", 3)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transaction");
        }

        @Test
        @DisplayName("a retryable-exceptions entry that is not an Exception (an Error) cannot be classified")
        void retryableErrorType() {
            ExtendedConsumerProperties<KafkaConsumerProperties> binding = binding("orderEvents-in-0", 3);
            binding.setRetryableExceptions(Map.of(OutOfMemoryError.class, false));

            assertThatThrownBy(() -> customizer.configure(container(Map.of()), ORDER_TOPIC, "notification",
                    TO_DEAD_LETTERS, backOff(3), binding))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("retryable-exceptions")
                    .hasMessageContaining(OutOfMemoryError.class.getName());
        }

        @Test
        @DisplayName("the older five-argument hook, which carries no binding properties, is refused for the "
                + "module's bindings and ignored for others")
        void olderHook() {
            assertThatThrownBy(() -> customizer.configure(container(Map.of()), ORDER_TOPIC, "notification",
                    TO_DEAD_LETTERS, backOff(3)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(ORDER_TOPIC);

            ConcurrentMessageListenerContainer<byte[], byte[]> other = container(Map.of());
            customizer.configure(other, "oriah.payment", "notification", TO_DEAD_LETTERS, backOff(3));
            assertThat(other.getCommonErrorHandler()).isNull();
        }
    }

    @Nested
    @DisplayName("what it installs on one of the module's bindings")
    class Installs {

        private final DeadLetterCustomizer customizer = customizer(inbox(Set.of(ORDER_TOPIC, "catalog.product"),
                null), false);

        @Test
        @DisplayName("a container error handler that dead-letters through the module's own publisher")
        void errorHandler() {
            ConcurrentMessageListenerContainer<byte[], byte[]> container = container(Map.of());

            customizer.configure(container, ORDER_TOPIC, "notification", TO_DEAD_LETTERS, backOff(3),
                    binding("orderEvents-in-0", 3));

            assertThat(container.getCommonErrorHandler()).isInstanceOf(DefaultErrorHandler.class);
            assertThat(customizer.producerFactories()).hasSize(1);
        }

        @Test
        @DisplayName("the dead-letter producer copies only the connection and security settings of the "
                + "binding's own consumer, sends raw bytes, and waits at most 10 seconds for Kafka")
        void producerSettings() {
            Map<String, Object> consumer = new HashMap<>();
            consumer.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker-a:9093");
            consumer.put("security.protocol", "SASL_SSL");
            consumer.put("sasl.mechanism", "SCRAM-SHA-512");
            consumer.put("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required;");
            consumer.put("ssl.truststore.location", "/etc/kafka/truststore.jks");
            consumer.put("client.dns.lookup", "use_all_dns_ips");
            consumer.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
            consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

            customizer.configure(container(consumer), ORDER_TOPIC, "notification", TO_DEAD_LETTERS, backOff(3),
                    binding("orderEvents-in-0", 3));

            assertThat(customizer.producerFactories().get(0).getConfigurationProperties())
                    .containsOnly(
                            Map.entry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker-a:9093"),
                            Map.entry("security.protocol", "SASL_SSL"),
                            Map.entry("sasl.mechanism", "SCRAM-SHA-512"),
                            Map.entry("sasl.jaas.config",
                                    "org.apache.kafka.common.security.scram.ScramLoginModule required;"),
                            Map.entry("ssl.truststore.location", "/etc/kafka/truststore.jks"),
                            Map.entry("client.dns.lookup", "use_all_dns_ips"),
                            Map.entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class),
                            Map.entry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class),
                            Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
                            Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
                            Map.entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000),
                            Map.entry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000),
                            Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000));
        }

        @Test
        @DisplayName("one producer per binding, built from that binding's own consumer: two bindings with "
                + "different credentials never share one")
        void onePerBinding() {
            customizer.configure(container(Map.of("sasl.jaas.config", "user-a")), ORDER_TOPIC, "notification",
                    TO_DEAD_LETTERS, backOff(3), binding("orderEvents-in-0", 3));
            customizer.configure(container(Map.of("sasl.jaas.config", "user-b")), "catalog.product",
                    "notification", TO_DEAD_LETTERS, backOff(3), binding("productEvents-in-0", 3));

            assertThat(customizer.producerFactories()).hasSize(2);
            assertThat(customizer.producerFactories())
                    .extracting(factory -> factory.getConfigurationProperties().get("sasl.jaas.config"))
                    .containsExactly("user-a", "user-b");
        }

        @Test
        @DisplayName("on shutdown every dead-letter producer is closed")
        void closedOnShutdown() throws Exception {
            customizer.configure(container(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9")),
                    ORDER_TOPIC, "notification", TO_DEAD_LETTERS, backOff(3), binding("orderEvents-in-0", 3));
            DefaultKafkaProducerFactory<byte[], byte[]> factory = customizer.producerFactories().get(0);
            List<String> closed = new ArrayList<>();
            factory.addListener(new ProducerFactory.Listener<>() {
                @Override
                public void producerRemoved(String id, Producer<byte[], byte[]> producer) {
                    closed.add(id);
                }
            });
            factory.createProducer();

            customizer.destroy();

            assertThat(closed).hasSize(1);
        }
    }

    @Nested
    @DisplayName("which failures are tried again: the binding's retryable-exceptions, on top of spring-kafka's "
            + "own list of failures that can never succeed")
    class Classification {

        private final List<Exception> recovered = new ArrayList<>();
        private final ConsumerAwareRecordRecoverer recoverer = (record, consumer, exception) -> recovered.add(exception);

        @Test
        @DisplayName("an exception marked false (a refusal) goes to the dead-letter topic on its first delivery")
        void notRetryable() {
            ExtendedConsumerProperties<KafkaConsumerProperties> binding = binding("orderEvents-in-0", 3);
            binding.setRetryableExceptions(Map.of(IllegalArgumentException.class, false));
            DefaultErrorHandler handler = DeadLetterCustomizer.errorHandler(recoverer, backOff(3), binding);

            deliver(handler, new IllegalArgumentException("undeclared event type"));

            assertThat(recovered).hasSize(1);
        }

        @Test
        @DisplayName("any other exception is tried max-attempts times in all, then dead-lettered")
        void retryable() {
            ExtendedConsumerProperties<KafkaConsumerProperties> binding = binding("orderEvents-in-0", 3);
            binding.setRetryableExceptions(Map.of(IllegalArgumentException.class, false));
            DefaultErrorHandler handler = DeadLetterCustomizer.errorHandler(recoverer, backOff(3), binding);

            assertThatThrownBy(() -> deliver(handler, new IllegalStateException("database down")))
                    .hasMessageContaining("Record in retry");
            assertThatThrownBy(() -> deliver(handler, new IllegalStateException("database down")))
                    .hasMessageContaining("Record in retry");
            assertThat(recovered).as("after two deliveries").isEmpty();

            deliver(handler, new IllegalStateException("database down"));

            assertThat(recovered).as("after the third").hasSize(1);
        }

        @Test
        @DisplayName("spring-kafka's own never-retry list is kept: a ClassCastException is dead-lettered at once")
        void builtInFatalKept() {
            DefaultErrorHandler handler = DeadLetterCustomizer.errorHandler(recoverer, backOff(3),
                    binding("orderEvents-in-0", 3));

            deliver(handler, new ClassCastException("not a Message<byte[]>"));

            assertThat(recovered).hasSize(1);
        }

        @Test
        @DisplayName("default-retryable false: only the types marked true are retried, and spring-kafka's "
                + "never-retry list still wins over a broad type marked true")
        void defaultNotRetryable() {
            ExtendedConsumerProperties<KafkaConsumerProperties> binding = binding("orderEvents-in-0", 3);
            binding.setDefaultRetryable(false);
            binding.setRetryableExceptions(Map.of(RuntimeException.class, true));
            DefaultErrorHandler handler = DeadLetterCustomizer.errorHandler(recoverer, backOff(3), binding);

            deliver(handler, new ClassCastException("not a Message<byte[]>"));

            assertThat(recovered).as("ClassCastException at once").hasSize(1);
            assertThatThrownBy(() -> deliver(handler, new IllegalStateException("database down")))
                    .as("a RuntimeException marked true is retried").hasMessageContaining("Record in retry");
        }

        @Test
        @DisplayName("default-retryable false: an exception not listed at all is dead-lettered at once")
        void defaultNotRetryableUnlisted() {
            ExtendedConsumerProperties<KafkaConsumerProperties> binding = binding("orderEvents-in-0", 3);
            binding.setDefaultRetryable(false);
            DefaultErrorHandler handler = DeadLetterCustomizer.errorHandler(recoverer, backOff(3), binding);

            deliver(handler, new IllegalStateException("database down"));

            assertThat(recovered).hasSize(1);
        }

        /** One failed delivery of the same record, as the container reports it to its error handler. */
        private void deliver(DefaultErrorHandler handler, Exception cause) {
            ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(ORDER_TOPIC, 0, 42L, new byte[0],
                    new byte[0]);
            handler.handleRemaining(new ListenerExecutionFailedException("listener failed", cause),
                    List.of(record), mock(Consumer.class), mock(MessageListenerContainer.class));
        }
    }

    private static DeadLetterCustomizer customizer(EventsProperties.InboxSwitch inbox, boolean replay) {
        return new DeadLetterCustomizer(DeadLetterScope.of(properties(inbox, replay)));
    }

    private static EventsProperties.InboxSwitch inbox(Set<String> topics, String catchUpConsumer) {
        return new EventsProperties.InboxSwitch(true, topics,
                new EventsProperties.CatchUpSettings(catchUpConsumer, Set.of()));
    }

    private static EventsProperties properties(EventsProperties.InboxSwitch inbox, boolean replay) {
        return new EventsProperties("order", Set.of(), new EventsProperties.Switch(false),
                new EventsProperties.Relay(false, 100), new EventsProperties.Replay(replay, 10), inbox);
    }

    /** A binding as the binder describes it: dead-lettering on, to its own topic. */
    private static ExtendedConsumerProperties<KafkaConsumerProperties> binding(String name, int maxAttempts) {
        ExtendedConsumerProperties<KafkaConsumerProperties> binding =
                new ExtendedConsumerProperties<>(new KafkaConsumerProperties());
        binding.populateBindingName(name);
        binding.setMaxAttempts(maxAttempts);
        binding.setBackOffInitialInterval(1);
        binding.setBackOffMaxInterval(1);
        binding.getExtension().setEnableDlq(true);
        binding.getExtension().setDlqName(DEAD_LETTERS);
        return binding;
    }

    /** The back-off the binder builds for max-attempts: max-attempts - 1 retries, kept tiny here. */
    private static BackOff backOff(int maxAttempts) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxAttempts - 1);
        backOff.setInitialInterval(1);
        backOff.setMaxInterval(1);
        return backOff;
    }

    /** A container as the binder builds it: byte-array key and value, plus anything given here. */
    private static ConcurrentMessageListenerContainer<byte[], byte[]> container(Map<String, Object> consumer) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker-a:9092");
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "notification");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.putAll(consumer);
        return new ConcurrentMessageListenerContainer<>(new DefaultKafkaConsumerFactory<>(config),
                new ContainerProperties(ORDER_TOPIC));
    }
}
