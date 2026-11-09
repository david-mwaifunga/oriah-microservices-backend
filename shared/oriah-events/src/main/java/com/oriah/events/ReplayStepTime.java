package com.oriah.events;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * The time rule for one replay step, checked when the service starts: the
 * slowest a step can be must still be less than the time Kafka allows the
 * replay-request listener between two polls.
 *
 * <p>Why it matters: Kafka presumes a listener that takes longer than
 * {@code max.poll.interval.ms} between polls is dead. It takes its
 * partitions away and delivers the request it was answering again -- to an
 * instance that would take just as long, for ever. The listener reads one
 * request per poll (the module sets {@code max.poll.records=1}), so the time
 * between polls is one step.
 *
 * <p>The slowest step: it sends up to {@code oriah.events.replay.chunk-size}
 * rows, then one continuation or end-of-replay marker -- chunk-size + 1
 * sends, one after another, each waiting until Kafka has the record. A send
 * Kafka does not answer waits up to {@code max.block.ms} (for the topic's
 * details) and then up to {@code delivery.timeout.ms} (for the send itself)
 * before it fails. So the service refuses to start unless
 * <pre>(chunk-size + 1) x (max.block.ms + delivery.timeout.ms) &lt; max.poll.interval.ms</pre>
 * With the README's timeouts (10000 each) and the default chunk size of 10
 * that is 11 x 20 s = 220 s, under the module's 300 s.
 *
 * <p><b>What the rule does not count.</b> Only the sends. A step that fails
 * also spends time on the same thread before the next poll: the pause before
 * its next try (the listener's back-off, 1 s then 2 s by default) and, after
 * its last try, the dead-letter publish (up to about 25 s, see
 * {@link DeadLetterCustomizer}'s producer settings) and, if that fails too, the
 * pause after it ({@link DeadLetterThrottle}, the back-off max-interval, 10 s
 * by default). At the default chunk size they fit in what the rule leaves:
 * 220 + 25 + 10 = 255 s. The rule itself would allow up to 13 with these
 * timeouts (14 x 20 s = 280 s), and there they do not fit: 280 + 25 + 10 =
 * 315 s. Kafka would then presume the listener dead and deliver the step
 * again -- nothing is lost, the step is answered again -- so the refusal
 * says so rather than recommend the largest size the rule allows. (The rule
 * is the one agreed for the module; counting these too would change it.)
 *
 * <p>The values are read from the service's configuration. That is the
 * configuration the binder uses only because {@link DeadLetterStartupCheck}
 * refuses a second binder and a binder with an {@code environment} block of
 * its own; it runs whenever replay is on with the Kafka binder.
 *
 * <p>Each value is the one the Kafka binder will actually use, read from the
 * same places in the same order, a later one winning:
 * <ul>
 *   <li>the send timeouts from {@code spring.kafka.properties},
 *       {@code spring.kafka.producer.properties}, the binder's
 *       {@code configuration}, its {@code producer-properties}, then
 *       {@code spring.cloud.stream.kafka.default.producer.configuration}
 *       (which every replay send gets: the replay topics are named only when
 *       a request arrives, so they have no settings of their own -- do not
 *       give one any);</li>
 *   <li>the poll interval from {@code spring.kafka.properties},
 *       {@code spring.kafka.consumer.max-poll-interval},
 *       {@code spring.kafka.consumer.properties}, the binder's
 *       {@code configuration}, its {@code consumer-properties},
 *       {@code spring.cloud.stream.kafka.default.consumer.configuration},
 *       then the listener binding's own {@code configuration}, where the
 *       module puts its 300000.</li>
 * </ul>
 * Missing everywhere, a value is Kafka's own default: max.block.ms 60000,
 * delivery.timeout.ms 120000, max.poll.interval.ms 300000.
 */
final class ReplayStepTime {

    static final String CHUNK_SIZE = "oriah.events.replay.chunk-size";

    private static final String MAX_BLOCK = "max.block.ms";
    private static final String DELIVERY_TIMEOUT = "delivery.timeout.ms";
    private static final String MAX_POLL_INTERVAL = "max.poll.interval.ms";

    /** The send timeouts the README asks every producing service to set, per send. */
    private static final long README_SEND_MS = 10_000 + 10_000;

    private static final List<String> PRODUCER_LAYERS = List.of(
            "spring.kafka.properties",
            "spring.kafka.producer.properties",
            "spring.cloud.stream.kafka.binder.configuration",
            "spring.cloud.stream.kafka.binder.producer-properties",
            "spring.cloud.stream.kafka.default.producer.configuration");

    private ReplayStepTime() {
    }

    /**
     * @throws IllegalStateException in plain words, with every number and
     *                               where it came from, when a step could
     *                               outlast the poll interval
     */
    static void require(int chunkSize, Environment environment) {
        Binder binder = Binder.get(environment);
        Setting maxBlock = fromLayers(binder, PRODUCER_LAYERS, MAX_BLOCK, 60_000);
        Setting delivery = fromLayers(binder, PRODUCER_LAYERS, DELIVERY_TIMEOUT, 120_000);
        Setting interval = pollInterval(binder);

        long perSend = maxBlock.millis() + delivery.millis();
        long slowestStep = (chunkSize + 1L) * perSend;
        if (slowestStep < interval.millis()) {
            return;
        }
        long fitsNow = interval.millis() > 0 ? (interval.millis() - 1) / perSend - 1 : 0;
        long fitsWithReadme = (interval.millis() - 1) / README_SEND_MS - 1;
        throw new IllegalStateException("oriah-events refuses to start: one replay step could take longer than "
                + "Kafka allows the replay-request listener (" + OutboxReplayBinding.BINDING_NAME + ") between two "
                + "polls. Kafka would then presume the listener dead and deliver the step again, where it would "
                + "take just as long, for ever.\n"
                + "A step sends up to " + CHUNK_SIZE + " rows plus one continuation or end-of-replay marker, one "
                + "after another, and a send Kafka does not answer can wait max.block.ms and then "
                + "delivery.timeout.ms before it fails: (" + chunkSize + " + 1) x (" + maxBlock.millis() + " + "
                + delivery.millis() + ") ms = " + slowestStep + " ms, which is not under " + MAX_POLL_INTERVAL + " "
                + interval.millis() + " ms.\n"
                + "Where each number came from: chunk size " + chunkSize + " (" + CHUNK_SIZE + "), " + MAX_BLOCK + " "
                + maxBlock + ", " + DELIVERY_TIMEOUT + " " + delivery + ", " + MAX_POLL_INTERVAL + " " + interval
                + ".\n"
                + "To fix it, set " + MAX_BLOCK + ": 10000 and " + DELIVERY_TIMEOUT + ": 10000 under "
                + "spring.cloud.stream.kafka.binder.producer-properties, as the oriah-events README asks (with "
                + "those, " + CHUNK_SIZE + " may be " + fitsWithReadme + " or less), or lower " + CHUNK_SIZE
                + (fitsNow >= 1 ? " to " + fitsNow + " or less." : " (with these timeouts not even 1 fits).")
                + "\n" + NOT_COUNTED);
    }

    /**
     * What the rule leaves out, said with every refusal so that the largest
     * chunk size the rule allows is not read as advice.
     */
    private static final String NOT_COUNTED = "This rule counts the sends only. A step that fails also waits, on "
            + "the same thread before the next poll, for the pause before its next try and, after its last try, "
            + "for its dead-letter publish (up to about 25 s) and, if that fails too, the pause after it (the "
            + "listener's back-off-max-interval, 10 s by default). With the send timeouts above, the default chunk "
            + "size of 10 leaves room for them; the largest the rule allows may not, and a failed step would then "
            + "be delivered again. See the oriah-events README, \"How long one step may take\".";

    /** The listener's poll interval, as the Kafka binder builds its consumer. */
    private static Setting pollInterval(Binder binder) {
        Setting found = new Setting(300_000, "Kafka's default");
        found = layer(binder, "spring.kafka.properties", MAX_POLL_INTERVAL, found);
        Duration boot = binder.bind("spring.kafka.consumer.max-poll-interval", Duration.class).orElse(null);
        if (boot != null) {
            found = new Setting(boot.toMillis(), "spring.kafka.consumer.max-poll-interval");
        }
        for (String layer : List.of(
                "spring.kafka.consumer.properties",
                "spring.cloud.stream.kafka.binder.configuration",
                "spring.cloud.stream.kafka.binder.consumer-properties",
                "spring.cloud.stream.kafka.default.consumer.configuration",
                "spring.cloud.stream.kafka.bindings." + OutboxReplayBinding.BINDING_NAME + ".consumer.configuration")) {
            found = layer(binder, layer, MAX_POLL_INTERVAL, found);
        }
        return found;
    }

    private static Setting fromLayers(Binder binder, List<String> layers, String key, long kafkaDefault) {
        Setting found = new Setting(kafkaDefault, "Kafka's default");
        for (String layer : layers) {
            found = layer(binder, layer, key, found);
        }
        return found;
    }

    /** The key's value in this layer (a map of Kafka settings), if it has one; otherwise what was found below it. */
    private static Setting layer(Binder binder, String layer, String key, Setting below) {
        // adapt, not of: a binding name such as outboxReplayRequests-in-0 has capitals, which a strict name refuses.
        Map<String, String> settings = binder.bind(ConfigurationPropertyName.adapt(layer, '.'),
                Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        String value = settings.get(key);
        if (!StringUtils.hasText(value)) {
            return below;
        }
        try {
            return new Setting(Long.parseLong(value.trim()), layer);
        } catch (NumberFormatException e) {
            // Not chained: Java's own message would then be the first one read, and it says less than this.
            throw new IllegalStateException("oriah-events refuses to start: " + key + " under " + layer + " is '"
                    + value + "', not a whole number of milliseconds; it is needed to check that a replay step "
                    + "fits the time Kafka allows the replay-request listener between polls");
        }
    }

    /** A value in milliseconds and where it came from. */
    private record Setting(long millis, String source) {

        @Override
        public String toString() {
            return millis + " (" + source + ")";
        }
    }
}
