package com.oriah.events;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Inbox applies each event once in effect, inside the consumer's own
 * transaction: the marker that says "applied" commits with the effect or
 * not at all.
 *
 * <p>{@code once} is for facts (each applied once, in any order).
 * {@code onceInOrder} is for projections of full snapshots: it also keeps
 * the highest seq applied per aggregate, so an older snapshot arriving
 * late is skipped -- even after the projection has deleted its own row.
 *
 * <p>The projection here is a real table written in the same transaction
 * as the markers, as search's index or inventory's stock would be.
 */
@ProbeTest
class InboxTest {

    /** The two ways in, so the rules they share are proved on both. */
    enum Method {
        ONCE {
            @Override
            void deliver(Inbox inbox, InboxConsumer consumer, Map<String, ?> headers, Runnable effect) {
                inbox.once(consumer, headers, effect);
            }
        },
        ONCE_IN_ORDER {
            @Override
            void deliver(Inbox inbox, InboxConsumer consumer, Map<String, ?> headers, Runnable effect) {
                inbox.onceInOrder(consumer, headers, effect);
            }
        };

        abstract void deliver(Inbox inbox, InboxConsumer consumer, Map<String, ?> headers, Runnable effect);
    }

    @Autowired
    private Inbox inbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * A fresh consumer per test: processed_event is append-only, so no test can clean up after another.
     * payment.succeeded is about a payment_intent, as payment's events are: the aggregate is declared per
     * event type, never guessed from the event type's name.
     */
    private final InboxConsumer consumer = InboxConsumer.named("probe-" + UUID.randomUUID().toString().substring(0, 8))
            .handles("product", "product.updated", 1)
            .handles("product", "product.deleted", 1)
            .handles("payment_intent", "payment.succeeded", 1)
            .ignores("product.reviewed");

    private final AtomicInteger effects = new AtomicInteger();

