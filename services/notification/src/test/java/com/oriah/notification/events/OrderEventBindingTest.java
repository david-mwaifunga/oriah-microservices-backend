package com.oriah.notification.events;

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
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.ListenerContainerWithDlqAndRetryCustomizer;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.backoff.BackOffExecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.oriah.events.EventsProperties;
import com.zaxxer.hikari.HikariDataSource;

import com.oriah.notification.ServiceTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens on Kafka to an order event the consumer cannot apply: it is
 * delivered again a few times with a growing pause (unless it is a refusal
 * that would fail the same way again: OrderEventInboxTest), then published to
 * a dead-letter topic of its own instead of blocking the topic -- without
 * ever holding a poll so long that Kafka takes the partition away.
 *
 * <p>Who does that: oriah.order is read through the Inbox, so it is one of
 * the oriah-events module's own topics, and for its binding the module
 * replaces the Kafka binder's own retry with spring-kafka's container error
 * handler and dead-letter publisher (shared/oriah-events README, "When a
 * message keeps failing"). Two things follow that the binder's own handling
 * did not do: a retry is a NEW delivery of the record (the handler seeks the
 * partition back to it, and the next poll hands it over again), not a loop
 * inside one poll; and the record is not committed until the dead-letter
 * topic has it.
 *
 * <p>The test binder has no Kafka container or dead-letter topic to run, so
 * this reads the settings exactly as the Kafka binder binds them -- the common
 * consumer properties from Spring Cloud Stream, and the Kafka-specific ones
 * bound from the same environment the Kafka binder reads -- and works out
 * from them what the module's handler will do. The module's own
 * DeadLetterKafkaTest proves that handler on a real broker.
 */
@ServiceTest
class OrderEventBindingTest {

    private static final String BINDING = "orderEvents-in-0";

    /**
     * The longest the module's dead-letter publisher waits before it gives up
     * on a dead-letter topic that does not answer: 5 s while spring-kafka
     * looks up the topic's partitions, 10 s for the topic's details
     * (max.block.ms) and 10 s for the send itself (delivery.timeout.ms) --
     * shared/oriah-events README, "The dead-letter publisher". The module
     * builds that producer from fixed settings, not from this service's
     * configuration, so there is nothing here to read it from.
     */
    private static final long DEAD_LETTER_PUBLISH_MS = 5_000 + 10_000 + 10_000;

    @Autowired
    private BindingServiceProperties bindings;
    @Autowired
    private EventsProperties events;
    @Autowired
    private ListenerContainerWithDlqAndRetryCustomizer deadLetters;
    @Autowired
    private ConfigurableApplicationContext context;
    @Autowired
    private DataSource dataSource;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /**
     * oriah.events.inbox.topics names the topics this service reads through
     * the Inbox. The module gives only their bindings its dead-letter
     * handling; any other binding keeps the binder's own, whose dead-letter
     * publish drops the message when it fails. So oriah.order must be listed,
     * and its binding must be the one the module takes over.
     */
    @Test
    @DisplayName("oriah.order is read through the Inbox, so the module -- not the binder -- retries and dead-letters it")
    void readThroughTheInbox() {
        assertThat(events.inbox().enabled()).isTrue();
        assertThat(events.inbox().topics()).containsExactly("oriah.order");

        BindingProperties binding = bindings.getBindingProperties(BINDING);
        assertThat(deadLetters.retryAndDlqInBinding(binding.getDestination(), binding.getGroup()))
                .as("false means: the binder leaves retry and dead-lettering of this binding to the module")
                .isFalse();
    }

    @Test
    @DisplayName("a failing order event is retried with back-off before it is given up on")
    void retriedWithBackOff() {
        ConsumerProperties consumer = bindings.getConsumerProperties(BINDING);

        assertThat(consumer.getMaxAttempts()).isEqualTo(3);
        assertThat(consumer.getBackOffInitialInterval()).isEqualTo(1000);
        assertThat(consumer.getBackOffMultiplier()).isEqualTo(2.0);
        assertThat(consumer.getBackOffMaxInterval()).isEqualTo(10000);
    }

    /**
     * max-attempts counts deliveries in all, the first one included: after
     * each failed delivery the module's handler takes the next pause from the
     * back-off the binder builds for it, and when there is none left it
     * dead-letters the record. (The binder's own retry, which this binding
     * used before, counted max-attempts as retries after the first try: four
     * tries for the same setting.)
     */
    @Test
    @DisplayName("a failing order event is delivered three times in all, 1 s and then 2 s apart; a refusal once")
    void deliveredThreeTimesInAll() {
        ConsumerProperties consumer = bindings.getConsumerProperties(BINDING);

        List<Long> pauses = pausesBetweenDeliveries(consumer);

        assertThat(pauses).as("the pause before each delivery after the first").containsExactly(1_000L, 2_000L);
        assertThat(pauses.size() + 1).as("deliveries in all").isEqualTo(3);
        assertThat(consumer.getRetryableExceptions())
                .as("a refusal (and a body that cannot be read) fails the same way every time: "
                        + "dead-lettered on its first delivery")
                .containsEntry(IllegalArgumentException.class, false)
                .containsEntry(JsonProcessingException.class, false);
    }

    /**
     * The list is exactly those two, on purpose. In particular IllegalStateException is NOT on it: notification has no replay of its own, so an
     * event it gives up on is never applied later, and an IllegalStateException can be something that passes on the next try (a context that is
     * shutting down). The one case that can never pass, an event type declared without words for it, throws IllegalArgumentException instead.
     */
    @Test
    @DisplayName("only a refusal and a body that cannot be read skip the retries: nothing else is given up on at the first failure")
    void onlyRefusalsAndUnreadableBodiesSkipTheRetries() {
        assertThat(bindings.getConsumerProperties(BINDING).getRetryableExceptions())
                .containsOnlyKeys(IllegalArgumentException.class, JsonProcessingException.class);
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
     * {@code max.poll.interval.ms}, or it takes the partition away. The offset
     * of the event in hand is then never committed, the same event comes back
     * to another instance, is failed all over again, and every order behind it
     * waits, round after round.
     *
     * <p>What one poll can hold, at worst: each event it hands over takes a
     * whole try (the database is away, so the try waits the pool's whole
     * connection-timeout and fails). Then, for the one that failed, the
     * module's handler either pauses before its next delivery (the back-off)
     * or -- on its last delivery, or for a refusal -- publishes it to the
     * dead-letter topic (up to {@link #DEAD_LETTER_PUBLISH_MS}) and, if that
     * publish fails, pauses back-off-max-interval before the event comes back.
     * Either way the poll ends there: the handler seeks the partition back to
     * the failed event, so its next delivery comes in the next poll.
     *
     * <p>(This used to add up four tries per event inside one poll, because
     * the binder's own retry looped inside it. The module's handler does not,
     * so the budget per poll is much smaller; one event per poll still keeps
     * it to one event's worst.)
     */
    @Test
    @DisplayName("a poll of order events that keep failing is worked through inside Kafka's poll interval")
    void theWorstPollFitsInsideThePollInterval() throws SQLException {
        ConsumerProperties consumer = bindings.getConsumerProperties(BINDING);
        KafkaConsumerProperties kafka = kafkaConsumerProperties();

        long tryMs = dataSource.unwrap(HikariDataSource.class).getConnectionTimeout();
        long longestPause = Collections.max(pausesBetweenDeliveries(consumer));
        long deadLetterMs = DEAD_LETTER_PUBLISH_MS + consumer.getBackOffMaxInterval();
        long events = consumerSetting(kafka, ConsumerConfig.MAX_POLL_RECORDS_CONFIG);

        long worstPoll = events * tryMs + Math.max(longestPause, deadLetterMs);

        assertThat(worstPoll)
                .as("max.poll.records %d x %d ms per try + the larger of the longest pause (%d ms) and the "
                        + "dead-letter publish with the pause after it failing (%d ms)",
                        events, tryMs, longestPause, deadLetterMs)
                .isLessThanOrEqualTo(consumerSetting(kafka, ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
    }

    /**
     * The pause before each delivery after the first, from the back-off the
     * Kafka binder hands the module for this binding: an
     * ExponentialBackOffWithMaxRetries of max-attempts - 1 retries, with the
     * binding's back-off settings (KafkaMessageChannelBinder.createBackOff,
     * Spring Cloud Stream 5.0.1).
     */
    private static List<Long> pausesBetweenDeliveries(ConsumerProperties consumer) {
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
