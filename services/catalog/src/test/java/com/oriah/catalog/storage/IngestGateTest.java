package com.oriah.catalog.storage;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The limit on how much photograph catalog works on at once.
 *
 * <p>Found by uploading the largest picture catalog accepts (8000 x 5000, turned upright
 * from EXIF orientation 6) many at a time: each one needs about 300 MiB of heap while it is
 * decoded, rotated and re-encoded, catalog's heap is 575 MiB, and 124 at once killed it with
 * {@code OutOfMemoryError}. The gate gives each upload a share of a fixed working-memory
 * budget, makes the rest wait a little, and answers 503 with {@code Retry-After} to those
 * that cannot be served, so a burst of uploads costs the admin a retry, not the service its
 * life.
 */
class IngestGateTest {

    private static final long MIB = 1024 * 1024;

    /** The biggest 8-bit picture: 40 megapixels at 8 bytes a pixel = 320,000,000 B = 306 MiB. */
    private static final long BIG = 40_000_000L * 8;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("memory is charged in whole MiB, rounded up, never less than one")
    void chargeIsWholeMiBRoundedUp() {
        assertThat(IngestGate.workingMemoryMiB(BIG)).isEqualTo(306);
        assertThat(IngestGate.workingMemoryMiB(480_000L * 8)).isEqualTo(4);   // 3,840,000 B
        assertThat(IngestGate.workingMemoryMiB(MIB)).isEqualTo(1);
        assertThat(IngestGate.workingMemoryMiB(MIB + 1)).isEqualTo(2);
        assertThat(IngestGate.workingMemoryMiB(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("a budget smaller than the biggest 8-bit picture is refused at start-up, not discovered at the first upload")
    void budgetMustFitTheBiggestImage() {
        assertThatThrownBy(() -> new IngestGate(305, 4, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("306");
        // and exactly enough is fine
        new IngestGate(306, 4, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("nonsense settings and requests are refused")
    void settingsAreChecked() {
        assertThatThrownBy(() -> new IngestGate(400, -1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestGate(400, 4, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestGate(400, 4, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestGate(400, 4, Duration.ofSeconds(1)).admit(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a picture that needs more than the whole budget is refused with the sizes, not queued for ever")
    void pictureBiggerThanTheBudgetIsRefusedAtOnce() {
        var gate = new IngestGate(400, 4, Duration.ofSeconds(30));

        long t0 = System.nanoTime();
        assertThatThrownBy(() -> gate.admit(500 * MIB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("400");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
        assertThat(gate.availableMiB()).isEqualTo(400);
    }

    @Test
    @DisplayName("small images run side by side while the budget lasts")
    void smallImagesShareTheBudget() {
        var gate = new IngestGate(400, 4, Duration.ofSeconds(1));
        // 12 megapixels at 8 bytes = 92 MiB each; four fit in 400 MiB, a fifth would not
        long twelveMp = 12_000_000L * 8;
        try (var a = gate.admit(twelveMp); var b = gate.admit(twelveMp);
             var c = gate.admit(twelveMp); var d = gate.admit(twelveMp)) {
            assertThat(gate.availableMiB()).isEqualTo(400 - 4 * 92);
        }
        assertThat(gate.availableMiB()).isEqualTo(400);
    }

    @Test
    @DisplayName("a second biggest-possible image waits for the first and then goes ahead")
    void secondBigImageWaitsForTheFirst() throws Exception {
        var gate = new IngestGate(400, 4, Duration.ofSeconds(10));
        var first = gate.admit(BIG);

        var started = new CountDownLatch(1);
        Future<IngestGate.Permit> second = pool.submit(() -> {
            started.countDown();
            return gate.admit(BIG);
        });
        started.await();
        waitUntil(() -> gate.waiting() == 1);
        assertThat(second.isDone()).as("the second must not be admitted while the first holds the budget").isFalse();

        first.close();

        var permit = second.get(5, TimeUnit.SECONDS);
        assertThat(gate.waiting()).isZero();
        permit.close();
        assertThat(gate.availableMiB()).isEqualTo(400);
    }

    @Test
    @DisplayName("waiting longer than the limit is a 503 that says when to try again")
    void waitingTooLongIsA503() {
        var gate = new IngestGate(400, 4, Duration.ofMillis(200));
        try (var first = gate.admit(BIG)) {
            assertThatThrownBy(() -> gate.admit(BIG))
                    .isInstanceOfSatisfying(ImageIngestBusyException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.getHeaders().getFirst("Retry-After")).isEqualTo("5");
                        assertThat(e.getReason()).contains("busy");
                    });
        }
        // the refused request left nothing behind
        assertThat(gate.availableMiB()).isEqualTo(400);
        assertThat(gate.waiting()).isZero();
    }

    @Test
    @DisplayName("when too many are already waiting, the next one is refused at once instead of queueing")
    void fullQueueRefusesAtOnce() throws Exception {
        var gate = new IngestGate(400, 2, Duration.ofSeconds(30));
        var first = gate.admit(BIG);

        var done = new ExecutorCompletionService<IngestGate.Permit>(pool);
        done.submit(() -> gate.admit(BIG));
        done.submit(() -> gate.admit(BIG));
        waitUntil(() -> gate.waiting() == 2);

        long t0 = System.nanoTime();
        assertThatThrownBy(() -> gate.admit(BIG)).isInstanceOf(ImageIngestBusyException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("refused without waiting").isLessThan(Duration.ofSeconds(2));

        first.close();
        // the two that were waiting are served one after the other, in whichever order the
        // scheduler started them: take whichever is admitted first, give it back, then the other
        var p1 = done.poll(5, TimeUnit.SECONDS);
        assertThat(p1).as("one of the waiting uploads is admitted").isNotNull();
        p1.get().close();
        var p2 = done.poll(5, TimeUnit.SECONDS);
        assertThat(p2).as("and then the other").isNotNull();
        p2.get().close();
        assertThat(gate.availableMiB()).isEqualTo(400);
        assertThat(gate.waiting()).isZero();
    }

    @Test
    @DisplayName("closing a permit twice gives the memory back once")
    void closeIsIdempotent() {
        var gate = new IngestGate(400, 4, Duration.ofSeconds(1));
        var permit = gate.admit(12_000_000L * 8);
        permit.close();
        permit.close();
        assertThat(gate.availableMiB()).isEqualTo(400);
    }

    @Test
    @DisplayName("a thread interrupted while waiting is refused and keeps its interrupt")
    void interruptedWhileWaiting() throws Exception {
        var gate = new IngestGate(400, 4, Duration.ofSeconds(30));
        var first = gate.admit(BIG);
        var interruptedAfter = new AtomicBoolean();
        Future<?> waiter = pool.submit(() -> {
            try {
                gate.admit(BIG);
            } catch (ImageIngestBusyException expected) {
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            }
            return null;
        });
        waitUntil(() -> gate.waiting() == 1);
        pool.shutdownNow();
        waiter.get(5, TimeUnit.SECONDS);
        assertThat(interruptedAfter.get()).isTrue();
        assertThat(gate.waiting()).isZero();
        first.close();
        assertThat(gate.availableMiB()).isEqualTo(400);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached in 5 s");
            }
            Thread.sleep(5);
        }
    }
}
