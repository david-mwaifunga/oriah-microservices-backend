package com.oriah.events;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The time rule for a replay step, checked when the service starts.
 *
 * <p>Why: Kafka allows the replay-request listener {@code max.poll.interval.ms}
 * between two polls (the module sets 300000, five minutes). A listener that
 * takes longer is presumed dead; Kafka hands the request to another instance,
 * which takes just as long -- for ever. With one request per poll, that time
 * is one step: up to {@code chunk-size} row sends plus one continuation or
 * marker, and a send Kafka does not answer can wait {@code max.block.ms} and
 * then {@code delivery.timeout.ms}; a step that fails also spends 25 s on its
 * dead-letter publish and then the listener's back-off-max-interval (10 s by
 * default) on the same thread. So the service refuses to start unless
 * {@code (chunk-size + 1) x (max.block.ms + delivery.timeout.ms) + 25 s + back-off-max-interval
 * < max.poll.interval.ms}, read as the Kafka binder will actually use them: a
 * timeout missing everywhere is Kafka's own default (60000 and 120000).
 *
 * <p>Each context is the module's producer side, with the binding settings
 * the module adds itself ({@link OutboxReplayBinding}) unless a test says
 * otherwise; the DataSource, transaction manager and sender are stand-ins.
 */
class ReplayStepTimeTest {

    private static final String PRODUCER_PROPERTIES = "spring.cloud.stream.kafka.binder.producer-properties.";

    /** A producing service with replay on, sync sends, and no send timeouts yet. */
    private final ApplicationContextRunner withoutModuleBinding = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OutboxAutoConfiguration.class,
                    OutboxRelayAutoConfiguration.class,
                    OutboxReplayAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(StreamOperations.class, () -> mock(StreamOperations.class))
            .withPropertyValues(
                    "spring.application.name=order",
                    "spring.cloud.stream.kafka.default.producer.sync=true",
                    "oriah.events.outbox.enabled=true", "oriah.events.topics=oriah.order",
                    "oriah.events.relay.enabled=true", "oriah.events.producer=order",
                    "oriah.events.replay.enabled=true");

    /** The same, with the listener's settings the module adds, as in a real service. */
    private final ApplicationContextRunner producer = withoutModuleBinding
            .withInitializer(context -> new OutboxReplayBinding()
                    .postProcessEnvironment(context.getEnvironment(), new SpringApplication()));

    /** The send timeouts the README asks every producing service to set. */
    private static final String[] HOUSE = {PRODUCER_PROPERTIES + "max.block.ms=10000",
            PRODUCER_PROPERTIES + "request.timeout.ms=5000", PRODUCER_PROPERTIES + "delivery.timeout.ms=10000"};

