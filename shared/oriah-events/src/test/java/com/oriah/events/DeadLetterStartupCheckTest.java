package com.oriah.events;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.binder.kafka.ListenerContainerWithDlqAndRetryCustomizer;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.cloud.stream.config.ListenerContainerCustomizer;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The startup refusal: every setting the module's dead-letter handling
 * depends on is checked before any binding starts.
 *
 * <p>Why at startup and not only when the binder sets a binding up: a refusal
 * thrown there does not stop the service. Spring Cloud Stream catches it,
 * leaves the binding unbound and tries again every 30 seconds, so the
 * service would run with that topic unread and nothing but a log line to
 * say so.
 *
 * <p>Each context here is Spring Cloud Stream's own binding configuration
 * (with its test binder, which never connects anywhere) plus the module, so
 * the binding settings are read exactly as a service's are. The DataSource,
 * transaction manager and sender are stand-ins: nothing here touches them.
 */
class DeadLetterStartupCheckTest {

    private static final String BYTES = ByteArrayDeserializer.class.getName();
    private static final String STRINGS = StringDeserializer.class.getName();

    /** Every binding setting below is for this one, notification's live binding. */
    private static final String BINDING = "spring.cloud.stream.bindings.orderEvents-in-0.";
    private static final String KAFKA = "spring.cloud.stream.kafka.bindings.orderEvents-in-0.consumer.";
    private static final String KAFKA_DEFAULT = "spring.cloud.stream.kafka.default.consumer.";

    /** The module and Spring Cloud Stream, with nothing switched on. */
    private final ApplicationContextRunner bare = TestChannelBinderConfiguration.applicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OutboxAutoConfiguration.class,
                    OutboxRelayAutoConfiguration.class,
                    OutboxReplayAutoConfiguration.class,
                    InboxAutoConfiguration.class,
                    DeadLetterAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(StreamOperations.class, () -> mock(StreamOperations.class))
            .withPropertyValues("spring.cloud.stream.kafka.default.producer.sync=true");

    /** Notification as it should be set up: the Inbox on oriah.order, dead-lettering on. */
    private final ApplicationContextRunner notification = bare.withPropertyValues(
            "oriah.events.inbox.enabled=true",
            "oriah.events.inbox.topics=oriah.order",
            BINDING + "destination=oriah.order",
            BINDING + "group=notification",
            KAFKA + "enable-dlq=true",
            KAFKA + "dlq-name=oriah.order.dlq.notification");

    @Nested
    @DisplayName("when the module's dead-letter handling is there at all")
    class Presence {

        @Test
        @DisplayName("listed for Spring Boot to find")
        void listedInImports() {
            List<String> listed = new ArrayList<>();
            ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).forEach(listed::add);

            assertThat(listed).contains(DeadLetterAutoConfiguration.class.getName());
        }

