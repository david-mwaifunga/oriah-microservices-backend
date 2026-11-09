package com.oriah.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.oriah.events.Relayed.all;
import static com.oriah.events.Relayed.headers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Why appends take a lock: the relay keeps one number, "published up to
 * outbox_id N". That is only safe if no row with an id below N can still
 * commit after the relay has moved past it. Ids are handed out when a row
 * is inserted, but transactions commit in any order -- so without the lock,
 * a slow transaction holding id 10 could commit after id 11 was already
 * published, and id 10 would never be published at all.
 *
 * <p>The advisory lock makes every appending transaction wait for the one
 * before it to finish, from its append to its commit. Ids are then given
 * out in commit order, and the cursor can never step over a late row.
 */
@ProbeTest
class OutboxOrderingTest {

    private static final String TOPIC = "oriah.probe";

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutputDestination output;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void startFromAnEmptyBacklog() {
        Relayed.drain(relay, output);
    }

    @Test
    @DisplayName("transaction A appends and holds before commit: B's append waits, gets the higher id, "
            + "and the relay never publishes B before A")
    void lateCommitIsNeverSkipped() throws Exception {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        CountDownLatch aAppended = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AppendedEvent> txA = pool.submit(() -> inTransaction(() -> {
                AppendedEvent event = writer.append(draft(a));
                aAppended.countDown();
                await(releaseA);
                return event;
            }));
            assertThat(aAppended.await(10, TimeUnit.SECONDS)).isTrue();

            Future<AppendedEvent> txB = pool.submit(() -> inTransaction(() -> writer.append(draft(b))));
            assertThatThrownBy(() -> txB.get(1, TimeUnit.SECONDS))
                    .as("B's append must wait for A to commit")
                    .isInstanceOf(TimeoutException.class);

            // A relay round while A is still open: nothing is committed, so
            // nothing is published and the cursor stays where it was.
            assertThat(relay.relayBatch()).isZero();

            releaseA.countDown();
            AppendedEvent eventA = txA.get(10, TimeUnit.SECONDS);
            AppendedEvent eventB = txB.get(10, TimeUnit.SECONDS);
            assertThat(eventB.outboxId()).isGreaterThan(eventA.outboxId());

            while (relay.relayBatch() > 0) {
                // publish both
            }
            assertThat(headers(all(output, TOPIC), "event-id"))
                    .containsExactly(eventA.eventId().toString(), eventB.eventId().toString());
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("concurrent appends to one aggregate all succeed with distinct, consecutive seqs")
    void concurrentAppendsToOneAggregate() throws Exception {
        String order = UUID.randomUUID().toString();
        int threads = 8;
        int each = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<Long>>> results = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    await(start);
                    List<Long> seqs = new ArrayList<>();
                    for (int i = 0; i < each; i++) {
                        seqs.add(inTransaction(() -> writer.append(draft(order))).seq());
                    }
                    return seqs;
                }));
            }
            start.countDown();
            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> result : results) {
                all.addAll(result.get(60, TimeUnit.SECONDS));
            }

            assertThat(all).containsExactlyInAnyOrderElementsOf(
                    LongStream.rangeClosed(1, (long) threads * each).boxed().toList());
        } finally {
            pool.shutdownNow();
        }
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static EventDraft draft(String aggregateId) {
        return new EventDraft("order", aggregateId, "order.created", 1, TOPIC,
                Instant.parse("2026-11-09T07:30:00Z"), "system:probe", Map.of("orderId", aggregateId));
    }
}
