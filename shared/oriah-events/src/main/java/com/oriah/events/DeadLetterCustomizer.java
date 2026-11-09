package com.oriah.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.ListenerContainerWithDlqAndRetryCustomizer;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.StringUtils;
import org.springframework.util.backoff.BackOff;

/**
 * The module's dead-letter handling, installed on each Kafka binding that
 * reads one of the module's topics ({@link DeadLetterScope}). It replaces
 * the binder's own retry and dead-lettering for those bindings only.
 *
 * <p><b>Why replace them.</b> When a message keeps failing, the Kafka binder
 * (Spring Cloud Stream 5.0.1) publishes it to the binding's dead-letter
 * topic and then commits its offset -- even when that publish failed. Its
 * dead-letter sender only logs the failure, so the message is gone. With the
 * handler installed here a message is committed only once the dead-letter
 * topic has it: if the publish fails, the record stays uncommitted and is
 * delivered again, so nothing is dropped while Kafka still holds it.
 *
 * <p>What it installs, per binding (the binder calls {@link #configure} once
 * for each binding it starts):
 * <ul>
 *   <li>a container error handler that tries a failing record
 *       {@code max-attempts} times in all (the binding's own back-off between
 *       tries), then publishes it to the binding's dead-letter topic;</li>
 *   <li>the binding's {@code retryable-exceptions}: a type marked false is
 *       dead-lettered on its first delivery (a refusal, which fails the same
 *       way every time), on top of spring-kafka's own list of failures that
 *       can never succeed (a ClassCastException, a deserialization failure,
 *       and so on), which is kept on purpose;</li>
 *   <li>a dead-letter producer of its own, built from that binding's own
 *       consumer settings (address and credentials only), so two bindings
 *       never share credentials; each is closed when the service stops;</li>
 *   <li>a pause after a failed dead-letter publish ({@link DeadLetterThrottle}),
 *       so a record the dead-letter topic refuses is not retried in a tight
 *       loop.</li>
 * </ul>
 *
 * <p>Every other binding is left exactly as the binder built it, with one
 * exception: a binding of the service's own with batch-mode and enable-dlq
 * is refused, because the binder hands the dead-lettering of every batch to
 * this hook once it exists, and nothing would then dead-letter that batch
 * (see {@link DeadLetterStartupCheck#BATCH_DEAD_LETTERING}).
 *
 * <p>A binding it cannot make safe is refused with a plain reason. A refusal
 * here does not stop the service -- the binder catches it and retries the
 * binding every 30 seconds, leaving it dead in between -- which is why
 * {@link DeadLetterStartupCheck} checks the same things before anything is
 * bound. This is the backstop, reading the binding as the binder actually
 * built it.
 */
final class DeadLetterCustomizer implements ListenerContainerWithDlqAndRetryCustomizer, DisposableBean {

    private static final String BYTES = ByteArrayDeserializer.class.getName();

    private final DeadLetterScope scope;
    private final List<DefaultKafkaProducerFactory<byte[], byte[]>> factories = new CopyOnWriteArrayList<>();

    DeadLetterCustomizer(DeadLetterScope scope) {
        this.scope = scope;
    }

    /** False -- "the module handles it" -- only for a binding on one of the module's topics. */
    @Override
    public boolean retryAndDlqInBinding(String destination, String group) {
        return !scope.covers(destination);
    }

    /**
     * The older form of the hook, without the binding's properties. The
     * Kafka binder this module is built with always calls the form below;
     * the module cannot set up dead-lettering without the binding's
     * properties, so one of its bindings is refused rather than left
     * half-done.
     */
    @Override
    public void configure(AbstractMessageListenerContainer<?, ?> container, String destination, String group,
                          BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> deadLetterTopic,
                          BackOff backOff) {
        if (scope.covers(destination)) {
            throw new IllegalStateException("The Kafka binder set up the binding of " + destination
                    + " without passing its properties; oriah-events needs them to dead-letter safely. Use the "
                    + "Kafka binder version the module is built with.");
        }
    }

