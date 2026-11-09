package com.oriah.events;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.cloud.stream.config.BinderProperties;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Checks, when the service starts and before anything is bound, every
 * setting the module's dead-letter handling ({@link DeadLetterCustomizer})
 * depends on, and refuses to start -- naming every problem at once -- when
 * one is wrong.
 *
 * <p>Why here and not only in the customizer: a refusal thrown while the
 * Kafka binder sets a binding up does not stop the service. Spring Cloud
 * Stream catches it, leaves that binding unbound and tries again every 30
 * seconds, so the service would run with the topic unread and only a log
 * line to say so. The customizer keeps its own checks as the last line of
 * defence, reading the binding as the binder actually built it.
 *
 * <p>Where the settings are read from:
 * <ul>
 *   <li>binding name, destination, group and the core consumer settings
 *       (max-attempts, batch-mode, use-native-decoding, retryable-exceptions)
 *       from Spring Cloud Stream's own {@link BindingServiceProperties}, so
 *       its {@code spring.cloud.stream.default.*} values are already merged
 *       in exactly as the binder sees them;</li>
 *   <li>each binding's Kafka settings (enable-dlq, dlq-name, configuration)
 *       from the service's configuration, with relaxed names, the
 *       {@code spring.cloud.stream.kafka.default.consumer} value first and
 *       the binding's own on top -- the binder's precedence.</li>
 * </ul>
 * Reading the Kafka settings straight from the configuration is only right
 * when the service has one binder, of type kafka, with no settings of its
 * own: with several, each binder may carry settings of its own, and a
 * binder's {@code environment} block is read by the binder before the
 * service's configuration. So both are required too. ({@link ReplayStepTime}
 * reads the replay's send timeouts from the same configuration, so this
 * check covers it as well: it runs whenever replay is on with the Kafka
 * binder.)
 */
final class DeadLetterStartupCheck {

    private static final String BYTES = ByteArrayDeserializer.class.getName();

    /**
     * Why a binding's dead-letter topic must not be the topic it reads. Shared
     * with {@link DeadLetterCustomizer}, which refuses the same thing.
     */
    static final String OWN_TOPIC_AS_DLQ = "A failed message would be published back onto the topic it came "
            + "from and read again; worse, for a failure Spring Kafka counts as never worth retrying (a body that "
            + "cannot be read, a ClassCastException and the like), its dead-letter publisher skips a publish back to "
            + "the same topic without saying it failed, so the message is committed -- dropped.";

    /**
     * Why a binding of the service's own may not combine batch-mode and
     * enable-dlq while the module is on. Shared with
     * {@link DeadLetterCustomizer}, which refuses the same thing.
     *
     * <p>The Kafka binder (Spring Cloud Stream 5.0.1) builds its own
     * dead-letter sender for a batch binding only when no hook like the
     * module's is present; with one, it leaves dead-lettering every batch to
     * the hook. The module's hook takes over only the module's own bindings,
     * so nobody would dead-letter the service's batch: Spring Kafka would
     * retry it and then commit it -- dropped, though the setting says
     * dead-letter.
     */
    static final String BATCH_DEAD_LETTERING = "While oriah-events is on, the Kafka binder dead-letters no batch "
            + "itself: it leaves that to the module's hook, which handles only the module's own bindings, so a batch "
            + "that keeps failing would be retried and then committed -- dropped. Turn batch-mode off for it (the "
            + "binder then dead-letters each failed record itself), or, if a failing batch may be dropped, turn "
            + "enable-dlq off for it so its settings do not promise a dead-letter topic";

    /** A function's input binding: {@code <function>-in-<index>}. */
    private static final Pattern INPUT_BINDING = Pattern.compile(".+-in-\\d+");

    private DeadLetterStartupCheck() {
    }