        @Test
        @DisplayName("Inbox on, set up as notification is: the service starts with the module's hook for the "
                + "Kafka binder")
        void inboxOn() {
            notification.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ListenerContainerWithDlqAndRetryCustomizer.class);
            });
        }

        @Test
        @DisplayName("a container customizer of the service's own beside the module's: refused -- the Kafka binder "
                + "takes only one, so the module's dead-letter handling could be the one left out")
        void ownCustomizerRefused() {
            notification.withBean("ownCustomizer", ListenerContainerCustomizer.class,
                            () -> (container, destination, group) -> {
                            })
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("ownCustomizer")
                            .hasMessageContaining("ListenerContainerCustomizer"));
        }

        @Test
        @DisplayName("neither the Inbox nor replay on (a service that only publishes): no hook, nothing checked")
        void nothingOn() {
            bare.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ListenerContainerWithDlqAndRetryCustomizer.class);
            });
        }

        @Test
        @DisplayName("no Kafka binder on the classpath: the module still starts, with no hook and nothing checked")
        void withoutKafkaBinder() {
            bare.withClassLoader(new FilteredClassLoader(ListenerContainerWithDlqAndRetryCustomizer.class))
                    .withPropertyValues("oriah.events.inbox.enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(Inbox.class);
                        assertThat(context).doesNotHaveBean(DeadLetterCustomizer.class);
                    });
        }
    }

    @Nested
    @DisplayName("which bindings are the module's")
    class Bindings {

        @Test
        @DisplayName("Inbox on with no oriah.events.inbox.topics: refused -- the module would not know which "
                + "bindings to make safe")
        void inboxTopicsRequired() {
            bare.withPropertyValues("oriah.events.inbox.enabled=true")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("oriah.events.inbox.topics"));
        }

        @Test
        @DisplayName("an Inbox topic no input binding reads (an output binding to it does not count): refused, "
                + "it is most likely a typo")
        void inboxTopicUnread() {
            bare.withPropertyValues(
                            "oriah.events.inbox.enabled=true",
                            "oriah.events.inbox.topics=oriah.order",
                            "spring.cloud.stream.bindings.orderEvents-out-0.destination=oriah.order")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("oriah.order")
                            .hasMessageContaining("no input binding"));
        }

        @Test
        @DisplayName("an Inbox topic read by two input bindings: refused, it must be exactly one")
        void inboxTopicReadTwice() {
            notification.withPropertyValues(
                            "spring.cloud.stream.bindings.orderAudit-in-0.destination=oriah.order",
                            "spring.cloud.stream.bindings.orderAudit-in-0.group=notification-audit")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("2 input bindings")
                            .hasMessageContaining("orderEvents-in-0")
                            .hasMessageContaining("orderAudit-in-0"));
        }

        @Test
        @DisplayName("a binding that is not the module's is not checked for the module's settings")
        void otherBindingsUnchecked() {
            notification.withPropertyValues(
                            "spring.cloud.stream.bindings.paymentEvents-in-0.destination=oriah.payment",
                            "spring.cloud.stream.bindings.paymentEvents-in-0.consumer.max-attempts=1",
                            "spring.cloud.stream.bindings.paymentEvents-in-0.consumer.use-native-decoding=true")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "spring.cloud.stream.kafka.bindings.paymentEvents-in-0.consumer.enable-dlq=true",
                KAFKA_DEFAULT + "enable-dlq=true"})
        @DisplayName("a binding of the service's own in batch mode with enable-dlq: refused -- while the module's hook "
                + "is there, the Kafka binder dead-letters no batch itself and leaves it to the hook, which takes only "
                + "the module's bindings, so a failing batch would be retried and then committed: dropped")
        void otherBatchBindingWithDeadLettering(String enableDlq) {
            notification.withPropertyValues(
                            "spring.cloud.stream.bindings.paymentEvents-in-0.destination=oriah.payment",
                            "spring.cloud.stream.bindings.paymentEvents-in-0.group=notification",
                            "spring.cloud.stream.bindings.paymentEvents-in-0.consumer.batch-mode=true",
                            enableDlq)
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("binding paymentEvents-in-0 (oriah.payment)")
                            .hasMessageContaining("batch-mode")
                            .hasMessageContaining("enable-dlq"));
        }

        @Test
        @DisplayName("a binding of the service's own in batch mode without enable-dlq: the module changes nothing "
                + "for it, so it is not refused")
        void otherBatchBindingWithoutDeadLettering() {
            notification.withPropertyValues(
                            "spring.cloud.stream.bindings.paymentEvents-in-0.destination=oriah.payment",
                            "spring.cloud.stream.bindings.paymentEvents-in-0.group=notification",
                            "spring.cloud.stream.bindings.paymentEvents-in-0.consumer.batch-mode=true")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("CatchUp on with no input binding for oriah.replay.<consumer>: refused")
        void catchUpTopicUnread() {
            notification.withPropertyValues(
                            "oriah.events.inbox.catch-up.consumer=notification",
                            "oriah.events.inbox.catch-up.producers=order")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("oriah.replay.notification")
                            .hasMessageContaining("no input binding"));
        }

        @Test
        @DisplayName("CatchUp's replay binding is the module's too: without dead-lettering it is refused")
        void catchUpBindingChecked() {
            notification.withPropertyValues(
                            "oriah.events.inbox.catch-up.consumer=notification",
                            "oriah.events.inbox.catch-up.producers=order",
                            "spring.cloud.stream.bindings.replay-in-0.destination=oriah.replay.notification",
                            "spring.cloud.stream.bindings.replay-in-0.group=notification")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("replay-in-0")
                            .hasMessageContaining("enable-dlq"));
        }

        @Test
        @DisplayName("replay on: the replay-request binding the module adds is checked like any of its bindings; "
                + "the dead-letter settings the module gives it pass, with nothing from the service")
        void replayRequestBinding() {
            ApplicationContextRunner producer = bare
                    .withInitializer(context -> new OutboxReplayBinding()
                            .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
                    .withPropertyValues(
                            "spring.application.name=order",
                            "oriah.events.outbox.enabled=true", "oriah.events.topics=oriah.order",
                            "oriah.events.relay.enabled=true", "oriah.events.producer=order",
                            "oriah.events.replay.enabled=true",
                            // The README's send timeouts, which replay needs (see ReplayStepTime).
                            "spring.cloud.stream.kafka.binder.producer-properties.max.block.ms=10000",
                            "spring.cloud.stream.kafka.binder.producer-properties.delivery.timeout.ms=10000");

            // enable-dlq and dlq-name come from the module (OutboxReplayBinding).
            producer.run(context -> assertThat(context).hasNotFailed());
            // Still checked: a setting the module leaves to the service, set wrong, is refused for this binding.
            producer.withPropertyValues("spring.cloud.stream.bindings.outboxReplayRequests-in-0.consumer.max-attempts=1")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("outboxReplayRequests-in-0")
                            .hasMessageContaining("max-attempts 1"));
        }
    }

    @Nested
    @DisplayName("what each of the module's bindings must have")
    class Settings {

        @Test
        @DisplayName("a consumer group: Kafka keeps no dead-letter topic for an anonymous subscription")
        void group() {
            notification.withPropertyValues(BINDING + "group=")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("orderEvents-in-0")
                            .hasMessageContaining("group"));
        }

        @Test
        @DisplayName("enable-dlq true")
        void enableDlq() {
            notification.withPropertyValues(KAFKA + "enable-dlq=false")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("enable-dlq"));
        }

        @Test
        @DisplayName("a dlq-name")
        void dlqName() {
            notification.withPropertyValues(KAFKA + "dlq-name=")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("dlq-name"));
        }

        @Test
        @DisplayName("a dlq-name that is not the topic the binding reads: for a failure Spring Kafka counts as never "
                + "retryable, its dead-letter publisher skips a publish back to the same topic, and the record "
                + "would then be committed -- dropped")
        void dlqNameNotItsOwnTopic() {
            notification.withPropertyValues(KAFKA + "dlq-name=oriah.order")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("binding orderEvents-in-0 (oriah.order)")
                            .hasMessageContaining("dlq-name oriah.order")
                            .hasMessageContaining("the topic it reads"));
        }

        @Test
        @DisplayName("no native decoding: the body must reach the handler as the bytes Kafka holds")
        void nativeDecoding() {
            notification.withPropertyValues(BINDING + "consumer.use-native-decoding=true")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("use-native-decoding"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                KAFKA + "configuration.value.deserializer",
                KAFKA_DEFAULT + "configuration.value.deserializer",
                "spring.cloud.stream.kafka.binder.configuration.value.deserializer",
                "spring.cloud.stream.kafka.binder.consumer-properties.value.deserializer",
                "spring.kafka.consumer.value-deserializer",
                "spring.kafka.consumer.properties.value.deserializer",
                "spring.kafka.properties.value.deserializer"})
        @DisplayName("no value deserializer but bytes, wherever it is set")
        void valueDeserializer(String property) {
            notification.withPropertyValues(property + "=" + STRINGS)
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("value deserializer")
                            .hasMessageContaining(STRINGS));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                KAFKA + "configuration.key.deserializer",
                "spring.kafka.consumer.key-deserializer"})
        @DisplayName("no key deserializer but bytes either")
        void keyDeserializer(String property) {
            notification.withPropertyValues(property + "=" + STRINGS)
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("key deserializer")
                            .hasMessageContaining(STRINGS));
        }

        @Test
        @DisplayName("bytes named explicitly is fine -- the Kafka binder itself sets Spring Kafka's default to it")
        void bytesNamedExplicitly() {
            notification.withPropertyValues(
                            "spring.kafka.consumer.key-deserializer=" + BYTES,
                            "spring.kafka.consumer.value-deserializer=" + BYTES)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("max-attempts 2 or more: with 1 the binder dead-letters through its own sender, which drops "
                + "the message if that publish fails")
        void maxAttempts() {
            notification.withPropertyValues(BINDING + "consumer.max-attempts=1")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("max-attempts"));
        }

        @Test
        @DisplayName("no batch mode")
        void batchMode() {
            notification.withPropertyValues(BINDING + "consumer.batch-mode=true")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("batch-mode"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "spring.cloud.stream.kafka.binder.transaction.transaction-id-prefix=tx-",
                KAFKA + "transaction-manager=kafkaTransactionManager"})
        @DisplayName("no Kafka transactions: the binder then dead-letters through its own after-rollback step")
        void transactions(String setting) {
            notification.withPropertyValues(setting)
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("transaction"));
        }

        @Test
        @DisplayName("retryable-exceptions lists Exceptions only")
        void retryableExceptions() {
            notification.withPropertyValues(BINDING + "consumer.retryable-exceptions.java.lang.OutOfMemoryError=false")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("retryable-exceptions")
                            .hasMessageContaining("java.lang.OutOfMemoryError"));
        }

        @Test
        @DisplayName("every problem is named in one refusal, so one start shows them all")
        void allAtOnce() {
            notification.withPropertyValues(KAFKA + "enable-dlq=false", BINDING + "consumer.max-attempts=1")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("enable-dlq")
                            .hasMessageContaining("max-attempts"));
        }
    }

    /**
     * When a dead-letter publish fails, the module logs one ERROR and waits
     * the binding's back-off-max-interval before the record comes round again
     * (DeadLetterThrottle), on the thread that polls Kafka. The publish itself
     * can take up to 25 s first. If that wait and that publish took a large
     * share of {@code max.poll.interval.ms}, a slow listener on top of them
     * would push the next poll past it: Kafka takes the partition away and the
     * record comes back again, for ever. So the two together may use at most
     * half of the interval, which leaves the other half for the listener's own
     * work (a database wait of up to 30 s, say).
     */
    @Nested
    @DisplayName("the pause after a failed dead-letter publish, plus the publish, may use at most half of "
            + "max.poll.interval.ms")
    class PauseFitsPollInterval {

        private static final String BACK_OFF_MAX = BINDING + "consumer.back-off-max-interval";
        private static final String POLL_INTERVAL = KAFKA + "configuration.max.poll.interval.ms";

        @Test
        @DisplayName("a pause of 200 s: 25 s + 200 s is more than half of 300 s -- refused, naming the binding, "
                + "both numbers and what to change")
        void longPauseRefused() {
            notification.withPropertyValues(BACK_OFF_MAX + "=200000")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("binding orderEvents-in-0 (oriah.order)")
                            .hasMessageContaining("back-off-max-interval 200000 ms")
                            .hasMessageContaining("up to 25000 ms")
                            .hasMessageContaining("max.poll.interval.ms of 300000 ms")
                            .hasMessageContaining("half")
                            .hasMessageContaining("125000 ms or less"));
        }

        @Test
        @DisplayName("the edge: 25 s + 125 s is exactly half of 300 s and starts; 126 s is over it and is refused")
        void edge() {
            notification.withPropertyValues(BACK_OFF_MAX + "=125000")
                    .run(context -> assertThat(context).hasNotFailed());
            notification.withPropertyValues(BACK_OFF_MAX + "=126000")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("back-off-max-interval 126000 ms"));
        }

        @Test
        @DisplayName("the poll interval is the binding's own when it sets one: 60 s allows 30 s, and the default "
                + "pause of 10 s plus the 25 s publish is 35 s -- refused")
        void bindingsOwnPollInterval() {
            notification.withPropertyValues(POLL_INTERVAL + "=60000")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("max.poll.interval.ms of 60000 ms"));
        }

        @Test
        @DisplayName("the pause is read from the default consumer settings when the binding is silent, and the "
                + "binding's own value wins over them")
        void pauseFromTheDefaultsAndPrecedence() {
            notification.withPropertyValues("spring.cloud.stream.default.consumer.back-off-max-interval=200000")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("back-off-max-interval 200000 ms"));
            notification.withPropertyValues("spring.cloud.stream.default.consumer.back-off-max-interval=200000",
                            BACK_OFF_MAX + "=10000")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("every binding of the module's is held to it, CatchUp's replay binding too")
        void catchUpBindingToo() {
            notification.withPropertyValues(
                            "oriah.events.inbox.catch-up.consumer=notification",
                            "oriah.events.inbox.catch-up.producers=order",
                            "spring.cloud.stream.bindings.replay-in-0.destination=oriah.replay.notification",
                            "spring.cloud.stream.bindings.replay-in-0.group=notification",
                            "spring.cloud.stream.kafka.bindings.replay-in-0.consumer.enable-dlq=true",
                            "spring.cloud.stream.kafka.bindings.replay-in-0.consumer.dlq-name="
                                    + "oriah.replay.notification.dlq.notification",
                            "spring.cloud.stream.bindings.replay-in-0.consumer.back-off-max-interval=200000")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("binding replay-in-0 (oriah.replay.notification)")
                            .hasMessageContaining("back-off-max-interval 200000 ms"));
        }
    }

    @Nested
    @DisplayName("one Kafka binder only: the module reads the Kafka settings from the service's configuration, "
            + "which with several binders may not be the ones a binding uses")
    class OneKafkaBinder {

        @Test
        @DisplayName("two binders defined: refused")
        void twoBinders() {
            notification.withPropertyValues(
                            "spring.cloud.stream.binders.kafka-a.type=kafka",
                            "spring.cloud.stream.binders.kafka-b.type=kafka")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("one Kafka binder"));
        }

        @Test
        @DisplayName("the one binder defined is not Kafka: refused")
        void notKafka() {
            notification.withPropertyValues("spring.cloud.stream.binders.rabbit.type=rabbit")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("one Kafka binder"));
        }

        @Test
        @DisplayName("a default binder that is not Kafka: refused")
        void defaultNotKafka() {
            notification.withPropertyValues("spring.cloud.stream.default-binder=rabbit")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("one Kafka binder"));
        }

        @Test
        @DisplayName("one binder of type kafka, named and made the default: fine")
        void oneNamedKafkaBinder() {
            notification.withPropertyValues(
                            "spring.cloud.stream.binders.main.type=kafka",
                            "spring.cloud.stream.default-binder=main")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("the one Kafka binder with an environment of its own: refused -- the binder reads its Kafka "
                + "settings there before the service's, so the module would check settings the binder does not use "
                + "(here: enable-dlq turned off for the module's binding)")
        void binderEnvironment() {
            notification.withPropertyValues(
                            "spring.cloud.stream.binders.main.type=kafka",
                            "spring.cloud.stream.default-binder=main",
                            "spring.cloud.stream.binders.main.environment.spring.cloud.stream.kafka.bindings."
                                    + "orderEvents-in-0.consumer.enable-dlq=false")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("spring.cloud.stream.binders.main.environment")
                            .hasMessageContaining("one Kafka binder"));
        }

        @Test
        @DisplayName("replay on, the send timeouts raised in the binder's environment: refused too -- the replay "
                + "step-time rule would read the service's 10 s while the binder used Kafka's 60 s and 120 s")
        void binderEnvironmentWithReplay() {
            String environment = "spring.cloud.stream.binders.main.environment.spring.cloud.stream.kafka.binder."
                    + "producer-properties.";
            bare.withInitializer(context -> new OutboxReplayBinding()
                            .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
                    .withPropertyValues(
                            "spring.application.name=order",
                            "oriah.events.outbox.enabled=true", "oriah.events.topics=oriah.order",
                            "oriah.events.relay.enabled=true", "oriah.events.producer=order",
                            "oriah.events.replay.enabled=true",
                            "spring.cloud.stream.kafka.binder.producer-properties.max.block.ms=10000",
                            "spring.cloud.stream.kafka.binder.producer-properties.delivery.timeout.ms=10000",
                            "spring.cloud.stream.binders.main.type=kafka",
                            "spring.cloud.stream.default-binder=main",
                            environment + "max.block.ms=60000",
                            environment + "delivery.timeout.ms=120000")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("spring.cloud.stream.binders.main.environment")
                            .hasMessageContaining("one Kafka binder"));
        }
    }

    @Nested
    @DisplayName("a binding's own setting wins over the default, and the default applies when the binding says "
            + "nothing -- the same precedence the Kafka binder uses")
    class Precedence {

        @Test
        @DisplayName("default enable-dlq true, the binding silent: the default applies, fine")
        void kafkaDefaultApplies() {
            bare.withPropertyValues(
                            "oriah.events.inbox.enabled=true",
                            "oriah.events.inbox.topics=oriah.order",
                            BINDING + "destination=oriah.order",
                            BINDING + "group=notification",
                            KAFKA_DEFAULT + "enable-dlq=true",
                            KAFKA + "dlq-name=oriah.order.dlq.notification")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("default enable-dlq true, the binding false: the binding wins, refused")
        void kafkaBindingWins() {
            notification.withPropertyValues(KAFKA_DEFAULT + "enable-dlq=true", KAFKA + "enable-dlq=false")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("enable-dlq"));
        }

        @Test
        @DisplayName("default value deserializer String, the binding's bytes: the binding wins, fine")
        void deserializerBindingWins() {
            notification.withPropertyValues(
                            KAFKA_DEFAULT + "configuration.value.deserializer=" + STRINGS,
                            KAFKA + "configuration.value.deserializer=" + BYTES)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("the binder's value deserializer String, the binding's bytes: the binding wins, fine")
        void deserializerBindingWinsOverBinder() {
            notification.withPropertyValues(
                            "spring.cloud.stream.kafka.binder.consumer-properties.value.deserializer=" + STRINGS,
                            KAFKA + "configuration.value.deserializer=" + BYTES)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("default max-attempts 1, the binding's 3: the binding wins, fine")
        void coreBindingWins() {
            notification.withPropertyValues(
                            "spring.cloud.stream.default.consumer.max-attempts=1",
                            BINDING + "consumer.max-attempts=3")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("default max-attempts 1, the binding silent: the default applies, refused")
        void coreDefaultApplies() {
            notification.withPropertyValues("spring.cloud.stream.default.consumer.max-attempts=1")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("max-attempts"));
        }

        @Test
        @DisplayName("relaxed names are read too: enableDlq and dlqName in camel case")
        void relaxedNames() {
            bare.withPropertyValues(
                            "oriah.events.inbox.enabled=true",
                            "oriah.events.inbox.topics=oriah.order",
                            BINDING + "destination=oriah.order",
                            BINDING + "group=notification",
                            KAFKA + "enableDlq=true",
                            KAFKA + "dlqName=oriah.order.dlq.notification")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }
}
