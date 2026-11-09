package com.oriah.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.oriah.events.RunningConsumer.CatchUpRow;
import com.oriah.events.RunningConsumer.Kind;
import com.oriah.events.RunningConsumer.ProductRow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole catch-up, end to end: two consumers that did not exist while the
 * producer was running -- a FACT consumer ({@code once}, as finance will be)
 * and a PROJECTION ({@code onceInOrder}, as search is) -- start late, while
 * the producer keeps appending and the relay keeps publishing.
 *
 * <p>Each reads the live topic from the earliest message Kafka still holds
 * (the oldest have already been dropped by retention) and, at the same time,
 * a replay of the producer's whole log. There is no cutover between the two:
 * the test delivers them interleaved in the worst order it can arrange --
 * newer snapshots before older ones, a deletion before the updates it ends,
 * duplicates from both streams, rows appended after the replay began arriving
 * only live and before the replay's own records.
 *
 * <p>It must not matter. At the end every fact is applied exactly once, every
 * projected product equals its newest snapshot (or is gone, if that snapshot
 * is a deletion), and both catch-ups are complete at H, the newest row when
 * the producer answered.
 */
class LateConsumerTest {

    private static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private final Deque<AutoCloseable> running = new ArrayDeque<>();

    @BeforeAll
    static void startDatabase() {
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    @AfterEach
    void stopServices() throws Exception {
        while (!running.isEmpty()) {
            running.pop().close();
        }
    }

    @Test
    @DisplayName("late fact and projection consumers, live and replay interleaved in the worst order while the "
            + "producer keeps appending: every fact once, every projection the newest snapshot, both complete at H")
    void worstInterleaving() {
        RunningProducer producer = start(RunningProducer.start(FreshDatabase.in(postgres)));
        String ring = "ring-" + fresh();
        String chain = "chain-" + fresh();
        String watch = "watch-" + fresh();

        // Months of history before either consumer exists.
        producer.productUpdated(ring, "Gold ring");                 // ring seq 1
        producer.sale("sale-1-" + fresh());
        producer.productUpdated(chain, "Silver chain");             // chain seq 1
        producer.productUpdated(ring, "Gold ring, 18ct");           // ring seq 2
        producer.sale("sale-2-" + fresh());
        List<Message<byte[]>> history = producer.publish();
        // Retention has already dropped the oldest three from Kafka: reading
        // the live topic from the earliest offset gives only the last two.
        List<Message<byte[]>> retained = history.subList(3, 5);

        // Both consumers start late; each asks for a replay on start.
        RunningConsumer facts = start(RunningConsumer.start(Kind.FACT, FreshDatabase.in(postgres)));
        RunningConsumer products = start(RunningConsumer.start(Kind.PROJECTION, FreshDatabase.in(postgres)));
        Message<byte[]> factsRequest = only(facts.requests());
        Message<byte[]> productsRequest = only(products.requests());

        // The producer keeps going before the requests reach it.
        producer.productUpdated(ring, "Gold ring, 18ct, new price"); // ring seq 3
        producer.sale("sale-3-" + fresh());
        producer.productDeleted(chain);                              // chain seq 2
        List<Message<byte[]>> beforeAnswer = producer.publish();

        // Live first, newest first: the chain's deletion, the third sale and
        // ring seq 3, then the retained older two (ring seq 2 is now stale).
        for (RunningConsumer consumer : List.of(facts, products)) {
            consumer.live(reversed(beforeAnswer));
            consumer.live(reversed(retained));
            assertThat(consumer.catchUp("probe").replayedThrough()).as("live never advances it").isZero();
        }

        // Now the requests are answered. H is the newest row at this moment.
        long h = producer.newestOutboxId();
        List<Message<byte[]>> factsReplay = producer.answer(factsRequest, facts.name());
        List<Message<byte[]>> productsReplay = producer.answer(productsRequest, products.name());
        // And the producer keeps going: these rows are above H, so they come live only.
        producer.sale("sale-4-" + fresh());
        producer.productDeleted(ring);                               // ring seq 4
        producer.productUpdated(watch, "Steel watch");               // watch seq 1
        List<Message<byte[]>> afterAnswer = producer.publish();

        interleave(facts, factsReplay, retained, beforeAnswer, afterAnswer);
        interleave(products, productsReplay, retained, beforeAnswer, afterAnswer);

        for (RunningConsumer consumer : List.of(facts, products)) {
            CatchUpRow row = consumer.catchUp("probe");
            assertThat(row.replayedThrough()).as(consumer.name()).isEqualTo(h);
            assertThat(row.completedThrough()).as(consumer.name()).isEqualTo(h);
            assertThat(row.completedAt()).as(consumer.name()).isNotNull();
        }
        List<Map<String, Object>> log = producer.log();
        assertThat(facts.appliedSales()).as("every sale exactly once").containsExactlyInAnyOrderElementsOf(sales(log));
        assertThat(facts.products()).as("products are not the fact consumer's business").isEmpty();
        assertThat(products.products()).as("each product as its newest snapshot says")
                .isEqualTo(newestSnapshots(log))
                .containsOnlyKeys(watch);
        assertThat(products.appliedSales()).as("sales are not the projection's business").isEmpty();
    }

    /** The end of a live queue. */
    private static final Message<byte[]> END = MessageBuilder.withPayload(new byte[0]).build();

    @RepeatedTest(3)
    @DisplayName("the same with real threads: the relay on its own schedule, an appender running throughout, "
            + "and each consumer taking its live and replay streams at the same moment on two threads")
    void concurrentProducerAndBothStreams(RepetitionInfo repetition) throws Exception {
        Random random = new Random(repetition.getCurrentRepetition());
        RunningProducer producer = start(RunningProducer.start(FreshDatabase.in(postgres), 20));
        List<String> catalogue = List.of("ring-" + fresh(), "chain-" + fresh(), "watch-" + fresh(), "box-" + fresh());

        // History, published before either consumer exists; retention has
        // already dropped the older half of it.
        for (int i = 0; i < 16; i++) {
            appendSomething(producer, catalogue, random);
        }
        List<Message<byte[]>> history = new ArrayList<>();
        while (history.size() < 16) {
            Message<byte[]> message = producer.nextLive(10_000);
            assertThat(message).as("the relay publishes the history on its own").isNotNull();
            history.add(message);
        }
        List<Message<byte[]>> retained = history.subList(8, 16);

        RunningConsumer facts = start(RunningConsumer.start(Kind.FACT, FreshDatabase.in(postgres)));
        RunningConsumer products = start(RunningConsumer.start(Kind.PROJECTION, FreshDatabase.in(postgres)));
        List<RunningConsumer> consumers = List.of(facts, products);
        Map<RunningConsumer, Message<byte[]>> requests = Map.of(
                facts, only(facts.requests()), products, only(products.requests()));
        Map<RunningConsumer, BlockingQueue<Message<byte[]>>> live = Map.of(
                facts, new LinkedBlockingQueue<>(retained), products, new LinkedBlockingQueue<>(retained));

        ExecutorService threads = Executors.newCachedThreadPool();
        try {
            CountDownLatch halfway = new CountDownLatch(1);
            CountDownLatch answered = new CountDownLatch(1);
            AtomicBoolean appending = new AtomicBoolean(true);
            long appenderSeed = random.nextLong();
            Future<?> appender = threads.submit(() -> {
                Random own = new Random(appenderSeed);
                for (int i = 0; i < 40; i++) {
                    appendSomething(producer, catalogue, own);
                    if (i == 15) {
                        halfway.countDown();
                    }
                    if (i == 30) {
                        // However the threads are scheduled, the last rows
                        // land above H: they reach the consumers live only.
                        assertThat(answered.await(30, TimeUnit.SECONDS)).isTrue();
                    }
                    Thread.sleep(own.nextInt(5));
                }
                appending.set(false);
                return null;
            });
            // Kafka: whatever the relay publishes reaches both consumers' live topics.
            Future<?> broker = threads.submit(() -> {
                while (appending.get() || producer.relayCursor() < producer.newestOutboxId()) {
                    fanOut(producer.nextLive(50), live);
                }
                Message<byte[]> straggler;
                while ((straggler = producer.nextLive(200)) != null) {
                    fanOut(straggler, live);
                }
                live.values().forEach(queue -> queue.add(END));
                return null;
            });
            List<Future<?>> deliveries = new ArrayList<>();
            for (RunningConsumer consumer : consumers) {
                long seed = random.nextLong();
                deliveries.add(threads.submit(() -> deliverLive(consumer, live.get(consumer), new Random(seed))));
            }

            // The requests reach the producer while it is still appending.
            assertThat(halfway.await(30, TimeUnit.SECONDS)).isTrue();
            Map<RunningConsumer, List<Message<byte[]>>> replays = new LinkedHashMap<>();
            for (RunningConsumer consumer : consumers) {
                replays.put(consumer, producer.answer(requests.get(consumer), consumer.name()));
            }
            answered.countDown();
            for (RunningConsumer consumer : consumers) {
                long seed = random.nextLong();
                deliveries.add(threads.submit(() -> deliverReplay(consumer, replays.get(consumer), new Random(seed))));
            }

            appender.get(60, TimeUnit.SECONDS);
            broker.get(60, TimeUnit.SECONDS);
            for (Future<?> delivery : deliveries) {
                delivery.get(60, TimeUnit.SECONDS);
            }

            List<Map<String, Object>> log = producer.log();
            for (RunningConsumer consumer : consumers) {
                List<Message<byte[]>> replay = replays.get(consumer);
                long h = Long.parseLong(Relayed.header(replay.get(replay.size() - 1), "through-outbox-id"));
                assertThat(h).as("the replay stopped at H while the producer kept going")
                        .isLessThan(producer.newestOutboxId());
                CatchUpRow row = consumer.catchUp("probe");
                assertThat(row.replayedThrough()).as(consumer.name()).isEqualTo(h);
                assertThat(row.completedThrough()).as(consumer.name()).isEqualTo(h);
                assertThat(row.completedAt()).as(consumer.name()).isNotNull();
            }
            assertThat(facts.appliedSales()).as("every sale exactly once")
                    .containsExactlyInAnyOrderElementsOf(sales(log));
            assertThat(products.products()).as("each product as its newest snapshot says")
                    .isEqualTo(newestSnapshots(log));
        } finally {
            threads.shutdownNow();
        }
    }

    private static void appendSomething(RunningProducer producer, List<String> catalogue, Random random) {
        String product = catalogue.get(random.nextInt(catalogue.size()));
        switch (random.nextInt(4)) {
            case 0 -> producer.productDeleted(product);
            case 1 -> producer.sale("sale-" + fresh());
            default -> producer.productUpdated(product, "Title " + fresh());
        }
    }

    private static void fanOut(Message<byte[]> message, Map<RunningConsumer, BlockingQueue<Message<byte[]>>> live) {
        if (message != null) {
            live.values().forEach(queue -> queue.add(message));
        }
    }

    /**
     * One consumer's live listener: takes whatever has arrived, delivers it
     * in a shuffled order, and now and then delivers an earlier message again.
     */
    private static Void deliverLive(RunningConsumer consumer, BlockingQueue<Message<byte[]>> queue, Random random)
            throws InterruptedException {
        List<Message<byte[]>> delivered = new ArrayList<>();
        boolean ended = false;
        while (!ended) {
            List<Message<byte[]>> batch = new ArrayList<>();
            batch.add(queue.take());
            queue.drainTo(batch);
            ended = batch.remove(END);
            Collections.shuffle(batch, random);
            for (Message<byte[]> message : batch) {
                consumer.live(message);
                delivered.add(message);
                if (random.nextInt(5) == 0) {
                    consumer.live(delivered.get(random.nextInt(delivered.size())));
                }
            }
        }
        return null;
    }

    /** One consumer's replay listener: the replay in order (one partition), now and then an earlier record again. */
    private static Void deliverReplay(RunningConsumer consumer, List<Message<byte[]>> replay, Random random) {
        for (int i = 0; i < replay.size(); i++) {
            consumer.replay(replay.get(i));
            if (random.nextInt(4) == 0) {
                consumer.replay(replay.get(random.nextInt(i + 1)));
            }
        }
        return null;
    }

    /**
     * The worst order for one consumer: the replay's records arrive in order
     * (a replay topic has one partition) but cut through by live deliveries
     * that are newer, duplicated, or above H.
     */
    private static void interleave(RunningConsumer consumer, List<Message<byte[]>> replay,
                                   List<Message<byte[]>> retained, List<Message<byte[]>> beforeAnswer,
                                   List<Message<byte[]>> afterAnswer) {
        assertThat(replay).as("records 1..8, then the marker").hasSize(9);
        consumer.live(afterAnswer.get(1));          // ring deleted at seq 4, before any replayed ring snapshot
        consumer.replay(replay.subList(0, 3));      // ring seq 1, sale 1, chain seq 1 (chain is already deleted)
        consumer.live(beforeAnswer.get(1));         // sale 3 again
        consumer.replay(replay.subList(3, 6));      // ring seq 2, sale 2, ring seq 3
        consumer.replay(replay.get(4));             // sale 2 redelivered, after the record that followed it
        consumer.live(afterAnswer.get(0));          // sale 4, above H
        consumer.live(afterAnswer.get(2));          // the watch, above H
        consumer.replay(replay.subList(6, 8));      // sale 3, chain deleted
        consumer.replay(replay.get(8));             // the end-of-replay marker
        // A rebalance redelivers the whole live topic once more.
        consumer.live(retained);
        consumer.live(beforeAnswer);
        consumer.live(afterAnswer);
    }

    private static List<UUID> sales(List<Map<String, Object>> log) {
        return log.stream()
                .filter(row -> "sale.recorded".equals(row.get("event_type")))
                .map(row -> (UUID) row.get("event_id"))
                .toList();
    }

    /** From the producer's own log: each product's newest snapshot, or nothing when that is a deletion. */
    private static Map<String, ProductRow> newestSnapshots(List<Map<String, Object>> log) {
        Map<String, Map<String, Object>> newest = new LinkedHashMap<>();
        for (Map<String, Object> row : log) {
            if ("product".equals(row.get("aggregate_type"))) {
                newest.merge((String) row.get("aggregate_id"), row,
                        (a, b) -> ((Number) a.get("seq")).longValue() > ((Number) b.get("seq")).longValue() ? a : b);
            }
        }
        Map<String, ProductRow> expected = new LinkedHashMap<>();
        newest.forEach((id, row) -> {
            if ("product.updated".equals(row.get("event_type"))) {
                expected.put(id, new ProductRow(((Number) row.get("seq")).longValue(), (String) row.get("payload")));
            }
        });
        return expected;
    }

    private <T extends AutoCloseable> T start(T service) {
        running.push(service);
        return service;
    }

    private static List<Message<byte[]>> reversed(List<Message<byte[]>> messages) {
        List<Message<byte[]>> copy = new ArrayList<>(messages);
        java.util.Collections.reverse(copy);
        return copy;
    }

    private static Message<byte[]> only(List<Message<byte[]>> requests) {
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private static String fresh() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
