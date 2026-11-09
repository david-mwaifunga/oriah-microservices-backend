package com.oriah.events;

import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.ListenerUtils;

/**
 * Wraps the dead-letter publish: when it fails, logs one ERROR, waits, then
 * throws the failure on.
 *
 * <p>Why throw: the container's error handler then treats the record as not
 * dealt with -- it is not committed and Kafka delivers it again. That is what
 * keeps a message whose dead-letter publish failed from being lost. The cost
 * is that the consumer thread (and every partition it reads) waits on this
 * record until the dead-letter topic takes it; the ERROR line and the
 * consumer group's growing lag are how operations see that.
 *
 * <p>Why wait: a failure that is not retried (a refusal) goes back to the
 * dead-letter publish on every delivery, with no back-off in between, and a
 * publish that fails fast (no permission, a record the topic will not take)
 * would make the consumer spin and log as fast as it can. Waiting the
 * binding's back-off max-interval (10 seconds unless the binding says
 * otherwise) keeps it to one ERROR per round: the publish's own timeout plus
 * the pause. Spring Kafka has no setting for this -- its back-off spaces out
 * retries of the listener, not a failed recovery.
 *
 * <p>The pause ends early when the listener container stops, so a stuck
 * dead-letter topic never holds up the service's shutdown. If the thread is
 * interrupted, the interrupt is kept for whoever owns the thread and the
 * failure is thrown at once.
 */
final class DeadLetterThrottle implements ConsumerAwareRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterThrottle.class);

    private final ConsumerAwareRecordRecoverer deadLetters;
    private final String binding;
    private final long pauseMillis;
    private final BooleanSupplier running;

    /**
     * @param deadLetters the dead-letter publish
     * @param binding     the binding's name, for the log line
     * @param pauseMillis how long to wait after a failed publish
     * @param running     whether the listener container is still running
     */
    DeadLetterThrottle(ConsumerAwareRecordRecoverer deadLetters, String binding, long pauseMillis,
                       BooleanSupplier running) {
        this.deadLetters = deadLetters;
        this.binding = binding;
        this.pauseMillis = pauseMillis;
        this.running = running;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
        try {
            deadLetters.accept(record, consumer, exception);
        } catch (RuntimeException failed) {
            log.error("Dead-lettering failed for binding {}: topic {}, partition {}, offset {} ({}). The record "
                            + "stays uncommitted and is delivered again after a pause of {} ms; its partition waits "
                            + "until the dead-letter topic takes it.",
                    binding, record.topic(), record.partition(), record.offset(), cause(failed), pauseMillis);
            try {
                ListenerUtils.conditionalSleep(running::getAsBoolean, pauseMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw failed;
        }
    }

    /** The failure and, when it differs, the root of it -- the part that says what actually went wrong. */
    private static String cause(Throwable failed) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failed);
        return root == failed ? failed.toString() : failed.getMessage() + ", caused by " + root;
    }
}