    /**
     * @param deadLetterTopic where the binder would dead-letter a record;
     *                        null when the binding's enable-dlq is false
     * @param backOff         the pauses between tries, built by the binder
     *                        from max-attempts and the back-off settings;
     *                        null when max-attempts is 1 or less
     */
    @Override
    public void configure(AbstractMessageListenerContainer<?, ?> container, String destination, String group,
                          BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> deadLetterTopic,
                          BackOff backOff, ExtendedConsumerProperties<KafkaConsumerProperties> properties) {
        if (!scope.covers(destination)) {
            // Not the module's: left as the binder built it -- except a batch
            // binding with dead-lettering, which the binder hands to this hook
            // alone, and which nothing would then dead-letter.
            if (properties.isBatchMode() && properties.getExtension().isEnableDlq()) {
                throw new IllegalStateException("Binding " + properties.getBindingName() + " reads " + destination
                        + " with batch-mode and enable-dlq on; it is one of the service's own bindings, not the "
                        + "module's. " + DeadLetterStartupCheck.BATCH_DEAD_LETTERING + ".");
            }
            return;
        }
        String binding = properties.getBindingName();
        refuseUnsafe(container, destination, binding, deadLetterTopic, backOff, properties);

        DefaultKafkaProducerFactory<byte[], byte[]> producers =
                new DefaultKafkaProducerFactory<>(producerConfig(consumerFactory(container)
                        .getConfigurationProperties()));
        factories.add(producers);
        DeadLetterPublishingRecoverer deadLetters =
                new DeadLetterPublishingRecoverer(new KafkaTemplate<>(producers), deadLetterTopic);
        // True already in spring-kafka 4.0.5; set anyway, because the whole
        // point is that a failed publish fails the recovery, so the record is
        // not committed.
        deadLetters.setFailIfSendResultIsError(true);
        container.setCommonErrorHandler(errorHandler(
                new DeadLetterThrottle(deadLetters, binding, properties.getBackOffMaxInterval(), container::isRunning),
                backOff, properties));
    }