    /**
     * @throws IllegalStateException naming every problem found, when there is one
     */
    static void require(EventsProperties properties, BindingServiceProperties bindings, Environment environment) {
        DeadLetterScope scope = DeadLetterScope.of(properties);
        Binder binder = Binder.get(environment);
        List<String> problems = new ArrayList<>();

        if (properties.inbox().enabled() && properties.inbox().topics().isEmpty()) {
            problems.add("oriah.events.inbox.enabled is on but oriah.events.inbox.topics is empty: list the topics "
                    + "this service reads through the Inbox. Only their bindings get the module's dead-lettering; "
                    + "any other binding keeps the binder's own, which drops a message if its dead-letter publish "
                    + "fails");
        }
        String kafkaBinder = oneKafkaBinder(bindings, problems);

        Map<String, String> inputs = inputBindings(bindings);
        for (String topic : scope.inboxTopics()) {
            requireOneReader(topic, "oriah.events.inbox.topics", inputs, problems);
        }
        if (scope.catchUpTopic() != null) {
            requireOneReader(scope.catchUpTopic(), "CatchUp (oriah.events.inbox.catch-up.consumer)", inputs, problems);
        }

        inputs.forEach((binding, destination) -> {
            if (scope.covers(destination)) {
                checkBinding(binding, destination, bindings, binder, kafkaBinder, problems);
            } else {
                checkOtherBinding(binding, destination, bindings, binder, problems);
            }
        });
        if (!scope.topics().isEmpty()) {
            checkBinderWide(binder, problems);
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("oriah-events refuses to start: it cannot dead-letter safely for this "
                    + "service's bindings.\n - " + String.join("\n - ", problems));
        }
    }

    /**
     * Exactly one binder, of type kafka, with no {@code environment} block:
     * the settings the module reads must be the ones the binder uses.
     *
     * @return the Kafka binder's name, as a binding or default-binder setting would name it
     */
    private static String oneKafkaBinder(BindingServiceProperties bindings, List<String> problems) {
        Map<String, BinderProperties> binders = bindings.getBinders();
        String kafka = "kafka";
        if (binders.size() > 1) {
            problems.add("spring.cloud.stream.binders defines " + binders.size() + " binders " + binders.keySet()
                    + "; oriah-events supports one Kafka binder only, because it reads the Kafka settings from the "
                    + "service's configuration, and a binder of its own can override them");
        } else if (binders.size() == 1) {
            Map.Entry<String, BinderProperties> only = binders.entrySet().iterator().next();
            if (!"kafka".equals(only.getValue().getType())) {
                problems.add("spring.cloud.stream.binders." + only.getKey() + " is of type " + only.getValue().getType()
                        + "; oriah-events supports one Kafka binder only");
            }
            List<String> environment = new ArrayList<>();
            flatten("", only.getValue().getEnvironment(), environment);
            if (!environment.isEmpty()) {
                problems.add("spring.cloud.stream.binders." + only.getKey() + ".environment sets " + environment
                        + ". The binder reads its settings there before the service's own configuration, so "
                        + "oriah-events, which reads that configuration, would check settings the binder does not "
                        + "use (the dead-letter settings, the deserializers, the replay's send timeouts). "
                        + "oriah-events supports one Kafka binder only, set up in the service's own configuration: "
                        + "move these settings there and remove the environment block");
            }
            kafka = only.getKey();
        }
        String defaultBinder = bindings.getDefaultBinder();
        if (StringUtils.hasText(defaultBinder) && !defaultBinder.equals(kafka)) {
            problems.add("spring.cloud.stream.default-binder is " + defaultBinder + "; oriah-events supports one "
                    + "Kafka binder only, and it is the default");
        }
        return kafka;
    }

