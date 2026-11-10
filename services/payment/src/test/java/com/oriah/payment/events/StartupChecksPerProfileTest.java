package com.oriah.payment.events;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.kafka.ListenerContainerWithDlqAndRetryCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.oriah.events.EventsProperties;
import com.oriah.events.OutboxReplayer;
import com.oriah.payment.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payment starts in every profile it ships -- past every check the event-log
 * module (shared/oriah-events) makes when a service starts.
 *
 * <p><b>The checks.</b> Payment publishes events and answers replay requests
 * (outbox, relay and replay on, Inbox off), so when it starts the module
 * refuses to go on unless:
 * <ul>
 *   <li>each of its four switches is exactly true or false;</li>
 *   <li>the replay-request listener can be dead-lettered safely: it has a
 *       consumer group, dead-lettering on with a topic that is not the one
 *       it reads, more than one delivery allowed, no batch mode, no Kafka
 *       transactions, and its message read as raw bytes; there is one
 *       binder, Kafka, with no settings of its own; and payment defines no
 *       listener-container hook of its own beside the module's;</li>
 *   <li>one replay step fits the time Kafka allows the listener between two
 *       polls: (chunk-size + 1) x (max.block.ms + delivery.timeout.ms) must
 *       be under max.poll.interval.ms.</li>
 * </ul>
 * A check that fails stops the context, so a context that starts has passed
 * them all. Each test also asserts that the beans those checks guard exist,
 * so a context in which the module silently did not run cannot pass for one
 * that passed its checks.
 *
 * <p><b>Why every profile.</b> A profile file is laid over application.yaml
 * when its profile is active, so a setting in it changes what the checks read
 * -- in that profile only. Every other {@code @SpringBootTest} here starts
 * payment with application.yaml alone. The compose stack runs it with the
 * {@code docker} profile, a developer's IDE with {@code local}; each is
 * started here too, and a profile file added later fails
 * {@link #everyProfileFileIsStartedHere()} until it is.
 *
 * <p><b>What a test cannot reach is replaced, and nothing else:</b> the
 * database (a Testcontainers Postgres), Kafka (the in-memory test binder),
 * Eureka (off) and, in {@code docker}, the Config Server
 * ({@link com.oriah.payment.ConfigServerStandIn}). The reconciler's schedule
 * is off too, as in every payment test (src/test/resources/config). None of
 * these is a setting any of the checks reads.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class StartupChecksPerProfileTest {

    private static final String REPLAY_REQUESTS = "oriah.outbox.replay-requests";

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("default profile (application.yaml alone): payment passes every event-log startup check")
    void defaultProfile() {
        passedEveryCheck(context);
    }

    @Test
    @DisplayName("every profile file payment ships is started here")
    void everyProfileFileIsStartedHere() {
        Set<String> started = new TreeSet<>();
        for (Class<?> nested : StartupChecksPerProfileTest.class.getDeclaredClasses()) {
            ActiveProfiles profiles = AnnotatedElementUtils.findMergedAnnotation(nested, ActiveProfiles.class);
            if (nested.isAnnotationPresent(Nested.class) && profiles != null) {
                started.addAll(Arrays.asList(profiles.profiles()));
            }
        }

        assertThat(PaymentConfigFiles.profiles())
                .as("the profiles payment ships a file for (application-<profile>.yaml)")
                .isNotEmpty()
                .isEqualTo(started);
    }

    /** A developer's profile: payment run from the IDE against the backing services in Docker. */
    @Nested
    @ActiveProfiles("local")
    class LocalProfile {

        // This class's own context, started with the profile. (The fields of
        // the enclosing class are filled from ITS context, which has none.)
        @Autowired
        private ApplicationContext localContext;

        @Test
        @DisplayName("local profile (application-local.yaml): payment passes every event-log startup check")
        void passes() {
            // Not vacuous: application-local.yaml really is in force (this
            // value is set there and nowhere else).
            assertThat(localContext.getEnvironment().getProperty("eureka.instance.hostname")).isEqualTo("localhost");

            passedEveryCheck(localContext);
        }
    }

    /** The compose stack's profile. */
    @Nested
    @ActiveProfiles("docker")
    @TestPropertySource(properties = {
            // application-docker.yaml requires the Config Server. With it
            // switched off, ConfigServerStandIn answers for it with no
            // settings. (The real one serves config-repo's application.yaml
            // and payment.yaml; today neither sets anything these checks read.)
            "spring.cloud.config.enabled=false"})
    class DockerProfile {

        @Autowired
        private ApplicationContext dockerContext;

        @Test
        @DisplayName("docker profile (application-docker.yaml): payment passes every event-log startup check")
        void passes() {
            // Not vacuous: application-docker.yaml really is in force.
            assertThat(dockerContext.getEnvironment().getProperty("spring.cloud.stream.kafka.binder.brokers"))
                    .isEqualTo("kafka:9092");

            passedEveryCheck(dockerContext);
        }
    }

    /**
     * The checks ran in this context and passed. Each bean below is created
     * only after its check has passed, so its presence is the proof.
     */
    static void passedEveryCheck(ApplicationContext context) {
        // The dead-letter check: the module builds its hook only after
        // checking every setting of the bindings it takes over. The hook takes
        // over the replay listener, so the check covered it.
        ListenerContainerWithDlqAndRetryCustomizer deadLetters =
                context.getBean(ListenerContainerWithDlqAndRetryCustomizer.class);
        assertThat(deadLetters.retryAndDlqInBinding(REPLAY_REQUESTS, "payment"))
                .as("does the binder keep its own retry for the replay listener?")
                .isFalse();

        // The step-time rule: the replayer is built only after it.
        assertThat(context.getBeansOfType(OutboxReplayer.class)).hasSize(1);

        // And the numbers it was checked with, in this profile. A step sends
        // up to chunk-size rows plus one continuation or end-of-replay
        // marker, and each send that Kafka does not answer can wait
        // max.block.ms and then delivery.timeout.ms before it fails.
        int chunkSize = context.getBean(EventsProperties.class).replay().chunkSize();
        Binder binder = Binder.get(context.getEnvironment());
        Map<String, String> sends = settings(binder, "spring.cloud.stream.kafka.binder.producer-properties");
        Map<String, String> listener = settings(binder,
                "spring.cloud.stream.kafka.bindings.outboxReplayRequests-in-0.consumer.configuration");
        assertThat(sends).as("payment's send timeouts").containsKeys("max.block.ms", "delivery.timeout.ms");
        assertThat(listener).as("the replay listener's Kafka settings").containsKey("max.poll.interval.ms");
        long maxBlock = Long.parseLong(sends.get("max.block.ms"));
        long deliveryTimeout = Long.parseLong(sends.get("delivery.timeout.ms"));
        long pollInterval = Long.parseLong(listener.get("max.poll.interval.ms"));

        assertThat(chunkSize).as("oriah.events.replay.chunk-size").isEqualTo(10);
        // The house send timeouts (the oriah-events README, "The four
        // switches"): a send to a stopped broker fails within seconds, so the
        // relay's round and a replay step are never held for long.
        assertThat(maxBlock).as("max.block.ms").isEqualTo(10_000);
        assertThat(deliveryTimeout).as("delivery.timeout.ms").isEqualTo(10_000);
        // The module's own value for the listener (see ReplayRequestBindingTest).
        assertThat(pollInterval).as("max.poll.interval.ms").isEqualTo(300_000);
        // (10 + 1) x (10 s + 10 s) = 220 s, under 300 s. The 80 s left over
        // covers what the rule does not count: a failing step's pauses and
        // its dead-letter publish (up to about 25 s, then a 10 s pause).
        assertThat((chunkSize + 1L) * (maxBlock + deliveryTimeout))
                .isEqualTo(220_000)
                .isLessThan(pollInterval);
    }

    private static Map<String, String> settings(Binder binder, String name) {
        // adapt, not of: a binding name such as outboxReplayRequests-in-0 has capitals, which a strict name refuses.
        return binder.bind(ConfigurationPropertyName.adapt(name, '.'), Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
    }
}
