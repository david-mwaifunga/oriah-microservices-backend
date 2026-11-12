package com.oriah.procurement.service;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How many invoices are taken in at once. A 5 MB upload is held in memory several times over while it is read,
 * fingerprinted and written (about 15 MB), and the service's heap is small (217 MiB): measured live, 16 uploads
 * at once left half of them a 500 and the container at 96% of its limit. So only a few run together; the rest wait
 * a little, and if the wait runs out they are told to try again (503 with Retry-After), never failed half-way.
 */
class UploadGateTest {

    @Test
    @DisplayName("work runs, and its answer comes back, when there is room")
    void runsWhenThereIsRoom() throws Exception {
        UploadGate gate = new UploadGate(2, Duration.ofSeconds(1));

        String answer = gate.run(() -> "stored");

        assertThat(answer).isEqualTo("stored");
    }

    @Test
    @DisplayName("no more than the permitted number run at the same moment, however many arrive")
    void neverMoreThanThePermittedAtOnce() throws Exception {
        UploadGate gate = new UploadGate(3, Duration.ofSeconds(30));
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger mostInside = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            CountDownLatch go = new CountDownLatch(1);
            java.util.List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return gate.run(() -> {
                        int now = inside.incrementAndGet();
                        mostInside.accumulateAndGet(now, Math::max);
                        Thread.sleep(50);
                        inside.decrementAndGet();
                        return 1;
                    });
                }));
            }
            go.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(mostInside.get()).as("the most that ever ran together").isEqualTo(3);
    }

    @Test
    @DisplayName("when the wait runs out the answer is 503 with Retry-After, and the work never starts")
    void tooBusyIsAn503() throws Exception {
        UploadGate gate = new UploadGate(1, Duration.ofMillis(200));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> gate.run(() -> {
                holding.countDown();
                release.await(30, TimeUnit.SECONDS);
                return 0;
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            AtomicInteger started = new AtomicInteger();

            assertThatThrownBy(() -> gate.run(() -> started.incrementAndGet()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode().value()).isEqualTo(503);
                        assertThat(e.getHeaders().getFirst("Retry-After")).isEqualTo("5");
                        assertThat(e.getReason()).contains("busy").contains("try again");
                    });
            assertThat(started.get()).as("the refused upload never ran").isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the permit is given back when the work fails, so one bad upload cannot starve the rest")
    void aFailureGivesTheRoomBack() throws Exception {
        UploadGate gate = new UploadGate(1, Duration.ofMillis(300));

        assertThatThrownBy(() -> gate.run(() -> {
            throw new IllegalArgumentException("not a PDF");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(gate.run(() -> "next one runs")).isEqualTo("next one runs");
    }

    @Test
    @DisplayName("a gate of nothing, or a negative wait, is refused when it is built")
    void nonsenseIsRefused() {
        assertThatThrownBy(() -> new UploadGate(0, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UploadGate(1, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
