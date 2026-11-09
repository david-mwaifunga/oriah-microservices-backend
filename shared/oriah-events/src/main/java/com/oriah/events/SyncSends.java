package com.oriah.events;

import org.springframework.core.env.Environment;

/**
 * The one binder setting the relay and CatchUp cannot work without:
 * {@value #PROPERTY}=true, so that a send returns only once Kafka has the
 * record, and a send Kafka did not take fails in the send itself.
 *
 * <p>Why: with Kafka's default the send is asynchronous. StreamBridge answers
 * true as soon as the record is queued inside the service, and if the broker
 * then fails to take it, the failure only reaches a log. The relay would
 * move its cursor past a row Kafka never took, so that event would never be
 * published; CatchUp would believe it had asked for a replay that no
 * producer ever received, and the catch-up would wait for an answer that
 * never comes. Both trust the answer of the send, so both refuse to start
 * without it.
 *
 * <p>Read from the environment by name, so the module needs no Kafka
 * dependency. It is the binder's default for every producer binding,
 * including the topics the relay and CatchUp send to without declaring
 * them; a per-binding override to false would undo it, and is not checked.
 */
final class SyncSends {

    static final String PROPERTY = "spring.cloud.stream.kafka.default.producer.sync";

    private SyncSends() {
    }

    /**
     * @param switchedOn the setting that switched on the part that needs it
     * @param otherwise  what would go wrong without it, in plain words
     */
    static void require(Environment environment, String switchedOn, String otherwise) {
        if (!environment.getProperty(PROPERTY, Boolean.class, false)) {
            throw new IllegalStateException(switchedOn + " needs " + PROPERTY + "=true in the binder config: "
                    + "without it a send returns before Kafka has the record, and " + otherwise);
        }
    }
}
