package com.oriah.events;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MimeType;

import static com.oriah.events.Relayed.all;
import static com.oriah.events.Relayed.header;
import static com.oriah.events.Relayed.headers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The relay publishes the outbox to Kafka (here, the test binder) in the
 * order it was written, never skipping a row and never publishing one
 * twice on purpose. When something goes wrong it stops and tries the same
 * row again next round: a duplicate is possible (a consumer dedupes it by
 * event-id), a gap is not.
 */
@ProbeTest
class OutboxRelayTest {

    private static final String TOPIC = "oriah.probe";
    private static final Instant WHEN = Instant.parse("2026-11-09T07:30:00.123456Z");

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutputDestination output;

    @Autowired
    private StreamOperations streams;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void startFromAnEmptyBacklog() {
        Relayed.drain(relay, output);
    }

    @Test
    @DisplayName("publishes in outbox order across aggregates, and moves the cursor to the last row sent")
    void publishesInOrder() {
        String a = fresh();
        String b = fresh();
        String c = fresh();
        List<AppendedEvent> appended = List.of(
                append(a, TOPIC), append(b, TOPIC), append(a, TOPIC), append(c, TOPIC), append(b, TOPIC));

        assertThat(relay.relayBatch()).isEqualTo(5);

        List<Message<byte[]>> sent = all(output, TOPIC);
        assertThat(headers(sent, "event-id"))
                .containsExactlyElementsOf(appended.stream().map(e -> e.eventId().toString()).toList());
        assertThat(headers(sent, "aggregate-id")).containsExactly(a, b, a, c, b);
        assertThat(headers(sent, "seq")).containsExactly("1", "1", "2", "1", "2");
        assertThat(cursor()).isEqualTo(appended.get(4).outboxId());
    }

    @Test
    @DisplayName("each message goes to its row's own topic")
    void eachToItsTopic() {
        String a = fresh();
        AppendedEvent first = append(a, TOPIC);
        AppendedEvent second = append(a, "oriah.probe-other");

        relay.relayBatch();

        assertThat(headers(all(output, TOPIC), "event-id")).containsExactly(first.eventId().toString());
        assertThat(headers(all(output, "oriah.probe-other"), "event-id")).containsExactly(second.eventId().toString());
    }

    @Test
    @DisplayName("a message carries the stored payload byte for byte, every envelope header, the Kafka key and the content type")
    void messageShape() {
        String order = fresh();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order);
        payload.put("previousStatus", null);
        payload.put("total", new BigDecimal("150.00"));
        payload.put("currency", "ZMW");
        AppendedEvent appended = inTransaction(() -> writer.append(new EventDraft(
                "order", order, "order.created", 1, TOPIC, WHEN, "user:5b1c-subject", payload)));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT payload::text AS payload, recorded_at FROM outbox WHERE outbox_id = ?", appended.outboxId());

        relay.relayBatch();

