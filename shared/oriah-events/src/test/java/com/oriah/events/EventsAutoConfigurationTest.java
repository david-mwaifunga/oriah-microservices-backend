package com.oriah.events;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The four switches. Every part of the module is off until a service turns
 * it on, so adding the jar to a service changes nothing by itself; each
 * service sets exactly what it uses.
 *
 * <p>The DataSource, transaction manager and stream sender are stand-ins:
 * this test is about which beans exist, not about what they do (the
 * Testcontainers tests prove that).
 */
class EventsAutoConfigurationTest {

    /** The binder setting the relay and CatchUp need: a send returns only once Kafka has the record. */
    private static final String SYNC_SENDS = "spring.cloud.stream.kafka.default.producer.sync";

    /** The module with nothing set, not even the binder's sync sends. */
    private final ApplicationContextRunner bare = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OutboxAutoConfiguration.class,
                    OutboxRelayAutoConfiguration.class,
                    OutboxReplayAutoConfiguration.class,
                    InboxAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(StreamOperations.class, () -> mock(StreamOperations.class));

    /** As a service sets it up: sync sends on, as the README asks of every service that uses the relay or CatchUp. */
    private final ApplicationContextRunner runner = bare.withPropertyValues(SYNC_SENDS + "=true");

    @Test
    @DisplayName("all four auto-configurations are listed for Spring Boot to find")
    void listedInImports() {
        List<String> listed = new ArrayList<>();
        ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).forEach(listed::add);