    @BeforeEach
    void projectionTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS probe_projection (
                    product_id VARCHAR(64) PRIMARY KEY,
                    seq        BIGINT NOT NULL
                )""");
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    @DisplayName("a duplicate delivery applies once: one effect, one marker")
    void duplicateAppliesOnce(Method method) {
        Map<String, Object> headers = envelope("product.updated", fresh(), 1);

        deliver(method, headers, effects::incrementAndGet);
        deliver(method, headers, effects::incrementAndGet);

        assertThat(effects).hasValue(1);
        assertThat(markers()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    @DisplayName("an effect that throws leaves no marker, so the retry applies it")
    void throwingEffectLeavesNoMarker(Method method) {
        String product = fresh();
        Map<String, Object> headers = envelope("product.updated", product, 1);

        assertThatThrownBy(() -> deliver(method, headers, () -> {
            project(product, 1);
            throw new IllegalStateException("projection down");
        })).hasMessage("projection down");

        assertThat(markers()).isZero();
        assertThat(projectedSeq(product)).isNull();
        assertThat(lastSeq(product)).isNull();

        deliver(method, headers, () -> project(product, 1));
        assertThat(markers()).isEqualTo(1);
        assertThat(projectedSeq(product)).isEqualTo(1L);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("malformed")
    @DisplayName("missing or malformed envelope headers are refused before any marker, and nothing is applied")
    void malformedHeadersRefused(Method method, String damaged, Consumer<Map<String, Object>> damage) {
        String header = damaged.substring(0, damaged.indexOf(' '));
        Map<String, Object> headers = envelope("product.updated", fresh(), 1);
        damage.accept(headers);

        assertThatThrownBy(() -> deliver(method, headers, effects::incrementAndGet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(header);

        assertThat(effects).hasValue(0);
        assertThat(markers()).isZero();
    }

    static Stream<Arguments> malformed() {
        List<Arguments> cases = new ArrayList<>();
        Map<String, Consumer<Map<String, Object>>> damages = new LinkedHashMap<>();
        damages.put("event-id missing", headers -> headers.remove("event-id"));
        damages.put("event-id not a UUID", headers -> headers.put("event-id", "42"));
        damages.put("event-type missing", headers -> headers.remove("event-type"));
        damages.put("event-type blank", headers -> headers.put("event-type", " "));
        damages.put("aggregate-type missing", headers -> headers.remove("aggregate-type"));
        damages.put("aggregate-type over 64", headers -> headers.put("aggregate-type", "p".repeat(65)));
        damages.put("aggregate-id blank", headers -> headers.put("aggregate-id", ""));
        damages.put("aggregate-id over 64", headers -> headers.put("aggregate-id", "1".repeat(65)));
        damages.put("seq missing", headers -> headers.remove("seq"));
        damages.put("seq not a number", headers -> headers.put("seq", "two"));
        damages.put("seq zero", headers -> headers.put("seq", "0"));
        damages.put("schema-version missing", headers -> headers.remove("schema-version"));
        damages.put("schema-version not a number", headers -> headers.put("schema-version", "v1"));
        for (Method method : Method.values()) {
            damages.forEach((name, damage) -> cases.add(Arguments.of(method, name, damage)));
        }
        return cases.stream();
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    @DisplayName("a type the consumer never declared is refused before any marker (to the DLQ, for a later version to replay)")
    void undeclaredTypeRefused(Method method) {
        Map<String, Object> headers = envelope("product.archived", fresh(), 1);

        assertThatThrownBy(() -> deliver(method, headers, effects::incrementAndGet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.archived");

        assertThat(effects).hasValue(0);
        assertThat(markers()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    @DisplayName("a payload version the consumer cannot read is refused before any marker")
    void unsupportedSchemaVersionRefused(Method method) {
        Map<String, Object> headers = envelope("product.updated", fresh(), 1);
        headers.put("schema-version", "2");

        assertThatThrownBy(() -> deliver(method, headers, effects::incrementAndGet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated")
                .hasMessageContaining("2");

        assertThat(effects).hasValue(0);
        assertThat(markers()).isZero();
    }

    @ParameterizedTest(name = "{0}: aggregate-type \"{1}\"")
    @MethodSource("wrongAggregate")
    @DisplayName("a handled type whose aggregate-type is not the one declared for it is refused before any marker: "
            + "nothing is applied, and no seq is recorded under any aggregate")
    void wrongAggregateTypeRefused(Method method, String aggregateType) {
        String product = fresh();
        Map<String, Object> headers = envelope("product.updated", product, 5);
        headers.put("aggregate-type", aggregateType);

        assertThatThrownBy(() -> deliver(method, headers, () -> {
            effects.incrementAndGet();
            project(product, 5);
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated")
                .hasMessageContaining("'product'")
                .hasMessageContaining("'" + aggregateType + "'");

        assertThat(effects).hasValue(0);
        assertThat(markers()).isZero();
        assertThat(aggregates()).as("processed_aggregate rows for this consumer").isZero();
        assertThat(projectedSeq(product)).isNull();
    }

    /**
     * "order": an aggregate the consumer never declared. "payment_intent": one it declared, but for another
     * event type. "Product": the match is exact, as the producer wrote it -- in processed_aggregate a
     * differently spelled type is a different aggregate.
     */
    static Stream<Arguments> wrongAggregate() {
        List<Arguments> cases = new ArrayList<>();
        for (Method method : Method.values()) {
            for (String aggregateType : List.of("order", "payment_intent", "Product")) {
                cases.add(Arguments.of(method, aggregateType));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    @DisplayName("an event whose aggregate-type is the one declared for its type is applied -- payment.succeeded "
            + "about a payment_intent, an aggregate its type's name does not give")
    void declaredAggregateTypeApplied(Method method) {
        String intent = fresh();
        Map<String, Object> headers = envelope("payment.succeeded", intent, 1);
        headers.put("aggregate-type", "payment_intent");

        deliver(method, headers, effects::incrementAndGet);

        assertThat(effects).hasValue(1);
        assertThat(markers()).isEqualTo(1);
        // Only a projection keeps a seq, and it keeps it under the declared aggregate.
        assertThat(lastSeq("payment_intent", intent)).isEqualTo(method == Method.ONCE_IN_ORDER ? 1L : null);
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    @DisplayName("an ignored type is marked whatever its aggregate-type says: it is never applied, so it touches "
            + "no aggregate's seq")
    void ignoredTypeAnyAggregate(Method method) {
        Map<String, Object> headers = envelope("product.reviewed", fresh(), 7);
        headers.put("aggregate-type", "review");

        deliver(method, headers, effects::incrementAndGet);

        assertThat(effects).hasValue(0);
        assertThat(markers()).isEqualTo(1);
        assertThat(aggregates()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    @DisplayName("an ignored type gets its marker and no effect, and does not move the projection's seq")
    void ignoredTypeMarkedNotApplied(Method method) {
        String product = fresh();

        deliver(method, envelope("product.reviewed", product, 7), effects::incrementAndGet);

        assertThat(effects).hasValue(0);
        assertThat(markers()).isEqualTo(1);
        assertThat(lastSeq(product)).isNull();
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    @DisplayName("envelope headers that arrive as raw bytes (Kafka carries every header that way) are read as UTF-8 text")
    void headersAsBytes(Method method) {
        Map<String, Object> headers = new HashMap<>();
        envelope("product.updated", fresh(), 1)
                .forEach((name, value) -> headers.put(name, value.toString().getBytes(StandardCharsets.UTF_8)));

        deliver(method, headers, effects::incrementAndGet);

        assertThat(effects).hasValue(1);
        assertThat(markers()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    @DisplayName("outside a transaction it is refused: the marker must commit with the effect")
    void outsideTransactionRefused(Method method) {
        Map<String, Object> headers = envelope("product.updated", fresh(), 1);

        assertThatThrownBy(() -> method.deliver(inbox, consumer, headers, effects::incrementAndGet))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(effects).hasValue(0);
        assertThat(markers()).isZero();
    }

    @Test
    @DisplayName("onceInOrder skips an older snapshot that arrives late, and marks it so a redelivery is a duplicate")
    void olderSeqSkipped() {
        String product = fresh();
        Map<String, Object> older = envelope("product.updated", product, 2);

        deliver(Method.ONCE_IN_ORDER, envelope("product.updated", product, 3), () -> project(product, 3));
        deliver(Method.ONCE_IN_ORDER, older, () -> project(product, 2));
        deliver(Method.ONCE_IN_ORDER, older, () -> project(product, 2));

        assertThat(projectedSeq(product)).isEqualTo(3L);
        assertThat(lastSeq(product)).isEqualTo(3L);
        assertThat(marker(older)).as("the skipped snapshot is final for this consumer").isEqualTo(1);
    }

    @Test
    @DisplayName("onceInOrder keeps a tombstone: after the projection deletes its row, a late older snapshot "
            + "does not bring it back")
    void tombstoneSurvivesDelete() {
        String product = fresh();

        deliver(Method.ONCE_IN_ORDER, envelope("product.updated", product, 1), () -> project(product, 1));
        deliver(Method.ONCE_IN_ORDER, envelope("product.deleted", product, 3), () -> unproject(product));
        deliver(Method.ONCE_IN_ORDER, envelope("product.updated", product, 2), () -> project(product, 2));

        assertThat(projectedSeq(product)).as("still deleted").isNull();
        assertThat(lastSeq(product)).isEqualTo(3L);
    }

    @ParameterizedTest(name = "seq {0} starts first, seq {1} arrives while it is uncommitted; seq 1 already applied: {2}")
    @CsvSource({"2, 3, false", "3, 2, false", "2, 3, true", "3, 2, true"})
    @DisplayName("concurrent deliveries of seq 2 and seq 3, in either order, end at seq 3 -- for a new aggregate "
            + "(the first deliveries race to create its row) and for a known one (they race to read its seq)")
    void concurrentDeliveries(long first, long second, boolean known) throws Exception {
        String product = fresh();
        if (known) {
            deliver(Method.ONCE_IN_ORDER, envelope("product.updated", product, 1), () -> project(product, 1));
        }
        List<Long> applied = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstInEffect = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        boolean secondWaited;
        try {
            Future<?> a = pool.submit(() -> deliver(Method.ONCE_IN_ORDER,
                    envelope("product.updated", product, first), () -> {
                        project(product, first);
                        applied.add(first);
                        firstInEffect.countDown();
                        awaitQuietly(releaseFirst);
                    }));
            assertThat(firstInEffect.await(10, TimeUnit.SECONDS))
                    .as("the first delivery reached its effect").isTrue();

            Future<?> b = pool.submit(() -> deliver(Method.ONCE_IN_ORDER,
                    envelope("product.updated", product, second), () -> {
                        project(product, second);
                        applied.add(second);
                    }));
            secondWaited = waitsOnALock(b);
            releaseFirst.countDown();
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        assertThat(secondWaited).as("the second delivery ran while the first was uncommitted, and waited for it")
                .isTrue();
        assertThat(lastSeq(product)).isEqualTo(3L);
        assertThat(projectedSeq(product)).isEqualTo(3L);
        assertThat(applied).containsExactlyElementsOf(first == 2 ? List.of(2L, 3L) : List.of(3L));
    }

    /** True once the database shows a session waiting on a lock; false if the delivery finished without waiting. */
    private boolean waitsOnALock(Future<?> delivery) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && !delivery.isDone()) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'""", Integer.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deliver(Method method, Map<String, ?> headers, Runnable effect) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> method.deliver(inbox, consumer, headers, effect));
    }

    private void project(String product, long seq) {
        jdbc.update("""
                INSERT INTO probe_projection (product_id, seq) VALUES (?, ?)
                ON CONFLICT (product_id) DO UPDATE SET seq = excluded.seq""", product, seq);
    }

    private void unproject(String product) {
        jdbc.update("DELETE FROM probe_projection WHERE product_id = ?", product);
    }

    private Long projectedSeq(String product) {
        List<Long> seq = jdbc.queryForList("SELECT seq FROM probe_projection WHERE product_id = ?", Long.class, product);
        return seq.isEmpty() ? null : seq.get(0);
    }

    private Long lastSeq(String product) {
        return lastSeq("product", product);
    }

    private Long lastSeq(String aggregateType, String aggregateId) {
        List<Long> seq = jdbc.queryForList("""
                SELECT last_seq FROM processed_aggregate
                WHERE consumer = ? AND aggregate_type = ? AND aggregate_id = ?""",
                Long.class, consumer.name(), aggregateType, aggregateId);
        return seq.isEmpty() ? null : seq.get(0);
    }

    /** Every processed_aggregate row of this consumer, whatever its aggregate. */
    private int aggregates() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_aggregate WHERE consumer = ?",
                Integer.class, consumer.name());
    }

    private int markers() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE consumer = ?",
                Integer.class, consumer.name());
    }

    private int marker(Map<String, Object> headers) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE consumer = ? AND event_id = ?",
                Integer.class, consumer.name(), UUID.fromString((String) headers.get("event-id")));
    }

    private static Map<String, Object> envelope(String eventType, String product, long seq) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("event-id", UUID.randomUUID().toString());
        headers.put("event-type", eventType);
        headers.put("aggregate-type", "product");
        headers.put("aggregate-id", product);
        headers.put("seq", Long.toString(seq));
        headers.put("schema-version", "1");
        headers.put("producer", "catalog");
        return headers;
    }

    private static String fresh() {
        return UUID.randomUUID().toString();
    }
}