    /**
     * The container's error handler: {@code backOff} between tries, then the
     * recoverer, which dead-letters the record.
     *
     * <p>The classification is added to spring-kafka's defaults rather than
     * replacing them, so its list of failures that can never succeed (a
     * ClassCastException, a deserialization failure ...) is not retried,
     * even when the binding marks a broader type (RuntimeException) true;
     * only naming that very type true would change it. With
     * {@code default-retryable: false} only the types marked true are
     * retried, and that list is still kept.
     *
     * <p>When the recoverer itself fails (the dead-letter topic did not take
     * the record), the record's retry count starts again
     * (resetStateOnRecoveryFailure, spring-kafka's default, set here so it is
     * plain): a retryable failure goes through its back-off again before the
     * next try to dead-letter it.
     */
    static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer, BackOff backOff,
                                            ConsumerProperties properties) {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setResetStateOnRecoveryFailure(true);
        if (!properties.isDefaultRetryable()) {
            handler.defaultFalse(true);
        }
        properties.getRetryableExceptions().forEach((type, retry) -> {
            Class<? extends Exception> exception = type.asSubclass(Exception.class);
            if (retry) {
                handler.addRetryableExceptions(exception);
            } else {
                handler.addNotRetryableExceptions(exception);
            }
        });
        return handler;
    }

    /**
     * The dead-letter producer's settings: where the broker is and how to
     * log in to it, copied from the binding's own consumer, and nothing else
     * of it. Raw bytes in and out: the record is re-published exactly as it
     * arrived. The producer's two waits are 10 seconds each (for the topic's
     * details, then for the send), so -- with the 5 seconds Spring Kafka's
     * publisher first spends looking up the topic's partitions -- a
     * dead-letter topic that does not answer fails the publish (and the
     * record is kept) within about 25 seconds, instead of holding the
     * consumer for Kafka's defaults of 60 and 120.
     */
    static Map<String, Object> producerConfig(Map<String, Object> consumer) {
        Map<String, Object> producer = new HashMap<>();
        consumer.forEach((name, value) -> {
            if (name.equals(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG)
                    || name.equals(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG)
                    || name.equals(CommonClientConfigs.CLIENT_DNS_LOOKUP_CONFIG)
                    || name.startsWith("sasl.")
                    || name.startsWith("ssl.")) {
                producer.put(name, value);
            }
        });
        producer.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producer.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producer.put(ProducerConfig.ACKS_CONFIG, "all");
        producer.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producer.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        producer.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        producer.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        return producer;
    }

    /** Every reason this binding cannot be made safe, refused together so one start shows them all. */
    private static void refuseUnsafe(AbstractMessageListenerContainer<?, ?> container, String destination,
                                     String binding,
                                     BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> deadLetterTopic,
                                     BackOff backOff, ExtendedConsumerProperties<KafkaConsumerProperties> properties) {
        List<String> problems = new ArrayList<>();
        if (deadLetterTopic == null) {
            problems.add("it has no dead-letter topic: set enable-dlq: true and a dlq-name, or a message that keeps "
                    + "failing has nowhere to go");
        } else if (!StringUtils.hasText(properties.getExtension().getDlqName())) {
            problems.add("enable-dlq is on but dlq-name is empty: name the dead-letter topic "
                    + "(by convention <topic>.dlq.<group>)");
        } else if (properties.getExtension().getDlqName().trim().equals(destination)) {
            problems.add("its dlq-name " + destination + " is the topic it reads. "
                    + DeadLetterStartupCheck.OWN_TOPIC_AS_DLQ + " Name a topic of its own (by convention "
                    + destination + ".dlq.<group>)");
        }
        if (backOff == null) {
            problems.add("max-attempts is " + properties.getMaxAttempts() + ", and the module needs 2 or more: "
                    + "with 1 the binder hands every failure to its own dead-letter sender, which drops the message "
                    + "if that publish fails. To dead-letter a failure on its first delivery, list its exception "
                    + "under retryable-exceptions as false (or set default-retryable: false)");
        }
        if (properties.isBatchMode()) {
            problems.add("batch-mode is on: the module dead-letters one record at a time, and for a batch the "
                    + "binder handles failures itself");
        }
        if (container.getContainerProperties().getKafkaAwareTransactionManager() != null
                || container.getContainerProperties().getTransactionManager() != null) {
            problems.add("it runs in a Kafka transaction: the binder then dead-letters through its own "
                    + "after-rollback step, which drops the message if that publish fails");
        }
        ConsumerFactory<?, ?> consumer = consumerFactory(container);
        String key = deserializer(consumer.getKeyDeserializer(),
                consumer.getConfigurationProperties().get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        String value = deserializer(consumer.getValueDeserializer(),
                consumer.getConfigurationProperties().get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        if (!BYTES.equals(key)) {
            problems.add("its key deserializer is " + key + ", not " + BYTES + ": the dead-letter publish sends "
                    + "the key back as raw bytes, so every publish would fail");
        }
        if (!BYTES.equals(value)) {
            problems.add("its value deserializer is " + value + ", not " + BYTES + ": the dead-letter publish "
                    + "sends the body back as raw bytes, so every publish would fail");
        }
        properties.getRetryableExceptions().keySet().stream()
                .filter(type -> !Exception.class.isAssignableFrom(type))
                .forEach(type -> problems.add("retryable-exceptions lists " + type.getName()
                        + ", which is not an Exception: only Exceptions can be marked retryable or not"));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Binding " + binding + " reads " + destination + ", one of the "
                    + "oriah-events topics, so the module dead-letters for it; it cannot, because "
                    + String.join("; and ", problems) + ".");
        }
    }

    /**
     * The consumer factory the binder built this binding's container with:
     * the binding's real consumer settings (address, credentials,
     * deserializers), whatever mix of binder, default and per-binding
     * configuration produced them.
     *
     * <p>Read from the container's field because spring-kafka 4.0.5 has no
     * public getter for it. If a later spring-kafka renames the field, this
     * fails loudly (the binding is refused) rather than guessing.
     */
    private static ConsumerFactory<?, ?> consumerFactory(AbstractMessageListenerContainer<?, ?> container) {
        return (ConsumerFactory<?, ?>) new DirectFieldAccessor(container).getPropertyValue("consumerFactory");
    }

    /** The deserializer's class name: the instance the factory was given, else its configured class. */
    private static String deserializer(Object instance, Object configured) {
        if (instance != null) {
            return instance.getClass().getName();
        }
        return configured instanceof Class<?> type ? type.getName() : String.valueOf(configured);
    }

    /** The dead-letter producers built so far, one per binding set up. */
    List<DefaultKafkaProducerFactory<byte[], byte[]>> producerFactories() {
        return List.copyOf(factories);
    }

    /** The service is stopping: close every dead-letter producer. */
    @Override
    public void destroy() {
        factories.forEach(DefaultKafkaProducerFactory::destroy);
        factories.clear();
    }
}
