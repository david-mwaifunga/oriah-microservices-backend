package com.oriah.inventory.events;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.ListenerContainerWithDlqAndRetryCustomizer;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.backoff.BackOffExecution;

import com.oriah.events.EventsProperties;
import com.oriah.inventory.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens on Kafka to a product event inventory cannot apply: it is
 * delivered again a few times with a growing pause (unless it is a refusal
 * that would fail the same way again: ProductEventInboxTest), then published
 * to a dead-letter topic of its own instead of blocking the topic -- without
 * ever holding a poll so long that Kafka takes the partition away.
 *
 * <p>catalog.product is read through the Inbox, so the oriah-events module
 * does this for the binding, in place of the Kafka binder's own retry: the
 * listener container's error handler, which commits an event only once the
 * dead-letter topic has it (shared/oriah-events README, "When a message keeps
 * failing"). ProductEventDeadLetterKafkaTest watches it happen on a real
 * broker.
 *
 * <p>The test binder has no dead-letter topic to run, so this reads the
 * settings exactly as the Kafka binder binds them -- the common consumer
 * properties from Spring Cloud Stream, and the Kafka-specific ones bound from
 * the same environment the Kafka binder reads.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class ProductEventBindingTest {

    private static final String BINDING = "productEvents-in-0";

    /**
     * The longest one dead-letter publish can take before it fails (and the
     * event is kept, uncommitted): spring-kafka's publisher first looks up the
     * topic's partitions (up to 5 s), then the module's dead-letter producer
     * waits for the topic's details (max.block.ms, 10 s) and for the send
     * itself (delivery.timeout.ms, 10 s). The module sets these on its own
     * producer, whatever the binding says (oriah-events README, "The
     * dead-letter publisher").
     */
    private static final long DEAD_LETTER_PUBLISH_MS = 5_000 + 10_000 + 10_000;

    @Autowired
    private BindingServiceProperties bindings;
    @Autowired
    private ConfigurableApplicationContext context;
    @Autowired
    private DataSource dataSource;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /**
     * Each retry is a new delivery: the error handler waits the pause, then
     * Kafka hands the same event over again on the next poll. The back-off is
     * the one the Kafka binder builds from these settings and gives the
     * module: max-attempts - 1 retries, so max-attempts 3 is three deliveries
     * in all. (The binder's own retry, which the module replaces here, gave
     * one more.)
     */
    @Test
    @DisplayName("a failing product event is delivered three times in all, 1 s and then 2 s apart, before it is "
            + "given up on")
    void retriedWithBackOff() {
        ConsumerProperties consumer = bindings.getConsumerProperties(BINDING);

        assertThat(consumer.getMaxAttempts()).isEqualTo(3);
        assertThat(consumer.getBackOffInitialInterval()).isEqualTo(1000);
        assertThat(consumer.getBackOffMultiplier()).isEqualTo(2.0);
        assertThat(consumer.getBackOffMaxInterval()).isEqualTo(10000);
        assertThat(pauses(consumer))
                .as("the pauses between deliveries: one fewer than the deliveries")
                .containsExactly(1000L, 2000L);
    }

    @Test
    @DisplayName("then it goes to catalog.product.dlq.inventory: the topic, '.dlq.', the consumer group")
    void thenDeadLettered() {
        BindingProperties binding = bindings.getBindingProperties(BINDING);
        assertThat(binding.getDestination()).isEqualTo("catalog.product");
        assertThat(binding.getGroup()).isEqualTo("inventory");

        KafkaConsumerProperties kafka = kafkaConsumerProperties();

        assertThat(kafka.isEnableDlq()).isTrue();
        assertThat(kafka.getDlqName())
                .isEqualTo(binding.getDestination() + ".dlq." + binding.getGroup())
                .isEqualTo("catalog.product.dlq.inventory");
    }

    /**
     * The oriah-events module dead-letters only the bindings that read a topic
     * listed in oriah.events.inbox.topics; any other binding keeps the Kafka
     * binder's own handling, which commits -- drops -- an event when its
     * dead-letter publish fails. So catalog.product must be listed, and be
     * exactly the topic productEvents-in-0 reads.
     */
    @Test
    @DisplayName("catalog.product is read through the Inbox, so the module -- not the binder -- dead-letters it")
    void theModuleDeadLettersThisBinding() {
        BindingProperties binding = bindings.getBindingProperties(BINDING);

        assertThat(context.getBean(EventsProperties.class).inbox().topics())
                .as("oriah.events.inbox.topics: the three this service reads through the Inbox (order's events arrived in Phase 7)")
                .containsExactlyInAnyOrder("catalog.product", "oriah.procurement", "oriah.order")
                .contains(binding.getDestination());
        assertThat(context.getBean(ListenerContainerWithDlqAndRetryCustomizer.class)
                .retryAndDlqInBinding(binding.getDestination(), binding.getGroup()))
                .as("whether the Kafka binder keeps its own retry and dead-lettering for this binding")
                .isFalse();
    }

    /**
     * Kafka must hear from the consumer -- its next poll -- within
     * {@code max.poll.interval.ms}, or it takes the partition away. The events
     * in hand are then never committed, they come back to be failed all over
     * again, and every product behind them waits, round after round.
     *
     * <p>Each retry is a new poll of the same event, so one poll holds one try
     * of each event it hands over, and then whatever the consumer thread waits
     * before it polls again: the pause before the next try or, after the last
     * try, the dead-letter publish -- and, if that publish fails, the pause the
     * module takes before the event comes round again (back-off-max-interval).
     * The worst poll is every event in it failing on the database being away,
     * each try waiting the pool's whole connection-timeout, followed by the
     * longest of those waits. That must fit.
     */
    @Test
    @DisplayName("a poll of product events that all keep failing is worked through inside Kafka's poll interval")
    void theWorstPollFitsInsideThePollInterval() throws SQLException {
        ConsumerProperties consumer = bindings.getConsumerProperties(BINDING);
        KafkaConsumerProperties kafka = kafkaConsumerProperties();

        long tryMs = dataSource.unwrap(HikariDataSource.class).getConnectionTimeout();
        long longestPause = Collections.max(pauses(consumer));
        long deadLetterMs = DEAD_LETTER_PUBLISH_MS + consumer.getBackOffMaxInterval();
        long events = consumerSetting(kafka, ConsumerConfig.MAX_POLL_RECORDS_CONFIG);

        long worstPoll = events * tryMs + Math.max(longestPause, deadLetterMs);

        assertThat(worstPoll)
                .as("max.poll.records %d x one try of %d ms, then the longer of the %d ms pause and %d ms for a "
                        + "failed dead-letter publish and the pause after it", events, tryMs, longestPause,
                        deadLetterMs)
                .isLessThanOrEqualTo(consumerSetting(kafka, ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
    }

    /**
     * The pauses between deliveries, from the same back-off the Kafka binder
     * builds for this binding and hands the module (KafkaMessageChannelBinder
     * 5.0.1, createBackOff).
     */
    private static List<Long> pauses(ConsumerProperties consumer) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(consumer.getMaxAttempts() - 1);
        backOff.setInitialInterval(consumer.getBackOffInitialInterval());
        backOff.setMaxInterval(consumer.getBackOffMaxInterval());
        backOff.setMultiplier(consumer.getBackOffMultiplier());
        BackOffExecution execution = backOff.start();
        List<Long> pauses = new ArrayList<>();
        for (long pause = execution.nextBackOff(); pause != BackOffExecution.STOP; pause = execution.nextBackOff()) {
            pauses.add(pause);
        }
        return pauses;
    }

    /** A Kafka consumer setting as the client gets it: the binding's own, then the binder-wide one, then Kafka's default. */
    private long consumerSetting(KafkaConsumerProperties kafka, String key) {
        String value = kafka.getConfiguration().get(key);
        if (value == null) {
            value = Binder.get(context.getEnvironment())
                    .bind("spring.cloud.stream.kafka.binder.consumer-properties",
                            Bindable.mapOf(String.class, String.class))
                    .orElse(Map.of())
                    .get(key);
        }
        return value != null ? Long.parseLong(value)
                : ((Number) ConsumerConfig.configDef().defaultValues().get(key)).longValue();
    }

    /** Bound the way the Kafka binder binds them: spring.cloud.stream.kafka, defaults merged per binding. */
    private KafkaConsumerProperties kafkaConsumerProperties() {
        KafkaExtendedBindingProperties kafka = new KafkaExtendedBindingProperties();
        Binder.get(context.getEnvironment()).bind("spring.cloud.stream.kafka", Bindable.ofInstance(kafka));
        kafka.setApplicationContext(context);
        return kafka.getExtendedConsumerProperties(BINDING);
    }
}
