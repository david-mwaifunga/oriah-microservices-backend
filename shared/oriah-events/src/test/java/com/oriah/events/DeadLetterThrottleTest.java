package com.oriah.events;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.KafkaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pause after a failed dead-letter publish.
 *
 * <p>Why it exists: when the dead-letter topic does not take a record, the
 * record is not committed and Kafka delivers it again. A failure that is not
 * retried (a refusal) goes straight back to the dead-letter publish on every
 * delivery, with no back-off in between; if that publish fails fast (no
 * permission, say), the consumer would spin, logging an error as fast as it
 * can. So after a failed publish the module logs one ERROR and waits the
 * binding's back-off max-interval before letting the record come round again.
 * Spring Kafka has no setting for this (its back-off covers retries of the
 * listener, not a failed recovery), hence a wrapper.
 *
 * <p>Fast failures are the case that matters, so they are what is tested
 * here; {@code DeadLetterKafkaTest} shows it on a real broker.
 */
@ExtendWith(OutputCaptureExtension.class)
class DeadLetterThrottleTest {

    private static final ConsumerRecord<byte[], byte[]> RECORD =
            new ConsumerRecord<>("oriah.order", 2, 42L, new byte[] {1}, new byte[] {2});
    private static final Exception LISTENER_FAILED = new IllegalArgumentException("undeclared event type");

    @Test
    @DisplayName("a dead-letter publish that works passes straight through: no pause, no error logged")
    void published(CapturedOutput output) {
        DeadLetterThrottle throttle = new DeadLetterThrottle((record, consumer, exception) -> {
        }, "orderEvents-in-0", 5_000, () -> true);

        long started = System.nanoTime();
        throttle.accept(RECORD, null, LISTENER_FAILED);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(output).doesNotContain("Dead-lettering failed");
    }

    @Test
    @DisplayName("a publish that fails at once: one ERROR naming the binding, topic, partition, offset and cause, "
            + "then the pause, then the same failure is thrown so the record is not committed")
    void failedFast(CapturedOutput output) {
        KafkaException refused = new KafkaException("Dead-letter publication to oriah.order.dlq.notification failed",
                new TimeoutException("Topic oriah.order.dlq.notification not present in metadata"));
        DeadLetterThrottle throttle = new DeadLetterThrottle((record, consumer, exception) -> {
            throw refused;
        }, "orderEvents-in-0", 400, () -> true);

        // Timed on the wall clock, the clock the pause itself is measured on:
        // spring-kafka's ListenerUtils.conditionalSleep ends the pause once
        // System.currentTimeMillis() passes its deadline. That clock moves in
        // coarse steps on Windows, so on System.nanoTime() the same pause can
        // read a millisecond or so short of 400 ms (0.3987 s was seen,
        // 2026-11-09) while on its own clock it is never short.
        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> throttle.accept(RECORD, null, LISTENER_FAILED)).isSameAs(refused);

        assertThat(Duration.ofMillis(System.currentTimeMillis() - started)).as("the pause")
                .isGreaterThanOrEqualTo(Duration.ofMillis(400));
        assertThat(output).contains("ERROR")
                .contains("Dead-lettering failed for binding orderEvents-in-0")
                .contains("topic oriah.order, partition 2, offset 42")
                .contains("not present in metadata")
                .contains("400 ms");
    }

    @Test
    @DisplayName("the service stopping cuts the pause short, so a stuck dead-letter topic never holds up "
            + "shutdown; the failure is still thrown and the record stays uncommitted")
    void stopping() {
        KafkaException refused = new KafkaException("Dead-letter publication failed");
        DeadLetterThrottle throttle = new DeadLetterThrottle((record, consumer, exception) -> {
            throw refused;
        }, "orderEvents-in-0", 10_000, () -> false);

        long started = System.nanoTime();
        assertThatThrownBy(() -> throttle.accept(RECORD, null, LISTENER_FAILED)).isSameAs(refused);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("interrupted during the pause: the interrupt is kept for the caller and the failure is thrown "
            + "at once")
    void interrupted() throws InterruptedException {
        KafkaException refused = new KafkaException("Dead-letter publication failed");
        DeadLetterThrottle throttle = new DeadLetterThrottle((record, consumer, exception) -> {
            throw refused;
        }, "orderEvents-in-0", 30_000, () -> true);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread consumerThread = new Thread(() -> {
            try {
                throttle.accept(RECORD, null, LISTENER_FAILED);
            } catch (RuntimeException e) {
                thrown.set(e);
            }
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });

        long started = System.nanoTime();
        consumerThread.start();
        Thread.sleep(300);
        consumerThread.interrupt();
        consumerThread.join(10_000);

        assertThat(consumerThread.isAlive()).as("still pausing").isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(thrown.get()).isSameAs(refused);
        assertThat(stillInterrupted).as("the interrupt flag").isTrue();
    }
}
