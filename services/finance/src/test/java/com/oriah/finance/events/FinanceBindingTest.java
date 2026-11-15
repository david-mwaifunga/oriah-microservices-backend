package com.oriah.finance.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.ListenerContainerWithDlqAndRetryCustomizer;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
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
import com.oriah.finance.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What Kafka does with an event finance cannot apply: it is delivered a few times (unless it is a refusal, which would fail the same way again), then parked on a dead-letter
 * topic of finance's own, never dropped, and never holding a poll so long that Kafka takes the partition away. A money fact that cannot be applied must wait where it can be read
 * and sent again, not vanish and not block the events behind it.
 *
 * <p>Finance reads six producers' topics through the Inbox (so the module, not the binder, retries and dead-letters them) and one replay topic. This walks all seven.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class FinanceBindingTest {

    /** The longest the module's dead-letter publisher waits before it gives up on a topic that does not answer (shared/oriah-events README). */
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
    private javax.sql.DataSource dataSource;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    static Stream<Arguments> bindings() {
        return Stream.of(
            Arguments.of("orderEvents-in-0", "oriah.order"),
            Arguments.of("paymentEvents-in-0", "oriah.payment"),
            Arguments.of("inventoryEvents-in-0", "oriah.inventory"),
            Arguments.of("procurementEvents-in-0", "oriah.procurement"),
            Arguments.of("productEvents-in-0", "catalog.product"),
            Arguments.of("priceEvents-in-0", "catalog.price"),
            Arguments.of("financeReplay-in-0", "oriah.replay.finance"));
    }

    @Test
    @DisplayName("finance reads the six producers' topics through the Inbox, and produces nothing")
    void readThroughTheInbox() {
        assertThat(events.inbox().enabled()).isTrue();
        assertThat(events.inbox().topics()).containsExactlyInAnyOrder("oriah.order", "oriah.payment", "oriah.inventory", "oriah.procurement", "catalog.product", "catalog.price");
        assertThat(events.outbox().enabled()).as("finance writes no outbox: it is read-only to the rest of the system").isFalse();
    }

    @ParameterizedTest(name = "{0}: the module, not the binder, retries and dead-letters {1}")
    @MethodSource("bindings")
    void theModuleDeadLetters(String binding, String topic) {
        BindingProperties properties = bindings.getBindingProperties(binding);

        assertThat(properties.getDestination()).isEqualTo(topic);
        assertThat(properties.getGroup()).isEqualTo("finance");
        assertThat(deadLetters.retryAndDlqInBinding(properties.getDestination(), properties.getGroup()))
                .as("false means: the binder leaves retry and dead-lettering of this binding to the module").isFalse();
    }

    @ParameterizedTest(name = "{0}: three deliveries, 1 s then 2 s apart; a refusal once")
    @MethodSource("bindings")
    void deliveredThreeTimesInAll(String binding, String topic) {
        ConsumerProperties consumer = bindings.getConsumerProperties(binding);

        List<Long> pauses = pausesBetweenDeliveries(consumer);

        assertThat(pauses).containsExactly(1_000L, 2_000L);
        assertThat(consumer.getRetryableExceptions()).as("a refusal, a contradiction or an unreadable body fails the same way every time: dead-lettered on its first delivery")
                .containsEntry(IllegalArgumentException.class, false).containsEntry(IllegalStateException.class, false);
    }

    @ParameterizedTest(name = "{0}: then it goes to {1}.dlq.finance")
    @MethodSource("bindings")
    void thenDeadLettered(String binding, String topic) {
        KafkaConsumerProperties kafka = kafkaConsumerProperties(binding);

        assertThat(kafka.isEnableDlq()).isTrue();
        assertThat(kafka.getDlqName()).isEqualTo(topic + ".dlq.finance");
    }

    @ParameterizedTest(name = "{0}: a poll of events that keep failing is worked through inside Kafka's poll interval")
    @MethodSource("bindings")
    void theWorstPollFitsInsideThePollInterval(String binding, String topic) throws Exception {
        ConsumerProperties consumer = bindings.getConsumerProperties(binding);
        KafkaConsumerProperties kafka = kafkaConsumerProperties(binding);

        long tryMs = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getConnectionTimeout();
        long longestPause = Collections.max(pausesBetweenDeliveries(consumer));
        long deadLetterMs = DEAD_LETTER_PUBLISH_MS + consumer.getBackOffMaxInterval();
        long polled = consumerSetting(kafka, ConsumerConfig.MAX_POLL_RECORDS_CONFIG);

        assertThat(polled).as("one event per poll: a poll holds one try of one event").isEqualTo(1);
        assertThat(polled * tryMs + Math.max(longestPause, deadLetterMs)).isLessThanOrEqualTo(consumerSetting(kafka, ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
    }

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

    private long consumerSetting(KafkaConsumerProperties kafka, String key) {
        String value = kafka.getConfiguration().get(key);
        if (value == null) {
            value = Binder.get(context.getEnvironment()).bind("spring.cloud.stream.kafka.binder.consumer-properties", Bindable.mapOf(String.class, String.class)).orElse(Map.of()).get(key);
        }
        return value != null ? Long.parseLong(value) : ((Number) ConsumerConfig.configDef().defaultValues().get(key)).longValue();
    }

    private KafkaConsumerProperties kafkaConsumerProperties(String binding) {
        KafkaExtendedBindingProperties kafka = new KafkaExtendedBindingProperties();
        Binder.get(context.getEnvironment()).bind("spring.cloud.stream.kafka", Bindable.ofInstance(kafka));
        kafka.setApplicationContext(context);
        return kafka.getExtendedConsumerProperties(binding);
    }
}