    /** A binder's environment block as the dotted names it sets ({@code spring.cloud.stream.kafka...}). */
    private static void flatten(String prefix, Map<?, ?> settings, List<String> names) {
        settings.forEach((key, value) -> {
            String name = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                flatten(name, nested, names);
            } else {
                names.add(name);
            }
        });
    }

    /** Every input binding the service configures, with its topic. */
    private static Map<String, String> inputBindings(BindingServiceProperties bindings) {
        Set<String> named = StringUtils.hasText(bindings.getInputBindings())
                ? Set.copyOf(Arrays.stream(bindings.getInputBindings().split("[;,]")).map(String::trim).toList())
                : Set.of();
        Map<String, String> inputs = new LinkedHashMap<>();
        bindings.getBindings().forEach((name, binding) -> {
            if (INPUT_BINDING.matcher(name).matches() || named.contains(name)) {
                inputs.put(name, StringUtils.hasText(binding.getDestination())
                        ? binding.getDestination().trim() : name);
            }
        });
        return inputs;
    }

    /**
     * Exactly one input binding reads the topic. None: the binding that does
     * read it is named differently (a typo), so it would not get the module's
     * dead-lettering. Two: each would apply every event.
     */
    private static void requireOneReader(String topic, String listedBy, Map<String, String> inputs,
                                         List<String> problems) {
        List<String> readers = inputs.entrySet().stream()
                .filter(input -> input.getValue().equals(topic)).map(Map.Entry::getKey).toList();
        if (readers.isEmpty()) {
            problems.add(topic + " (from " + listedBy + ") has no input binding reading it: add one, or correct the "
                    + "topic name, so that the binding gets the module's dead-lettering");
        } else if (readers.size() > 1) {
            problems.add(topic + " (from " + listedBy + ") is read by " + readers.size() + " input bindings "
                    + readers + "; it must be exactly one");
        }
    }

    private static void checkBinding(String binding, String destination, BindingServiceProperties bindings,
                                     Binder binder, String kafkaBinder, List<String> problems) {
        BindingProperties settings = bindings.getBindingProperties(binding);
        ConsumerProperties consumer = bindings.getConsumerProperties(binding);
        KafkaConsumerProperties kafka = kafkaConsumer(binder, binding);
        String about = "binding " + binding + " (" + destination + ")";

        if (!StringUtils.hasText(settings.getGroup())) {
            problems.add(about + " has no group: Kafka keeps no dead-letter topic for an anonymous subscription");
        }
        if (StringUtils.hasText(settings.getBinder()) && !settings.getBinder().equals(kafkaBinder)) {
            problems.add(about + " uses binder " + settings.getBinder() + "; oriah-events supports one Kafka "
                    + "binder only");
        }
        if (!kafka.isEnableDlq()) {
            problems.add(about + " needs enable-dlq: true, or a message that keeps failing has nowhere to go");
        }
        if (!StringUtils.hasText(kafka.getDlqName())) {
            problems.add(about + " needs a dlq-name (by convention " + destination + ".dlq.<group>)");
        } else if (kafka.getDlqName().trim().equals(destination)) {
            problems.add(about + " has dlq-name " + destination + ", the topic it reads. " + OWN_TOPIC_AS_DLQ
                    + " Name a topic of its own (by convention " + destination + ".dlq.<group>)");
        }
        if (consumer.getMaxAttempts() < 2) {
            problems.add(about + " has max-attempts " + consumer.getMaxAttempts() + "; it needs 2 or more: with 1 "
                    + "the binder hands every failure to its own dead-letter sender, which drops the message if "
                    + "that publish fails. To dead-letter a failure on its first delivery, list its exception "
                    + "under retryable-exceptions as false (or set default-retryable: false)");
        }
        if (consumer.isBatchMode()) {
            problems.add(about + " has batch-mode on: the module dead-letters one record at a time");
        }
        if (consumer.isUseNativeDecoding()) {
            problems.add(about + " has use-native-decoding on: the module re-publishes a failed record as the "
                    + "raw bytes Kafka holds, so the body must reach the listener as those bytes");
        }
        consumer.getRetryableExceptions().keySet().stream()
                .filter(type -> !Exception.class.isAssignableFrom(type))
                .forEach(type -> problems.add(about + " lists " + type.getName() + " under retryable-exceptions; "
                        + "only Exceptions can be marked retryable or not"));
        if (StringUtils.hasText(kafka.getTransactionManager())) {
            problems.add(about + " runs in a Kafka transaction (transaction-manager): the binder then "
                    + "dead-letters through its own after-rollback step, which drops a message if that publish "
                    + "fails");
        }
        for (String part : List.of("key", "value")) {
            String deserializer = deserializer(binder, kafka, part);
            if (!BYTES.equals(deserializer)) {
                problems.add(about + " has the " + part + " deserializer " + deserializer + ", not " + BYTES
                        + ": the dead-letter publish sends the record back as raw bytes, so every publish "
                        + "would fail");
            }
        }
    }

    /**
     * A binding of the service's own. The module leaves it to the binder, and
     * checks one thing only: the one case where the module's mere presence
     * changes what the binder does with it (see {@link #BATCH_DEAD_LETTERING}).
     */
    private static void checkOtherBinding(String binding, String destination, BindingServiceProperties bindings,
                                          Binder binder, List<String> problems) {
        if (bindings.getConsumerProperties(binding).isBatchMode() && kafkaConsumer(binder, binding).isEnableDlq()) {
            problems.add("binding " + binding + " (" + destination + "), one of the service's own, has batch-mode "
                    + "and enable-dlq on. " + BATCH_DEAD_LETTERING);
        }
    }

    /** Settings for the whole binder, which every binding -- the module's too -- inherits. */
    private static void checkBinderWide(Binder binder, List<String> problems) {
        String prefix = binder.bind("spring.cloud.stream.kafka.binder.transaction.transaction-id-prefix",
                String.class).orElse(null);
        if (StringUtils.hasText(prefix)) {
            problems.add("spring.cloud.stream.kafka.binder.transaction.transaction-id-prefix is set: every "
                    + "binding then runs in a Kafka transaction, and the binder dead-letters through its own "
                    + "after-rollback step, which drops a message if that publish fails");
        }
    }

    /**
     * The binding's Kafka settings: the {@code spring.cloud.stream.kafka.default.consumer} values first, the
     * binding's own on top. Relaxed names ({@code enable-dlq}, {@code enableDlq}, an environment variable)
     * are all read.
     */
    private static KafkaConsumerProperties kafkaConsumer(Binder binder, String binding) {
        KafkaConsumerProperties kafka = new KafkaConsumerProperties();
        binder.bind("spring.cloud.stream.kafka.default.consumer", Bindable.ofInstance(kafka));
        // adapt, not of: a binding name such as orderEvents-in-0 has capitals, which a strict name refuses.
        binder.bind(ConfigurationPropertyName.adapt("spring.cloud.stream.kafka.bindings." + binding + ".consumer",
                '.'), Bindable.ofInstance(kafka));
        return kafka;
    }

    /**
     * The deserializer the binder will build this binding's consumer with,
     * from the lowest layer to the highest, each overriding the one before:
     * Spring Kafka's settings, the Kafka binder's {@code configuration}, its
     * {@code consumer-properties}, then the binding's own {@code configuration}
     * (with its default). Unset everywhere, the binder uses bytes.
     */
    private static String deserializer(Binder binder, KafkaConsumerProperties kafka, String part) {
        String kafkaName = part + ".deserializer";
        String found = BYTES;
        for (String layer : new String[] {
                map(binder, "spring.kafka.properties").get(kafkaName),
                binder.bind("spring.kafka.consumer." + part + "-deserializer", String.class).orElse(null),
                map(binder, "spring.kafka.consumer.properties").get(kafkaName),
                map(binder, "spring.cloud.stream.kafka.binder.configuration").get(kafkaName),
                map(binder, "spring.cloud.stream.kafka.binder.consumer-properties").get(kafkaName),
                kafka.getConfiguration().get(kafkaName)}) {
            if (StringUtils.hasText(layer)) {
                found = layer.trim();
            }
        }
        return found;
    }

    private static Map<String, String> map(Binder binder, String name) {
        return binder.bind(name, Bindable.mapOf(String.class, String.class)).orElse(Map.of());
    }
}