        List<Message<byte[]>> sent = all(output, TOPIC);
        assertThat(sent).hasSize(1);
        Message<byte[]> message = sent.get(0);
        assertThat(new String(message.getPayload(), StandardCharsets.UTF_8))
                .isEqualTo((String) row.get("payload"))
                .isEqualTo(EventJson.write(payload));
        assertThat(header(message, "event-id")).isEqualTo(appended.eventId().toString());
        assertThat(header(message, "event-type")).isEqualTo("order.created");
        assertThat(header(message, "aggregate-type")).isEqualTo("order");
        assertThat(header(message, "aggregate-id")).isEqualTo(order);
        assertThat(header(message, "seq")).isEqualTo("1");
        assertThat(header(message, "outbox-id")).isEqualTo(Long.toString(appended.outboxId()));
        assertThat(header(message, "occurred-at")).isEqualTo("2026-11-09T07:30:00.123456Z");
        assertThat(header(message, "recorded-at"))
                .isEqualTo(((java.sql.Timestamp) row.get("recorded_at")).toInstant().toString());
        assertThat(header(message, "actor")).isEqualTo("user:5b1c-subject");
        assertThat(header(message, "schema-version")).isEqualTo("1");
        assertThat(header(message, "producer")).isEqualTo("probe");
        // Kafka partitions by this key, so every event of one order lands on
        // one partition and stays in order there. It must be bytes: the
        // Kafka binder's key serializer takes nothing else, and a String key
        // fails every send (OutboxRelayKafkaTest proves that on a real
        // broker; the test binder here would carry any type).
        assertThat(message.getHeaders().get("kafka_messageKey"))
                .isInstanceOf(byte[].class)
                .isEqualTo(order.getBytes(StandardCharsets.UTF_8));
        assertThat(MimeType.valueOf(header(message, MessageHeaders.CONTENT_TYPE)))
                .isEqualTo(MimeType.valueOf("application/json"));
    }

    @Test
    @DisplayName("an append rolled back with its transaction is never published; the next committed one is")
    void rolledBackAppendNeverPublished() {
        String order = fresh();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            writer.append(draft(order, TOPIC));
            status.setRollbackOnly();
        });

        assertThat(relay.relayBatch()).isZero();
        assertThat(all(output, TOPIC)).isEmpty();

        AppendedEvent committed = append(order, TOPIC);
        assertThat(relay.relayBatch()).isEqualTo(1);
        assertThat(headers(all(output, TOPIC), "event-id")).containsExactly(committed.eventId().toString());
    }

    @Test
    @DisplayName("a send that answers false stops the round there; the row is retried next round and nothing is skipped")
    void sendFalseStopsAndRetries() {
        assertFailedSendIsRetried(FlakyStreams.Failure.ANSWER_FALSE);
    }

    @Test
    @DisplayName("a send that throws stops the round there; the row is retried next round and nothing is skipped")
    void sendThrowStopsAndRetries() {
        assertFailedSendIsRetried(FlakyStreams.Failure.THROW);
    }

    private void assertFailedSendIsRetried(FlakyStreams.Failure failure) {
        String a = fresh();
        AppendedEvent first = append(a, TOPIC);
        AppendedEvent second = append(a, TOPIC);
        AppendedEvent third = append(a, TOPIC);
        FlakyStreams flaky = new FlakyStreams(streams, second.eventId(), failure);
        OutboxRelay flakyRelay = new OutboxRelay(dataSource, transactionManager, flaky, "probe", 100);

        assertThat(flakyRelay.relayBatch()).isEqualTo(1);
        assertThat(cursor()).isEqualTo(first.outboxId());
        assertThat(headers(all(output, TOPIC), "event-id")).containsExactly(first.eventId().toString());

        flaky.heal();
        assertThat(flakyRelay.relayBatch()).isEqualTo(2);
        assertThat(cursor()).isEqualTo(third.outboxId());
        assertThat(headers(all(output, TOPIC), "event-id"))
                .containsExactly(second.eventId().toString(), third.eventId().toString());
    }

    @Test
    @DisplayName("ack then crash: the send succeeded but the cursor never committed, so the row is published again "
            + "with the same event-id, and a consumer that dedupes on it applies it once")
    void ackThenCrashRepublishes() throws Exception {
        String order = fresh();
        AppendedEvent appended = append(order, TOPIC);
        long before = cursor();

        try (CommitTrap trap = CommitTrap.armOnCursorUpdate(dataSource)) {
            assertThatThrownBy(() -> relay.relayBatch())
                    .rootCause().hasMessageContaining("simulated crash at commit");
        }
        assertThat(cursor()).as("the cursor did not move: its commit failed").isEqualTo(before);
        assertThat(relay.relayBatch()).isEqualTo(1);

        List<Message<byte[]>> deliveries = all(output, TOPIC);
        assertThat(headers(deliveries, "event-id"))
                .containsExactly(appended.eventId().toString(), appended.eventId().toString());
        assertThat(deliveries.get(1).getPayload()).isEqualTo(deliveries.get(0).getPayload());

        // A probe consumer that records each event-id it has applied (the
        // Inbox does this with processed_event) applies the event once.
        Set<String> applied = new HashSet<>();
        List<String> effects = new ArrayList<>();
        for (Message<byte[]> delivery : deliveries) {
            if (applied.add(header(delivery, "event-id"))) {
                effects.add(header(delivery, "aggregate-id"));
            }
        }
        assertThat(effects).containsExactly(order);
    }

    @Test
    @DisplayName("while one relay holds the cursor, another skips its round at once instead of waiting")
    void secondRelaySkipsWhileCursorHeld() throws Exception {
        AppendedEvent appended = append(fresh(), TOPIC);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("SELECT last_outbox_id FROM outbox_relay_cursor WHERE relay = 'main' FOR UPDATE");
            }
            int sent = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> relay.relayBatch());
            assertThat(sent).isZero();
            assertThat(all(output, TOPIC)).isEmpty();
            holder.rollback();
        }

        assertThat(relay.relayBatch()).isEqualTo(1);
        assertThat(headers(all(output, TOPIC), "event-id")).containsExactly(appended.eventId().toString());
    }

    @Test
    @DisplayName("two relays running at once, while the writer keeps appending: every row once, in order")
    void twoRelaysAtOnce() throws Exception {
        List<String> aggregates = List.of(fresh(), fresh(), fresh(), fresh());
        List<AppendedEvent> appended = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            appended.add(append(aggregates.get(i % 4), TOPIC));
        }
        OutboxRelay one = new OutboxRelay(dataSource, transactionManager, streams, "probe", 3);
        OutboxRelay two = new OutboxRelay(dataSource, transactionManager, streams, "probe", 3);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<List<AppendedEvent>> late = pool.submit(() -> {
                List<AppendedEvent> more = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    more.add(append(aggregates.get(i % 4), TOPIC));
                }
                return more;
            });
            AtomicReference<Long> target = new AtomicReference<>(Long.MAX_VALUE);
            Callable<Void> loop1 = relayUntil(one, target);
            Callable<Void> loop2 = relayUntil(two, target);
            Future<Void> r1 = pool.submit(loop1);
            Future<Void> r2 = pool.submit(loop2);

            appended.addAll(late.get(30, TimeUnit.SECONDS));
            target.set(appended.get(appended.size() - 1).outboxId());
            r1.get(30, TimeUnit.SECONDS);
            r2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(headers(all(output, TOPIC), "outbox-id"))
                .containsExactlyElementsOf(appended.stream()
                        .map(AppendedEvent::outboxId).sorted().map(String::valueOf).toList());
    }

    private Callable<Void> relayUntil(OutboxRelay instance, AtomicReference<Long> target) {
        return () -> {
            while (cursor() < target.get()) {
                instance.relayBatch();
            }
            return null;
        };
    }

    private AppendedEvent append(String aggregateId, String topic) {
        return inTransaction(() -> writer.append(draft(aggregateId, topic)));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private long cursor() {
        return jdbc.queryForObject("SELECT last_outbox_id FROM outbox_relay_cursor WHERE relay = 'main'", Long.class);
    }

    private static String fresh() {
        return UUID.randomUUID().toString();
    }

    private static EventDraft draft(String aggregateId, String topic) {
        return new EventDraft("order", aggregateId, "order.created", 1, topic, WHEN, "system:probe",
                Map.of("orderId", aggregateId));
    }
}