        assertThat(listed).contains(
                OutboxAutoConfiguration.class.getName(),
                OutboxRelayAutoConfiguration.class.getName(),
                OutboxReplayAutoConfiguration.class.getName(),
                InboxAutoConfiguration.class.getName());
    }

    @Test
    @DisplayName("with no switch set, the module adds nothing")
    void offByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OutboxWriter.class);
            assertThat(context).doesNotHaveBean(OutboxRelay.class);
            assertThat(context).doesNotHaveBean(OutboxReplayAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(InboxAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(OutboxReplayer.class);
            assertThat(context).doesNotHaveBean(Inbox.class);
            assertThat(context).doesNotHaveBean(CatchUp.class);
        });
    }

    @Test
    @DisplayName("outbox on: the writer, and no relay")
    void outboxOnly() {
        runner.withPropertyValues("oriah.events.outbox.enabled=true", "oriah.events.topics=oriah.order")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxWriter.class);
                    assertThat(context).doesNotHaveBean(OutboxRelay.class);
                });
    }

    @Test
    @DisplayName("outbox and relay on: the writer and the relay")
    void outboxAndRelay() {
        runner.withPropertyValues("oriah.events.outbox.enabled=true", "oriah.events.topics=oriah.order",
                        "oriah.events.relay.enabled=true", "oriah.events.producer=order")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxWriter.class);
                    assertThat(context).hasSingleBean(OutboxRelay.class);
                });
    }

    @Test
    @DisplayName("relay on without the outbox: refused at startup, not a relay with nothing to read")
    void relayNeedsOutbox() {
        runner.withPropertyValues("oriah.events.relay.enabled=true", "oriah.events.producer=order")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("oriah.events.relay.enabled needs oriah.events.outbox.enabled"));
    }

    @Test
    @DisplayName("relay on without a producer name: refused at startup, every message must say who sent it")
    void relayNeedsProducer() {
        runner.withPropertyValues("oriah.events.outbox.enabled=true", "oriah.events.topics=oriah.order",
                        "oriah.events.relay.enabled=true")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("oriah.events.producer"));
    }

    @ParameterizedTest(name = "batch size {0}")
    @ValueSource(strings = {"0", "-1"})
    @DisplayName("a relay batch size under 1 is refused at startup -- at 0 the relay would publish nothing, "
            + "with no error")
    void relayBatchSizeAtLeastOne(String batchSize) {
        runner.withPropertyValues("oriah.events.outbox.enabled=true", "oriah.events.topics=oriah.order",
                        "oriah.events.relay.enabled=true", "oriah.events.producer=order",
                        "oriah.events.relay.batch-size=" + batchSize)
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("oriah.events.relay.batch-size")
                        .hasMessageContaining(batchSize));
    }

    @ParameterizedTest(name = "sync sends {0}")
    @ValueSource(strings = {"unset", "false"})
    @DisplayName("relay on while the binder does not wait for Kafka to take each send: refused at startup -- "
            + "the cursor would move past a row Kafka never took, and that event would never be published")
    void relayNeedsSyncSends(String sync) {
        unsynced(sync).withPropertyValues("oriah.events.outbox.enabled=true", "oriah.events.topics=oriah.order",
                        "oriah.events.relay.enabled=true", "oriah.events.producer=order")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining(SYNC_SENDS + "=true"));
    }

    @ParameterizedTest(name = "sync sends {0}")
    @ValueSource(strings = {"unset", "false"})
    @DisplayName("a catch-up consumer while the binder does not wait for Kafka to take each send: refused at "
            + "startup -- a replay request Kafka never took would look sent, and nothing would ever answer it")
    void catchUpNeedsSyncSends(String sync) {
        unsynced(sync).withPropertyValues("oriah.events.inbox.enabled=true",
                        "oriah.events.inbox.catch-up.consumer=finance",
                        "oriah.events.inbox.catch-up.producers=order")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining(SYNC_SENDS + "=true"));
    }

    @Test
    @DisplayName("the Inbox alone sends nothing, so it needs no sync sends")
    void inboxAloneNeedsNoSyncSends() {
        bare.withPropertyValues("oriah.events.inbox.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(Inbox.class));
    }

    private ApplicationContextRunner unsynced(String sync) {
        return "unset".equals(sync) ? bare : bare.withPropertyValues(SYNC_SENDS + "=" + sync);
    }

    @Test
    @DisplayName("inbox on by itself: the Inbox, and nothing of the producer side")
    void inboxOnly() {
        runner.withPropertyValues("oriah.events.inbox.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(InboxAutoConfiguration.class);
                    assertThat(context).hasSingleBean(Inbox.class);
                    assertThat(context).doesNotHaveBean(OutboxReplayAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(OutboxReplayer.class);
                    assertThat(context).doesNotHaveBean(OutboxWriter.class);
                    // CatchUp is only for a consumer that names itself as one.
                    assertThat(context).doesNotHaveBean(CatchUp.class);
                });
    }

    @Test
    @DisplayName("inbox on with a catch-up consumer and its producers: the CatchUp helper beside the Inbox")
    void catchUpOn() {
        runner.withPropertyValues("oriah.events.inbox.enabled=true",
                        "oriah.events.inbox.catch-up.consumer=finance",
                        "oriah.events.inbox.catch-up.producers=order,payment")
                .run(context -> {
                    assertThat(context).hasSingleBean(Inbox.class);
                    assertThat(context).hasSingleBean(CatchUp.class);
                });
    }

    @Test
    @DisplayName("catch-up settings without the inbox switch: nothing -- CatchUp is part of the inbox")
    void catchUpNeedsInbox() {
        runner.withPropertyValues("oriah.events.inbox.catch-up.consumer=finance",
                        "oriah.events.inbox.catch-up.producers=order")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CatchUp.class);
                });
    }

    @Test
    @DisplayName("a catch-up consumer with no producers: refused at startup, it would never ask anyone")
    void catchUpNeedsProducers() {
        runner.withPropertyValues("oriah.events.inbox.enabled=true", "oriah.events.inbox.catch-up.consumer=finance")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("oriah.events.inbox.catch-up.producers"));
    }

    @Test
    @DisplayName("a catch-up consumer name that cannot be a topic suffix: refused at startup, "
            + "it names the consumer's replay topic")
    void catchUpNeedsAValidConsumerName() {
        runner.withPropertyValues("oriah.events.inbox.enabled=true",
                        "oriah.events.inbox.catch-up.consumer=Fin.ance",
                        "oriah.events.inbox.catch-up.producers=order")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("consumer name"));
    }

    @Test
    @DisplayName("replay on beside the relay: the replayer and its request listener, and no Inbox bean")
    void replayOn() {
        runner.withPropertyValues(REPLAY_READY)
                .withPropertyValues("oriah.events.replay.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxReplayAutoConfiguration.class);
                    assertThat(context).hasSingleBean(OutboxReplayer.class);
                    assertThat(context).hasBean("outboxReplayRequests");
                    assertThat(context).doesNotHaveBean(InboxAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(Inbox.class);
                });
    }

    @Test
    @DisplayName("a replay is answered in steps of oriah.events.replay.chunk-size rows, 10 unless set -- not the "
            + "relay's batch size: a step must fit the time Kafka allows the listener between polls")
    void replayChunkSizeDefault() {
        runner.withPropertyValues(REPLAY_READY)
                .withPropertyValues("oriah.events.replay.enabled=true", "oriah.events.relay.batch-size=100")
                .run(context -> assertThat(context.getBean(OutboxReplayer.class).chunkSize()).isEqualTo(10));
    }

    @Test
    @DisplayName("oriah.events.replay.chunk-size set: each step sends that many rows at most")
    void replayChunkSizeSet() {
        runner.withPropertyValues(REPLAY_READY)
                .withPropertyValues("oriah.events.replay.enabled=true", "oriah.events.replay.chunk-size=3")
                .run(context -> assertThat(context.getBean(OutboxReplayer.class).chunkSize()).isEqualTo(3));
    }

    @ParameterizedTest(name = "chunk size {0}")
    @ValueSource(strings = {"0", "-1"})
    @DisplayName("a replay chunk size under 1 is refused at startup -- a step that sends no row would hand the "
            + "same position on to the next step for ever")
    void replayChunkSizeAtLeastOne(String chunkSize) {
        runner.withPropertyValues(REPLAY_READY)
                .withPropertyValues("oriah.events.replay.enabled=true", "oriah.events.replay.chunk-size=" + chunkSize)
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("oriah.events.replay.chunk-size")
                        .hasMessageContaining(chunkSize));
    }

    @Test
    @DisplayName("replay on without the relay: refused at startup, a replay re-sends what the relay publishes")
    void replayNeedsRelay() {
        runner.withPropertyValues("oriah.events.outbox.enabled=true", "oriah.events.topics=oriah.order",
                        "oriah.events.producer=order", "spring.application.name=order",
                        "oriah.events.replay.enabled=true")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("oriah.events.replay.enabled needs oriah.events.relay.enabled"));
    }

    @Test
    @DisplayName("replay on without spring.application.name: refused at startup, it names the request listener's group")
    void replayNeedsApplicationName() {
        runner.withPropertyValues("oriah.events.outbox.enabled=true", "oriah.events.topics=oriah.order",
                        "oriah.events.relay.enabled=true", "oriah.events.producer=order",
                        "oriah.events.replay.enabled=true")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("oriah.events.replay.enabled needs spring.application.name"));
    }

    /**
     * Everything replay needs around it: the outbox, the relay, a producer, an
     * application name, and the README's send timeouts (a replay step must fit
     * the time Kafka allows its listener between polls: see ReplayStepTime).
     */
    private static final String[] REPLAY_READY = {
            "oriah.events.outbox.enabled=true", "oriah.events.topics=oriah.order",
            "oriah.events.relay.enabled=true", "oriah.events.producer=order",
            "spring.application.name=order",
            "spring.cloud.stream.kafka.binder.producer-properties.max.block.ms=10000",
            "spring.cloud.stream.kafka.binder.producer-properties.delivery.timeout.ms=10000"};
}
