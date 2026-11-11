package com.oriah.catalog.storage;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 40 megapixels: the most the ingest accepts. */
    private static final long MAX_PIXELS = 40_000_000L;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("an image is charged 8 bytes a pixel of working memory, rounded up to a whole MiB")
    void chargeIsEightBytesAPixel() {
        // decoded raster (3-4 bytes) + the rotated copy (4 bytes)
        assertThat(IngestGate.workingMemoryMiB(MAX_PIXELS)).isEqualTo(306);        // 320,000,000 B
        assertThat(IngestGate.workingMemoryMiB(480_000)).isEqualTo(4);             // 3,840,000 B
        assertThat(IngestGate.workingMemoryMiB(1)).isEqualTo(1);                   // never zero
    }

    @Test
    @DisplayName("a budget smaller than the biggest image is refused at start-up, not discovered at the first upload")
    void budgetMustFitTheBiggestImage() {
        assertThatThrownBy(() -> new IngestGate(305, 4, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("306");
        // and exactly enough is fine
        new IngestGate(306, 4, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("nonsense settings are refused")
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
    @DisplayName("small images run side by side while the budget lasts")
    void smallImagesShareTheBudget() {
        var gate = new IngestGate(400, 4, Duration.ofSeconds(1));
        // 12 megapixels = 92 MiB each; four fit in 400 MiB, a fifth would not
        try (var a = gate.admit(12_000_000); var b = gate.admit(12_000_000);
             var c = gate.admit(12_000_000); var d = gate.admit(12_000_000)) {
            assertThat(gate.availableMiB()).isEqualTo(400 - 4 * 92);
        }
        assertThat(gate.availableMiB()).isEqualTo(400);
    }

    @Test
    @DisplayName("a second biggest-possible image waits for the first and then goes ahead")
    void secondBigImageWaitsForTheFirst() throws Exception {
        var gate = new IngestGate(400, 4, Duration.ofSeconds(10));
        var first = gate.admit(MAX_PIXELS);

        var started = new CountDownLatch(1);
        Future<IngestGate.Permit> second = pool.submit(() -> {
            started.countDown();
            return gate.admit(MAX_PIXELS);
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
        try (var first = gate.admit(MAX_PIXELS)) {
            assertThatThrownBy(() -> gate.admit(MAX_PIXELS))
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
        var first = gate.admit(MAX_PIXELS);

        var attempts = new AtomicInteger();
        Future<?> w1 = pool.submit(() -> { attempts.incrementAndGet(); return gate.admit(MAX_PIXELS); });
        Future<?> w2 = pool.submit(() -> { attempts.incrementAndGet(); return gate.admit(MAX_PIXELS); });
        waitUntil(() -> gate.waiting() == 2);

        long t0 = System.nanoTime();
        assertThatThrownBy(() -> gate.admit(MAX_PIXELS)).isInstanceOf(ImageIngestBusyException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("refused without waiting").isLessThan(Duration.ofSeconds(2));

        first.close();
        // the two that were waiting are served, one after the other
        var p1 = (IngestGate.Permit) w1.get(5, TimeUnit.SECONDS);
        p1.close();
        var p2 = (IngestGate.Permit) w2.get(5, TimeUnit.SECONDS);
        p2.close();
        assertThat(gate.availableMiB()).isEqualTo(400);
        assertThat(gate.waiting()).isZero();
    }

    @Test
    @DisplayName("closing a permit twice gives the memory back once")
    void closeIsIdempotent() {
        var gate = new IngestGate(400, 4, Duration.ofSeconds(1));
        var permit = gate.admit(12_000_000);
        permit.close();
        permit.close();
        assertThat(gate.availableMiB()).isEqualTo(400);
    }

    @Test
    @DisplayName("a thread interrupted while waiting is refused and keeps its interrupt")
    void interruptedWhileWaiting() throws Exception {
        var gate = new IngestGate(400, 4, Duration.ofSeconds(30));
        var first = gate.admit(MAX_PIXELS);
        var interruptedAfter = new java.util.concurrent.atomic.AtomicBoolean();
        Future<?> waiter = pool.submit(() -> {
            try {
                gate.admit(MAX_PIXELS);
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
