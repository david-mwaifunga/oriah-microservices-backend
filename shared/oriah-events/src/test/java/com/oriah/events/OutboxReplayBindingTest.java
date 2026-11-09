package com.oriah.events;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Switching replay on is all a service does: the module itself binds the
 * replay-request listener to {@code oriah.outbox.replay-requests}, in a
 * consumer group named after the service (so each producer service gets
 * every request once, however many instances it runs), and adds the
 * listener to the service's {@code spring.cloud.function.definition}
 * without dropping the service's own consumers.
 *
 * <p>Why the definition must be extended rather than left alone: Spring
 * Cloud Stream only guesses which function to bind when a service has
 * exactly one; with two (the service's own and this listener) it binds
 * none unless the definition lists them.
 */
class OutboxReplayBindingTest {

    private static final String DEFINITION = "spring.cloud.function.definition";
    private static final String BINDING = "spring.cloud.stream.bindings.outboxReplayRequests-in-0.";
    private static final String KAFKA_BINDING = "spring.cloud.stream.kafka.bindings.outboxReplayRequests-in-0.consumer.";

    private final OutboxReplayBinding binding = new OutboxReplayBinding();

    @Test
    @DisplayName("is registered for Spring Boot to run on every application's environment")
    void registered() throws IOException {
        // Read by name, not instantiated: some of Boot's own processors
        // need constructor arguments only Boot supplies.
        List<String> registered = new ArrayList<>();
        Enumeration<URL> files = getClass().getClassLoader().getResources(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION);
        while (files.hasMoreElements()) {
            Properties factories = PropertiesLoaderUtils.loadProperties(new UrlResource(files.nextElement()));
            String names = factories.getProperty(EnvironmentPostProcessor.class.getName(), "");
            for (String name : names.split(",")) {
                registered.add(name.trim());
            }
        }

        assertThat(registered).contains(OutboxReplayBinding.class.getName());
    }

    @Test
    @DisplayName("replay off: the environment is left exactly as it was")
    void replayOffLeavesEnvironmentAlone() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "notification")
                .withProperty(DEFINITION, "orderEvents");

        binding.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(DEFINITION)).isEqualTo("orderEvents");
        assertThat(environment.getProperty(BINDING + "destination")).isNull();
        assertThat(environment.getProperty(BINDING + "group")).isNull();
    }

    @Test
    @DisplayName("replay on, a service with no consumers of its own: the listener is the one function, "
            + "on the requests topic, in a group named after the service")
    void replayOnAlone() {
        MockEnvironment environment = replayOn().withProperty("spring.application.name", "order");

        binding.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(DEFINITION)).isEqualTo("outboxReplayRequests");
        assertThat(environment.getProperty(BINDING + "destination")).isEqualTo("oriah.outbox.replay-requests");
        assertThat(environment.getProperty(BINDING + "group")).isEqualTo("order");
    }

    @Test
    @DisplayName("replay on: the listener's Kafka settings are the module's -- dead-lettered to "
            + "oriah.outbox.replay-requests.dlq.<service>, a malformed request never retried, one request per poll, "
            + "five minutes allowed between polls")
    void replayOnKafkaSettings() {
        MockEnvironment environment = replayOn().withProperty("spring.application.name", "order");

        binding.postProcessEnvironment(environment, new SpringApplication());

        KafkaConsumerProperties kafka = kafkaConsumer(environment);
        assertThat(kafka.isEnableDlq()).isTrue();
        assertThat(kafka.getDlqName()).isEqualTo("oriah.outbox.replay-requests.dlq.order");
        assertThat(kafka.getConfiguration()).containsEntry("max.poll.records", "1")
                .containsEntry("max.poll.interval.ms", "300000");
        assertThat(consumer(environment).getRetryableExceptions())
                .containsEntry(IllegalArgumentException.class, false);
    }

    @Test
    @DisplayName("the listener belongs to the module: a service's own value for any of these settings is overridden")
    void moduleSettingsWin() {
        MockEnvironment environment = replayOn().withProperty("spring.application.name", "order")
                .withProperty(KAFKA_BINDING + "enable-dlq", "false")
                .withProperty(KAFKA_BINDING + "dlq-name", "somewhere-else")
                .withProperty(KAFKA_BINDING + "configuration.max.poll.records", "500")
                .withProperty(KAFKA_BINDING + "configuration.max.poll.interval.ms", "60000")
                .withProperty(BINDING + "consumer.retryable-exceptions.java.lang.IllegalArgumentException", "true");

        binding.postProcessEnvironment(environment, new SpringApplication());

        KafkaConsumerProperties kafka = kafkaConsumer(environment);
        assertThat(kafka.isEnableDlq()).isTrue();
        assertThat(kafka.getDlqName()).isEqualTo("oriah.outbox.replay-requests.dlq.order");
        assertThat(kafka.getConfiguration()).containsEntry("max.poll.records", "1")
                .containsEntry("max.poll.interval.ms", "300000");
        assertThat(consumer(environment).getRetryableExceptions())
                .containsEntry(IllegalArgumentException.class, false);
    }

    @Test
    @DisplayName("replay off: none of the listener's Kafka settings are added")
    void replayOffAddsNoKafkaSettings() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", "notification");

        binding.postProcessEnvironment(environment, new SpringApplication());

        KafkaConsumerProperties kafka = kafkaConsumer(environment);
        assertThat(kafka.isEnableDlq()).isFalse();
        assertThat(kafka.getDlqName()).isNull();
        assertThat(kafka.getConfiguration()).isEmpty();
        assertThat(consumer(environment).getRetryableExceptions()).isEmpty();
    }

    /** The listener's Kafka settings, bound as the Kafka binder binds them. */
    private static KafkaConsumerProperties kafkaConsumer(MockEnvironment environment) {
        return Binder.get(environment).bind(ConfigurationPropertyName.adapt(KAFKA_BINDING.substring(0,
                KAFKA_BINDING.length() - 1), '.'), Bindable.of(KafkaConsumerProperties.class))
                .orElseGet(KafkaConsumerProperties::new);
    }

    /** The listener's core consumer settings, bound as Spring Cloud Stream binds them. */
    private static ConsumerProperties consumer(MockEnvironment environment) {
        return Binder.get(environment).bind(ConfigurationPropertyName.adapt(BINDING + "consumer", '.'),
                Bindable.of(ConsumerProperties.class)).orElseGet(ConsumerProperties::new);
    }

    @Test
    @DisplayName("replay on beside the service's own consumers: the listener is added, theirs are kept")
    void replayOnBesideOwnConsumers() {
        MockEnvironment environment = replayOn()
                .withProperty("spring.application.name", "catalog")
                .withProperty(DEFINITION, "orderEvents;stockEvents");

        binding.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(DEFINITION)).isEqualTo("orderEvents;stockEvents;outboxReplayRequests");
    }

    @Test
    @DisplayName("run twice on one environment (a context refresh): the listener is listed once")
    void idempotent() {
        MockEnvironment environment = replayOn()
                .withProperty("spring.application.name", "catalog")
                .withProperty(DEFINITION, "orderEvents");

        binding.postProcessEnvironment(environment, new SpringApplication());
        binding.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(DEFINITION)).isEqualTo("orderEvents;outboxReplayRequests");
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "oriah.events.replay.enabled, on",
            "oriah.events.relay.enabled, yes",
            "oriah.events.outbox.enabled, 1",
            "oriah.events.inbox.enabled, enabled"})
    @DisplayName("a switch set to anything but true or false is refused at startup -- Spring reads 'on' as true "
            + "in one place and false in another, so the listener could be bound with no bean behind it")
    void switchIsTrueOrFalse(String name, String value) {
        MockEnvironment environment = new MockEnvironment().withProperty(name, value);

        assertThatThrownBy(() -> binding.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(name + "=" + value)
                .hasMessageContaining("true or false");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"true", "TRUE", "false", "False"})
    @DisplayName("true and false are accepted in any case, as Spring Boot's own switches accept them")
    void trueAndFalseAccepted(String value) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("oriah.events.outbox.enabled", value)
                .withProperty("oriah.events.relay.enabled", value)
                .withProperty("oriah.events.replay.enabled", value)
                .withProperty("oriah.events.inbox.enabled", value)
                .withProperty("spring.application.name", "order");

        binding.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(BINDING + "destination"))
                .isEqualTo(Boolean.parseBoolean(value) ? "oriah.outbox.replay-requests" : null);
    }

    private static MockEnvironment replayOn() {
        return new MockEnvironment().withProperty("oriah.events.replay.enabled", "true");
    }
}
