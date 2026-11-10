package com.oriah.payment.events;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.ListenerContainerWithDlqAndRetryCustomizer;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.payment.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What Kafka does with a replay request payment cannot answer: it is tried
 * three times in all, then parked on a dead-letter topic of payment's own --
 * never dropped.
 *
 * <p>Why it matters: a consumer that starts late asks payment to send its
 * whole log of charge outcomes again. A request is refused when it is
 * malformed, and a replay step fails when Kafka is briefly down while it
 * runs. Parked, the request can be read and sent again; dropped, the consumer
 * that asked would wait for a replay that never comes. Nothing in the outbox
 * is lost either way; this is about not losing the question.
 *
 * <p><b>Whose settings these are.</b> The listener
 * ({@value #BINDING}) belongs to the event-log module (shared/oriah-events),
 * not to payment. Switching replay on is all payment does; the module binds
 * the listener and sets every one of its Kafka settings itself, in a property
 * source it puts ahead of all of payment's own, because the way a replay is
 * answered depends on them (its README, "Answering a replay request"). So
 * payment sets none of them -- a copy in application.yaml would be a dead
 * setting that only looks like it decides something -- and a value payment
 * did set would lose. Both are pinned here.
 *
 * <p><b>Effective</b> means as the Kafka binder will use them: read from this
 * context's real environment (application.yaml plus the module's settings),
 * bound the way Spring Cloud Stream and its Kafka binder bind them. The test
 * binder runs no Kafka, so nothing here is ever dead-lettered for real; the
 * module's own DeadLetterKafkaTest proves the handling itself on a real
 * broker.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ReplayRequestBindingTest {

    private static final String BINDING = "outboxReplayRequests-in-0";
    private static final String TOPIC = "oriah.outbox.replay-requests";
    private static final String DEAD_LETTER_TOPIC = "oriah.outbox.replay-requests.dlq.payment";

    @Autowired
    private BindingServiceProperties bindings;

    @Autowired
    private ConfigurableApplicationContext context;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the module binds the listener: oriah.outbox.replay-requests, in the consumer group payment")
    void boundByTheModule() {
        BindingProperties binding = bindings.getBindingProperties(BINDING);

        assertThat(binding.getDestination()).isEqualTo(TOPIC);
        // One group per producer service: every payment instance shares it,
        // so a request is answered once, by one of them.
        assertThat(binding.getGroup()).isEqualTo("payment");
    }

    @Test
    @DisplayName("a request that keeps failing is delivered 3 times in all, then parked on "
            + "oriah.outbox.replay-requests.dlq.payment -- by the module's error handler, not the binder's own retry")
    void triedThreeTimesThenParked() {
        // The binder asks this hook, for each binding it starts, whether to
        // do its own retry and dead-lettering. "No" for the listener: the
        // module's handler takes over, the one that never commits a request
        // until the dead-letter topic has it. (The binder's own sender would
        // log a failed dead-letter publish and commit past the request.)
        ListenerContainerWithDlqAndRetryCustomizer deadLetters =
                context.getBean(ListenerContainerWithDlqAndRetryCustomizer.class);
        assertThat(deadLetters.retryAndDlqInBinding(TOPIC, "payment"))
                .as("does the binder keep its own retry for the replay listener?")
                .isFalse();

        // With the module's handler, max-attempts is the number of deliveries
        // IN ALL: the first and two more, each a fresh poll of the same
        // request. (The binder's own retry, which this replaces, gave four.)
        ConsumerProperties consumer = bindings.getConsumerProperties(BINDING);
        assertThat(consumer.getMaxAttempts()).isEqualTo(3);
        // The pauses between them, and after a dead-letter publish that
        // fails: 1 s, then 2 s, and never more than 10 s. These are the
        // binder's defaults, which payment leaves alone; the module's
        // reckoning of how long one replay step can take counts on them
        // (its README, "How long one step may take").
        assertThat(consumer.getBackOffInitialInterval()).isEqualTo(1000);
        assertThat(consumer.getBackOffMultiplier()).isEqualTo(2.0);
        assertThat(consumer.getBackOffMaxInterval()).isEqualTo(10000);

        KafkaConsumerProperties kafka = kafkaConsumerProperties();
        assertThat(kafka.isEnableDlq())
                .as("with no dead-letter topic, a request that keeps failing has nowhere to go")
                .isTrue();
        // <topic>.dlq.<group>, the name every Oriah dead-letter topic has.
        assertThat(kafka.getDlqName())
                .isEqualTo(TOPIC + ".dlq." + bindings.getBindingProperties(BINDING).getGroup())
                .isEqualTo(DEAD_LETTER_TOPIC);
    }

    @Test
    @DisplayName("a malformed request (IllegalArgumentException) is parked on its first delivery, never tried again")
    void malformedRequestParkedAtOnce() {
        // A request with no requestId, or a consumer name that cannot be a
        // topic name, fails the same way every time it is read, so trying it
        // again only delays the requests behind it.
        assertThat(bindings.getConsumerProperties(BINDING).getRetryableExceptions())
                .containsEntry(IllegalArgumentException.class, false);
    }

    @Test
    @DisplayName("one request -- one replay step -- per poll, with five minutes allowed for it")
    void oneStepPerPoll() {
        // Kafka presumes a listener dead when it takes longer than
        // max.poll.interval.ms between two polls, and hands the request to
        // another instance, where it would take just as long. Reading one
        // request per poll makes that time one replay step's, and the module
        // refuses to start unless the slowest step fits in it
        // (StartupChecksPerProfileTest shows payment's does, in every profile).
        assertThat(kafkaConsumerProperties().getConfiguration())
                .containsEntry("max.poll.records", "1")
                .containsEntry("max.poll.interval.ms", "300000");
    }

    @Test
    @DisplayName("payment's own configuration files set none of the listener's settings: the module owns them")
    void paymentSetsNoneOfThem() {
        // Every Kafka setting of the listener, and the three core settings
        // the module gives it. Matched as Spring matches a name, so
        // enable-dlq, enableDlq and ENABLE_DLQ are all the same setting.
        List<ConfigurationPropertyName> moduleOwned = List.of(
                name("spring.cloud.stream.kafka.bindings." + BINDING),
                name("spring.cloud.stream.bindings." + BINDING + ".destination"),
                name("spring.cloud.stream.bindings." + BINDING + ".group"),
                name("spring.cloud.stream.bindings." + BINDING + ".consumer.retryable-exceptions"));

        List<Resource> files = PaymentConfigFiles.all();
        assertThat(files).extracting(Resource::getFilename)
                .contains("application.yaml", "application-local.yaml", "application-docker.yaml");

        List<String> set = new ArrayList<>();
        for (Resource file : files) {
            for (PropertySource<?> document : PaymentConfigFiles.load(file)) {
                if (ConfigurationPropertySource.from(document) instanceof IterableConfigurationPropertySource names) {
                    names.stream()
                            .filter(setting -> moduleOwned.stream()
                                    .anyMatch(owned -> owned.equals(setting) || owned.isAncestorOf(setting)))
                            .forEach(setting -> set.add(file.getFilename() + ": " + setting));
                }
            }
        }
        assertThat(set)
                .as("settings the module owns, set in payment's own files. The module's values win over them, so "
                        + "they decide nothing and only mislead whoever reads them; remove them")
                .isEmpty();
    }

    /**
     * Payment tries to set each of the module's values differently. Test
     * properties outrank every configuration file (application.yaml and its
     * profile files) and every environment variable, so a value that loses
     * here loses from any of those too.
     */
    @Nested
    @TestPropertySource(properties = {
            "spring.cloud.stream.bindings.outboxReplayRequests-in-0.destination=payment.somewhere-else",
            "spring.cloud.stream.bindings.outboxReplayRequests-in-0.group=not-payment",
            "spring.cloud.stream.bindings.outboxReplayRequests-in-0.consumer.retryable-exceptions"
                    + ".java.lang.IllegalArgumentException=true",
            "spring.cloud.stream.kafka.bindings.outboxReplayRequests-in-0.consumer.enable-dlq=false",
            "spring.cloud.stream.kafka.bindings.outboxReplayRequests-in-0.consumer.dlq-name=payment.parked",
            "spring.cloud.stream.kafka.bindings.outboxReplayRequests-in-0.consumer.configuration"
                    + ".max.poll.records=500",
            "spring.cloud.stream.kafka.bindings.outboxReplayRequests-in-0.consumer.configuration"
                    + ".max.poll.interval.ms=600000"})
    class PaymentsOwnValues {

        // This class's own context, the one with payment's values in it. (The
        // fields of the enclosing class are filled from ITS context, which
        // has none of them.)
        @Autowired
        private BindingServiceProperties ownBindings;

        @Autowired
        private ConfigurableApplicationContext ownContext;

        @Test
        @DisplayName("are overridden: the listener keeps the module's settings, whatever payment sets")
        void lose() {
            // Not vacuous: payment's own value really is in this context,
            // behind the module's.
            assertThat(ownContext.getEnvironment().getPropertySources().stream()
                    .map(source -> source.getProperty(
                            "spring.cloud.stream.kafka.bindings." + BINDING + ".consumer.dlq-name")))
                    .contains("payment.parked");

            BindingProperties binding = ownBindings.getBindingProperties(BINDING);
            assertThat(binding.getDestination()).isEqualTo(TOPIC);
            assertThat(binding.getGroup()).isEqualTo("payment");
            assertThat(ownBindings.getConsumerProperties(BINDING).getRetryableExceptions())
                    .containsEntry(IllegalArgumentException.class, false);

            KafkaConsumerProperties kafka = kafkaConsumerProperties(ownContext);
            assertThat(kafka.isEnableDlq()).isTrue();
            assertThat(kafka.getDlqName()).isEqualTo(DEAD_LETTER_TOPIC);
            assertThat(kafka.getConfiguration())
                    .containsEntry("max.poll.records", "1")
                    .containsEntry("max.poll.interval.ms", "300000");
        }
    }

    private KafkaConsumerProperties kafkaConsumerProperties() {
        return kafkaConsumerProperties(context);
    }

    /** Bound the way the Kafka binder binds them: spring.cloud.stream.kafka, defaults merged per binding. */
    private static KafkaConsumerProperties kafkaConsumerProperties(ConfigurableApplicationContext context) {
        KafkaExtendedBindingProperties kafka = new KafkaExtendedBindingProperties();
        Binder.get(context.getEnvironment()).bind("spring.cloud.stream.kafka", Bindable.ofInstance(kafka));
        kafka.setApplicationContext(context);
        return kafka.getExtendedConsumerProperties(BINDING);
    }

    private static ConfigurationPropertyName name(String name) {
        // adapt, not of: a binding name such as outboxReplayRequests-in-0 has capitals, which a strict name refuses.
        return ConfigurationPropertyName.adapt(name, '.');
    }
}
