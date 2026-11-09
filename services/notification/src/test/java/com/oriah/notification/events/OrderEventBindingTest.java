package com.oriah.notification.events;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.backoff.BackOffExecution;

import com.oriah.notification.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the Kafka binder does with an order event the consumer cannot apply:
 * retry it a few times with a growing pause (unless it is a refusal that
 * would fail the same way again: OrderEventInboxTest), then publish it to a
 * dead-letter topic of its own instead of blocking the topic -- without ever
 * holding a poll so long that Kafka takes the partition away.
 *
 * <p>The test binder has no dead-letter topic to run, so this reads the
 * settings exactly as the Kafka binder binds them -- the common consumer
 * properties from Spring Cloud Stream, and the Kafka-specific ones bound from
 * the same environment the Kafka binder reads.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.function.definition=orderEvents",
        "spring.cloud.stream.bindings.orderEvents-in-0.destination=oriah.order"
})
class OrderEventBindingTest {

    private static final String BINDING = "orderEvents-in-0";

    @Autowired
    private BindingServiceProperties bindings;
    @Autowired
    private ConfigurableApplicationContext context;
    @Autowired
    private DataSource dataSource;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("a failing order event is retried with back-off before it is given up on")
    void retriedWithBackOff() {
        ConsumerProperties consumer = bindings.getConsumerProperties(BINDING);

        assertThat(consumer.getMaxAttempts()).isEqualTo(3);
        assertThat(consumer.getBackOffInitialInterval()).isEqualTo(1000);
        assertThat(consumer.getBackOffMultiplier()).isEqualTo(2.0);
        assertThat(consumer.getBackOffMaxInterval()).isEqualTo(10000);
    }

    @Test
    @DisplayName("then it goes to oriah.order.dlq.notification: the topic, '.dlq.', the consumer group")
    void thenDeadLettered() {
        BindingProperties binding = bindings.getBindingProperties(BINDING);
        assertThat(binding.getDestination()).isEqualTo("oriah.order");
        assertThat(binding.getGroup()).isEqualTo("notification");

        KafkaConsumerProperties kafka = kafkaConsumerProperties();

        assertThat(kafka.isEnableDlq()).isTrue();
        assertThat(kafka.getDlqName())
                .isEqualTo(binding.getDestination() + ".dlq." + binding.getGroup())
                .isEqualTo("oriah.order.dlq.notification");
    }

    /**
     * Kafka must hear from the consumer -- its next poll -- within
     * {@code max.poll.interval.ms}, or it takes the partition away. The offsets
     * of the batch in hand are then never committed, the same order events come
     * back, are failed and retried all over again, and every order behind them
     * waits, round after round.
     *
     * <p>The binder retries in the thread that polls, so the worst poll is
     * every event in it failing every try: the database is away, each try waits
     * the pool's whole connection-timeout and fails, the binder pauses between
     * tries, and at last waits for the dead-letter topic to take the event.
     * That sum, for every event one poll may hand over, must fit.
     */
    @Test
    @DisplayName("a poll of order events that all keep failing is worked through inside Kafka's poll interval")
    void theWorstPollFitsInsideThePollInterval() throws SQLException {
        ConsumerProperties consumer = bindings.getConsumerProperties(BINDING);
        KafkaConsumerProperties kafka = kafkaConsumerProperties();

        // Spring Cloud Stream 5 hands max-attempts to RetryPolicy.maxRetries:
        // the first try, then that many retries.
        long tries = consumer.getMaxAttempts() + 1L;
        long tryMs = dataSource.unwrap(HikariDataSource.class).getConnectionTimeout();
        // The binder waits for the dead-letter send up to the producer's
        // delivery.timeout.ms plus 2 s (KafkaMessageChannelBinder 5.0.1).
        long deadLetterMs = producerSetting(kafka, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG) + 2_000;
        long perEvent = tries * tryMs + pauses(consumer) + deadLetterMs;

        long worstPoll = consumerSetting(kafka, ConsumerConfig.MAX_POLL_RECORDS_CONFIG) * perEvent;

        assertThat(worstPoll)
                .as("max.poll.records x (%d tries x %d ms + the pauses + %d ms for the dead-letter send)",
                        tries, tryMs, deadLetterMs)
                .isLessThanOrEqualTo(consumerSetting(kafka, ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
    }

    /** The pauses between tries, from the same RetryPolicy the binder builds (AbstractBinder.buildRetryTemplate). */
    private static long pauses(ConsumerProperties consumer) {
        BackOffExecution backOff = RetryPolicy.builder()
                .maxRetries(consumer.getMaxAttempts())
                .delay(Duration.ofMillis(consumer.getBackOffInitialInterval()))
                .multiplier(consumer.getBackOffMultiplier())
                .maxDelay(Duration.ofMillis(consumer.getBackOffMaxInterval()))
                .build()
                .getBackOff()
                .start();
        long total = 0;
        for (int retry = 0; retry < consumer.getMaxAttempts(); retry++) {
            total += backOff.nextBackOff();
        }
        return total;
    }

    /** A Kafka consumer setting as the client gets it: the binding's own, then the binder-wide one, then Kafka's default. */
    private long consumerSetting(KafkaConsumerProperties kafka, String key) {
        return kafkaSetting(kafka.getConfiguration(), "spring.cloud.stream.kafka.binder.consumer-properties",
                ConsumerConfig.configDef().defaultValues(), key);
    }

    /** A setting of the producer that sends to the dead-letter topic, found the same way. */
    private long producerSetting(KafkaConsumerProperties kafka, String key) {
        return kafkaSetting(kafka.getDlqProducerProperties().getConfiguration(),
                "spring.cloud.stream.kafka.binder.producer-properties",
                ProducerConfig.configDef().defaultValues(), key);
    }

    private long kafkaSetting(Map<String, String> binding, String binderWide, Map<String, Object> defaults,
                              String key) {
        String value = binding.get(key);
        if (value == null) {
            value = Binder.get(context.getEnvironment())
                    .bind(binderWide, Bindable.mapOf(String.class, String.class))
                    .orElse(Map.of())
                    .get(key);
        }
        return value != null ? Long.parseLong(value) : ((Number) defaults.get(key)).longValue();
    }

    /** Bound the way the Kafka binder binds them: spring.cloud.stream.kafka, defaults merged per binding. */
    private KafkaConsumerProperties kafkaConsumerProperties() {
        KafkaExtendedBindingProperties kafka = new KafkaExtendedBindingProperties();
        Binder.get(context.getEnvironment()).bind("spring.cloud.stream.kafka", Bindable.ofInstance(kafka));
        kafka.setApplicationContext(context);
        return kafka.getExtendedConsumerProperties(BINDING);
    }
}