    @Test
    @DisplayName("the README's send timeouts and the default chunk size: (10 + 1) x 20 s = 220 s of sends plus a "
            + "failed step's 25 s + 10 s = 255 s, under the listener's 300 s -- the service starts")
    void houseSettingsStart() {
        producer.withPropertyValues(HOUSE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OutboxReplayer.class);
                });
    }

    @Test
    @DisplayName("no send timeouts set: Kafka's own 60 s and 120 s make a step up to (10 + 1) x 180 s = 1980 s, "
            + "past 300 s -- refused, saying where each number came from and what to change")
    void kafkaDefaultsRefused() {
        producer.run(context -> assertThat(context).getFailure().rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oriah-events refuses to start")
                .hasMessageContaining("(10 + 1) x (60000 + 120000) ms = 1980000 ms")
                .hasMessageContaining("max.poll.interval.ms 300000 ms")
                .hasMessageContaining("max.block.ms 60000 (Kafka's default")
                .hasMessageContaining("delivery.timeout.ms 120000 (Kafka's default")
                .hasMessageContaining("oriah.events.replay.chunk-size")
                .hasMessageContaining("spring.cloud.stream.kafka.binder.producer-properties"));
    }

    @ParameterizedTest(name = "chunk size {0}")
    @ValueSource(ints = {12, 13})
    @DisplayName("the edge, with the README's timeouts: 12 is (12 + 1) x 20 s = 260 s plus a failed step's "
            + "25 s dead-letter publish and 10 s pause = 295 s and starts; 13 is 280 + 25 + 10 = 315 s, not under "
            + "300 s, and is refused")
    void edge(int chunkSize) {
        producer.withPropertyValues(HOUSE)
                .withPropertyValues("oriah.events.replay.chunk-size=" + chunkSize)
                .run(context -> {
                    if (chunkSize == 12) {
                        assertThat(context).hasNotFailed();
                    } else {
                        assertThat(context).getFailure().rootCause()
                                .hasMessageContaining("(13 + 1) x (10000 + 10000) ms = 280000 ms")
                                .hasMessageContaining("315000 ms")
                                .hasMessageContaining("12 or less");
                    }
                });
    }

    @Test
    @DisplayName("a step that fails also waits: its dead-letter publish (up to 25 s) and the pause after a failed "
            + "one (the listener's back-off-max-interval) are counted, and the refusal shows them in the sum")
    void refusalCountsAFailedStepsWaits() {
        producer.withPropertyValues(HOUSE)
                .withPropertyValues("oriah.events.replay.chunk-size=13")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("280000 ms for the sends")
                        .hasMessageContaining("25000 ms for the dead-letter publish of a step that fails")
                        .hasMessageContaining("10000 ms for the pause after a failed publish "
                                + "(back-off-max-interval 10000 ms")
                        .hasMessageContaining("= 315000 ms")
                        .hasMessageContaining("which is not under max.poll.interval.ms 300000 ms")
                        .hasMessageContaining("How long one step may take"));
    }

    @Test
    @DisplayName("the pause is the replay listener's own back-off-max-interval: a service that raises it to 60 s "
            + "makes (10 + 1) x 20 s + 25 s + 60 s = 305 s -- refused, though the sends alone fit")
    void aLongerPauseIsCounted() {
        producer.withPropertyValues(HOUSE)
                .withPropertyValues("spring.cloud.stream.bindings.outboxReplayRequests-in-0.consumer"
                        + ".back-off-max-interval=60000")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("60000 ms for the pause after a failed publish "
                                + "(back-off-max-interval 60000 ms (spring.cloud.stream.bindings."
                                + "outboxReplayRequests-in-0.consumer)")
                        .hasMessageContaining("= 305000 ms"));
    }

    @Test
    @DisplayName("the pause is read from the default consumer settings when the binding says nothing, and a "
            + "binding's own value wins over them")
    void pausePrecedence() {
        producer.withPropertyValues(HOUSE)
                .withPropertyValues("spring.cloud.stream.default.consumer.back-off-max-interval=60000")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("back-off-max-interval 60000 ms (spring.cloud.stream.default.consumer)"));
        producer.withPropertyValues(HOUSE)
                .withPropertyValues("spring.cloud.stream.default.consumer.back-off-max-interval=60000",
                        "spring.cloud.stream.bindings.outboxReplayRequests-in-0.consumer.back-off-max-interval=10000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "spring.kafka.properties.",
            "spring.kafka.producer.properties.",
            "spring.cloud.stream.kafka.binder.configuration.",
            "spring.cloud.stream.kafka.binder.producer-properties.",
            "spring.cloud.stream.kafka.default.producer.configuration."})
    @DisplayName("the send timeouts are read wherever the Kafka binder takes them from")
    void producerTimeoutsReadFromEveryLayer(String layer) {
        producer.withPropertyValues(layer + "max.block.ms=10000", layer + "delivery.timeout.ms=10000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("the timeouts written with brackets around the Kafka name, as payment writes them "
            + "(\"[max.block.ms]\": 10000), are read the same")
    void bracketedNames() {
        producer.withPropertyValues(
                        "spring.cloud.stream.kafka.binder.producer-properties[max.block.ms]=10000",
                        "spring.cloud.stream.kafka.binder.producer-properties[delivery.timeout.ms]=10000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("a higher layer wins, as in the binder: spring.cloud.stream.kafka.default.producer.configuration "
            + "over the binder's producer-properties, and those over the binder's configuration")
    void producerPrecedence() {
        producer.withPropertyValues(HOUSE)
                .withPropertyValues("spring.cloud.stream.kafka.default.producer.configuration.delivery.timeout.ms=120000")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("delivery.timeout.ms 120000 "
                                + "(spring.cloud.stream.kafka.default.producer.configuration)"));
        producer.withPropertyValues(HOUSE)
                .withPropertyValues("spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms=120000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "spring.kafka.properties.max.poll.interval.ms=200000",
            "spring.kafka.consumer.max-poll-interval=200s",
            "spring.kafka.consumer.properties.max.poll.interval.ms=200000",
            "spring.cloud.stream.kafka.binder.configuration.max.poll.interval.ms=200000",
            "spring.cloud.stream.kafka.binder.consumer-properties.max.poll.interval.ms=200000",
            "spring.cloud.stream.kafka.default.consumer.configuration.max.poll.interval.ms=200000",
            "spring.cloud.stream.kafka.bindings.outboxReplayRequests-in-0.consumer.configuration.max.poll.interval.ms=200000"})
    @DisplayName("the listener's poll interval is read wherever the Kafka binder takes it from: 200 s, under the "
            + "220 s a step may take, is refused")
    void pollIntervalReadFromEveryLayer(String setting) {
        withoutModuleBinding.withPropertyValues(HOUSE)
                .withPropertyValues(setting)
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("max.poll.interval.ms 200000 ms"));
    }

    @Test
    @DisplayName("the module's own 300 s on the listener's binding is what the binder uses: a service's shorter "
            + "interval for all its consumers does not reach it")
    void moduleIntervalWins() {
        producer.withPropertyValues(HOUSE)
                .withPropertyValues("spring.cloud.stream.kafka.binder.consumer-properties.max.poll.interval.ms=200000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("a timeout that is not a whole number of milliseconds is refused, naming where it is set")
    void notANumber() {
        producer.withPropertyValues(PRODUCER_PROPERTIES + "max.block.ms=ten seconds",
                        PRODUCER_PROPERTIES + "delivery.timeout.ms=10000")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("max.block.ms")
                        .hasMessageContaining("spring.cloud.stream.kafka.binder.producer-properties")
                        .hasMessageContaining("ten seconds"));
    }
}
