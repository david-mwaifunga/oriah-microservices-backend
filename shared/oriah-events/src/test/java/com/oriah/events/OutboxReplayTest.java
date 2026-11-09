package com.oriah.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MimeType;

import static com.oriah.events.Relayed.all;
import static com.oriah.events.Relayed.header;
import static com.oriah.events.Relayed.headers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replay: a consumer that joins late (or lost its copy) asks a producer to
 * send its outbox again, from a position, to a topic of the consumer's own.
 *
 * <p>The request goes to {@code oriah.outbox.replay-requests}, which every
 * producer reads; only the producer it names answers. The producer takes
 * the newest outbox_id at that moment (H), sends every row from the asked
 * position through H, in order, to {@code oriah.replay.<consumer>}, then
 * an end-of-replay marker. Rows appended meanwhile are after H: they go out
 * live, as always, and not in the replay. The live topics and the relay's
 * cursor are never touched.
 *
 * <p>Each replayed record carries the live envelope plus
 * {@code replay-request-id} and {@code replay-prev-outbox-id} (the record
 * before it in this replay), so the consumer can tell whether it has every
 * record of the run, with no gap.
 */
@ProbeTest
class OutboxReplayTest {

    private static final String TOPIC = "oriah.probe";
    private static final String OTHER_TOPIC = "oriah.probe-other";
    private static final String REQUESTS = "oriah.outbox.replay-requests";
    private static final Instant WHEN = Instant.parse("2026-11-09T07:30:00Z");

    /** The live envelope: a replayed record carries every one of these exactly as the relay sent it. */
    private static final List<String> LIVE_HEADERS = List.of(
            "event-id", "event-type", "aggregate-type", "aggregate-id", "seq", "outbox-id", "occurred-at",
            "recorded-at", "actor", "schema-version", "producer", MessageHeaders.CONTENT_TYPE);

    @Autowired
    private OutboxReplayer replayer;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private InputDestination input;

    @Autowired
    private OutputDestination output;

    @Autowired
    private StreamOperations streams;

    @Autowired
    private BindingServiceProperties bindings;

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
    @DisplayName("THE PLAN'S TEST: publish, empty the topic, replay to a named consumer from 0 -- every row "
            + "through H on oriah.replay.<consumer>, in order, per-aggregate seq ascending, then the marker")
    void emptyTheTopicThenReplayFromZero() {
        String a = fresh();
        String b = fresh();
        List<AppendedEvent> appended = List.of(
                append(a, TOPIC), append(b, TOPIC), append(a, TOPIC), append(b, OTHER_TOPIC), append(a, TOPIC));
        while (relay.relayBatch() > 0) {
            // publish all of it
        }
        // Read the live topics dry: this is "empty the topic".
        Map<String, Message<byte[]>> live = new HashMap<>();
        Stream.concat(all(output, TOPIC).stream(), all(output, OTHER_TOPIC).stream())
                .forEach(message -> live.put(header(message, "event-id"), message));
        assertThat(live).hasSize(5);
        long cursor = cursor();
        long h = maxOutboxId();
        List<String> everyRow = jdbc.queryForList("SELECT outbox_id FROM outbox ORDER BY outbox_id", Long.class)
                .stream().map(String::valueOf).toList();
        String consumer = freshConsumer();
        UUID requestId = UUID.randomUUID();

        // Through the binding. The test binder hands each continuation the
        // producer sends to the requests topic straight back to this same
        // listener (one in-memory channel per topic), so the whole replay,
        // every step, is answered before this returns.
        input.send(request(requestId, "probe", consumer, 0L), REQUESTS);

        List<Message<byte[]>> stream = all(output, "oriah.replay." + consumer);
        assertThat(stream).hasSize(everyRow.size() + 1);
        List<Message<byte[]>> records = stream.subList(0, stream.size() - 1);
        Message<byte[]> marker = stream.get(stream.size() - 1);

        // Every row of the log through H, in log order.
        assertThat(headers(records, "outbox-id")).containsExactlyElementsOf(everyRow);
        assertThat(headers(records, "replay-request-id")).containsOnly(requestId.toString());
        // Within each aggregate, seq only goes up.
        Map<String, List<Long>> seqs = new LinkedHashMap<>();
        for (Message<byte[]> record : records) {
            seqs.computeIfAbsent(header(record, "aggregate-type") + "/" + header(record, "aggregate-id"),
                    key -> new ArrayList<>()).add(Long.parseLong(header(record, "seq")));
        }
        seqs.values().forEach(list -> assertThat(list).isSorted().doesNotHaveDuplicates());
        assertThat(seqs.get("order/" + a)).containsExactly(1L, 2L, 3L);
        assertThat(seqs.get("order/" + b)).containsExactly(1L, 2L);
        // A gap-free chain: each record names the one before it in this replay.
        assertThat(header(records.get(0), "replay-prev-outbox-id")).isEqualTo("-1");
        for (int i = 1; i < records.size(); i++) {
            assertThat(header(records.get(i), "replay-prev-outbox-id"))
                    .isEqualTo(header(records.get(i - 1), "outbox-id"));
        }
        // Our five rows are the live messages again: same body and envelope.
        for (AppendedEvent event : appended) {
            Message<byte[]> again = records.stream()
                    .filter(record -> event.eventId().toString().equals(header(record, "event-id")))
                    .findFirst().orElseThrow();
            Message<byte[]> original = live.get(event.eventId().toString());
            assertThat(again.getPayload()).isEqualTo(original.getPayload());
            for (String name : LIVE_HEADERS) {
                assertThat(header(again, name)).as(name).isEqualTo(header(original, name));
            }
        }
        // But not the live key: every record of this replay, and its marker,
        // carry the request id as their key. Kafka places a record by its
        // key, so the whole run lands on one partition of the replay topic,
        // in order, however many partitions that topic has. Keyed by
        // aggregate, a topic of several partitions would split the run, the
        // records would arrive out of order and the catch-up could never
        // complete.
        for (Message<byte[]> message : stream) {
            assertThat((byte[]) message.getHeaders().get("kafka_messageKey")).as("the key of " + header(message,
                    "outbox-id")).isEqualTo(requestId.toString().getBytes(StandardCharsets.UTF_8));
        }
        // Then the end-of-replay marker.
        assertThat(header(marker, "event-type")).isEqualTo("outbox.replay.completed");
        assertThat(header(marker, "producer")).isEqualTo("probe");
        assertThat(header(marker, "replay-request-id")).isEqualTo(requestId.toString());
        assertThat(header(marker, "through-outbox-id")).isEqualTo(Long.toString(h));
        assertThat(header(marker, "last-outbox-id")).isEqualTo(Long.toString(h));
        assertThat(MimeType.valueOf(header(marker, MessageHeaders.CONTENT_TYPE)))
                .isEqualTo(MimeType.valueOf("application/json"));
        // The body repeats the headers, for whoever reads the topic by eye
        // (a console consumer shows bodies, not headers).
        @SuppressWarnings("unchecked")
        Map<String, Object> body = EventJson.mapper().readValue(marker.getPayload(), Map.class);
        assertThat(body.keySet()).containsExactlyInAnyOrder("requestId", "producer", "throughOutboxId", "lastOutboxId");
        assertThat(body.get("requestId")).isEqualTo(requestId.toString());
        assertThat(body.get("producer")).isEqualTo("probe");
        assertThat(String.valueOf(body.get("throughOutboxId"))).isEqualTo(Long.toString(h));
        assertThat(String.valueOf(body.get("lastOutboxId"))).isEqualTo(Long.toString(h));
        // The live topics and the relay's cursor are untouched.
        assertThat(all(output, TOPIC)).isEmpty();
        assertThat(all(output, OTHER_TOPIC)).isEmpty();
        assertThat(cursor()).isEqualTo(cursor);
    }

    @Test
    @DisplayName("the request listener reads oriah.outbox.replay-requests in a group named after the service, "
            + "so each producer service answers once however many instances it runs")
    void listenerBinding() {
        assertThat(bindings.getBindingProperties("outboxReplayRequests-in-0").getDestination()).isEqualTo(REQUESTS);
        assertThat(bindings.getBindingProperties("outboxReplayRequests-in-0").getGroup()).isEqualTo("probe");
    }

    @Test
    @DisplayName("from the middle: that row through H; the first record's prev is the position before it")
    void fromTheMiddle() {
        String a = fresh();
        append(a, TOPIC);
        AppendedEvent second = append(a, TOPIC);
        AppendedEvent third = append(a, OTHER_TOPIC);
        String consumer = freshConsumer();

        replayer.handle(request(UUID.randomUUID(), "probe", consumer, second.outboxId()));

        List<Message<byte[]>> stream = all(output, "oriah.replay." + consumer);
        assertThat(headers(stream, "outbox-id")).containsExactly(
                Long.toString(second.outboxId()), Long.toString(third.outboxId()), null);
        assertThat(headers(stream, "replay-prev-outbox-id")).containsExactly(
                Long.toString(second.outboxId() - 1), Long.toString(second.outboxId()), null);
        assertMarker(stream.get(2), third.outboxId(), third.outboxId());
    }

    @Test
    @DisplayName("from the last row: that one row, then the marker")
    void fromTheLast() {
        AppendedEvent last = append(fresh(), TOPIC);
        String consumer = freshConsumer();

        replayer.handle(request(UUID.randomUUID(), "probe", consumer, last.outboxId()));

        List<Message<byte[]>> stream = all(output, "oriah.replay." + consumer);
        assertThat(headers(stream, "outbox-id")).containsExactly(Long.toString(last.outboxId()), null);
        assertThat(header(stream.get(0), "replay-prev-outbox-id")).isEqualTo(Long.toString(last.outboxId() - 1));
        assertMarker(stream.get(1), last.outboxId(), last.outboxId());
    }

    @Test
    @DisplayName("from beyond the end: the marker alone, its last-outbox-id the position before the one asked for")
    void beyondTheEnd() {
        long h = append(fresh(), TOPIC).outboxId();
        String consumer = freshConsumer();

        replayer.handle(request(UUID.randomUUID(), "probe", consumer, h + 5));

        List<Message<byte[]>> stream = all(output, "oriah.replay." + consumer);
        assertThat(stream).hasSize(1);
        assertMarker(stream.get(0), h, h + 4);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedRequests")
    @DisplayName("a malformed request is refused (retried, then dead-lettered): no marker, nothing replayed")
    void refused(String why, String consumer, String body) {
        append(fresh(), TOPIC);
        int markers = requestMarkers();

        assertThatThrownBy(() -> replayer.handle(raw(body)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(why.substring(0, why.indexOf(' ')));

        assertThat(requestMarkers()).isEqualTo(markers);
        assertThat(all(output, "oriah.replay." + consumer)).isEmpty();
    }

    static Stream<Arguments> refusedRequests() {
        String id = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("fromOutboxId negative", "finance",
                        json(id, "probe", "finance", -1L)),
                Arguments.of("fromOutboxId missing", "finance",
                        json(id, "probe", "finance", null)),
                Arguments.of("consumer upper-case", "Finance",
                        json(id, "probe", "Finance", 0L)),
                Arguments.of("consumer with a dot (it would name another topic)", "fin.ance",
                        json(id, "probe", "fin.ance", 0L)),
                Arguments.of("consumer with a space", "fin ance",
                        json(id, "probe", "fin ance", 0L)),
                Arguments.of("consumer blank", "",
                        json(id, "probe", "", 0L)),
                Arguments.of("consumer missing", "null",
                        json(id, "probe", null, 0L)),
                Arguments.of("consumer over 64 characters", "f".repeat(65),
                        json(id, "probe", "f".repeat(65), 0L)),
                Arguments.of("requestId missing", "finance",
                        json(null, "probe", "finance", 0L)),
                Arguments.of("requestId not a UUID", "finance",
                        json("42", "probe", "finance", 0L)),
                Arguments.of("producer missing", "finance",
                        json(id, null, "finance", 0L)),
                Arguments.of("JSON not readable", "finance",
                        "replay everything please"));
    }

    @Test
    @DisplayName("a replay longer than one step is answered in steps: chunk-size rows each, the chain unbroken across "
            + "them, each step but the last ending with a continuation to the requests topic, keyed by the request "
            + "id; the last ends with the marker")
    void answeredInSteps() {
        String a = fresh();
        List<AppendedEvent> rows = List.of(append(a, TOPIC), append(a, TOPIC), append(a, TOPIC), append(a, TOPIC),
                append(a, TOPIC));
        long h = maxOutboxId();
        HeldContinuations held = new HeldContinuations(streams);
        OutboxReplayer stepping = new OutboxReplayer(dataSource, transactionManager, held, "probe", 2);
        String consumer = freshConsumer();
        UUID requestId = UUID.randomUUID();
        long from = rows.get(0).outboxId();

        stepping.handle(request(requestId, "probe", consumer, from));

        List<Message<byte[]>> first = all(output, "oriah.replay." + consumer);
        assertThat(headers(first, "outbox-id")).containsExactly(id(rows, 0), id(rows, 1));
        assertThat(headers(first, "replay-prev-outbox-id")).containsExactly(Long.toString(from - 1), id(rows, 0));
        Message<byte[]> afterFirst = only(held.take());
        assertContinuation(afterFirst, requestId, consumer, from, h, rows.get(1).outboxId());

        stepping.handle(afterFirst);

        List<Message<byte[]>> second = all(output, "oriah.replay." + consumer);
        assertThat(headers(second, "outbox-id")).containsExactly(id(rows, 2), id(rows, 3));
        assertThat(headers(second, "replay-prev-outbox-id")).containsExactly(id(rows, 1), id(rows, 2));
        assertThat(headers(second, "replay-request-id")).containsOnly(requestId.toString());
        Message<byte[]> afterSecond = only(held.take());
        assertContinuation(afterSecond, requestId, consumer, from, h, rows.get(3).outboxId());

        stepping.handle(afterSecond);

        List<Message<byte[]>> last = all(output, "oriah.replay." + consumer);
        assertThat(headers(last, "outbox-id")).containsExactly(id(rows, 4), null);
        assertThat(header(last.get(0), "replay-prev-outbox-id")).isEqualTo(id(rows, 3));
        assertMarker(last.get(1), h, rows.get(4).outboxId());
        assertThat(header(last.get(1), "replay-request-id")).isEqualTo(requestId.toString());
        assertThat(held.take()).as("no continuation after the last step").isEmpty();
    }

    @Test
    @DisplayName("outbox ids with gaps (an append that rolled back used up its id): each record names the row "
            + "actually sent before it, never its own id minus one, so the chain stays unbroken across every gap, "
            + "inside a step and between steps")
    void chainUnbrokenAcrossUsedUpIds() {
        String a = fresh();
        AppendedEvent r1 = append(a, TOPIC);
        useUpAnOutboxId();
        AppendedEvent r2 = append(a, TOPIC);
        AppendedEvent r3 = append(a, TOPIC);
        useUpAnOutboxId();
        AppendedEvent r4 = append(a, TOPIC);
        useUpAnOutboxId();
        AppendedEvent r5 = append(a, TOPIC);
        List<AppendedEvent> rows = List.of(r1, r2, r3, r4, r5);
        assertThat(r2.outboxId()).as("an id used up between r1 and r2").isGreaterThan(r1.outboxId() + 1);
        assertThat(r4.outboxId()).as("an id used up between r3 and r4").isGreaterThan(r3.outboxId() + 1);
        assertThat(r5.outboxId()).as("an id used up between r4 and r5").isGreaterThan(r4.outboxId() + 1);
        HeldContinuations held = new HeldContinuations(streams);
        OutboxReplayer stepping = new OutboxReplayer(dataSource, transactionManager, held, "probe", 2);
        String consumer = freshConsumer();

        stepping.handle(request(UUID.randomUUID(), "probe", consumer, r1.outboxId()));
        answer(held, stepping);

        // Steps of two: [r1 r2], [r3 r4], [r5] -- a gap inside each of the first two steps, and one just before
        // the last step's only row.
        List<Message<byte[]>> stream = all(output, "oriah.replay." + consumer);
        assertThat(headers(stream, "outbox-id")).containsExactly(
                id(rows, 0), id(rows, 1), id(rows, 2), id(rows, 3), id(rows, 4), null);
        assertThat(headers(stream, "replay-prev-outbox-id")).containsExactly(
                Long.toString(r1.outboxId() - 1), id(rows, 0), id(rows, 1), id(rows, 2), id(rows, 3), null);
        assertMarker(stream.get(5), r5.outboxId(), r5.outboxId());
    }

    @Test
    @DisplayName("exactly one step's rows left: the step sends them and then the marker, with no empty step after")
    void exactlyOneStepLeft() {
        String a = fresh();
        List<AppendedEvent> rows = List.of(append(a, TOPIC), append(a, TOPIC), append(a, TOPIC), append(a, TOPIC));
        HeldContinuations held = new HeldContinuations(streams);
        OutboxReplayer stepping = new OutboxReplayer(dataSource, transactionManager, held, "probe", 2);
        String consumer = freshConsumer();

        stepping.handle(request(UUID.randomUUID(), "probe", consumer, rows.get(0).outboxId()));
        assertThat(headers(all(output, "oriah.replay." + consumer), "outbox-id")).containsExactly(id(rows, 0),
                id(rows, 1));
        stepping.handle(only(held.take()));

        List<Message<byte[]>> last = all(output, "oriah.replay." + consumer);
        assertThat(headers(last, "outbox-id")).containsExactly(id(rows, 2), id(rows, 3), null);
        assertMarker(last.get(2), rows.get(3).outboxId(), rows.get(3).outboxId());
        assertThat(held.take()).isEmpty();
    }

    @Test
    @DisplayName("a continuation delivered twice is answered once: the second copy sends nothing, and the step is "
            + "recorded once, under an id of its own")
    void continuationTwiceAnsweredOnce() {
        String a = fresh();
        List<AppendedEvent> rows = List.of(append(a, TOPIC), append(a, TOPIC), append(a, TOPIC));
        HeldContinuations held = new HeldContinuations(streams);
        OutboxReplayer stepping = new OutboxReplayer(dataSource, transactionManager, held, "probe", 2);
        String consumer = freshConsumer();
        UUID requestId = UUID.randomUUID();
        stepping.handle(request(requestId, "probe", consumer, rows.get(0).outboxId()));
        all(output, "oriah.replay." + consumer);
        Message<byte[]> continuation = only(held.take());

        stepping.handle(continuation);
        stepping.handle(continuation);

        assertThat(headers(all(output, "oriah.replay." + consumer), "outbox-id"))
                .as("the second step's row and the marker, once").containsExactly(id(rows, 2), null);
        assertThat(held.take()).isEmpty();
        UUID step = new ReplayRequest(requestId, "probe", consumer, rows.get(0).outboxId())
                .continueAfter(rows.get(2).outboxId(), rows.get(1).outboxId()).stepId();
        assertThat(stepMarkers(step)).isEqualTo(1);
        assertThat(stepMarkers(requestId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a step that sent its records and its continuation but did not commit (a crash) is answered again "
            + "when Kafka redelivers it: the same records and continuation again; the next step, delivered twice, "
            + "once")
    void stepAnsweredAgainAfterNoCommit() {
        String a = fresh();
        List<AppendedEvent> rows = List.of(append(a, TOPIC), append(a, TOPIC), append(a, TOPIC), append(a, TOPIC));
        HeldContinuations held = new HeldContinuations(streams);
        ReplayHooks crashing = new ReplayHooks(held, null, transactionManager, TOPIC, 0,
                rows.get(1).outboxId());
        OutboxReplayer stepping = new OutboxReplayer(dataSource, transactionManager, crashing, "probe", 2);
        String consumer = freshConsumer();
        UUID requestId = UUID.randomUUID();
        Message<byte[]> request = request(requestId, "probe", consumer, rows.get(0).outboxId());

        assertThatThrownBy(() -> stepping.handle(request)).isInstanceOf(IllegalStateException.class);
        assertThat(stepMarkers(requestId)).as("the crashed step is not recorded").isZero();
        stepping.handle(request);
        List<Message<byte[]>> continuations = held.take();
        assertThat(continuations).as("the step's continuation, sent by both attempts").hasSize(2);
        continuations.forEach(stepping::handle);

        assertThat(headers(all(output, "oriah.replay." + consumer), "outbox-id")).containsExactly(
                id(rows, 0), id(rows, 1), id(rows, 0), id(rows, 1), id(rows, 2), id(rows, 3), null);
        assertThat(held.take()).isEmpty();
        assertThat(stepMarkers(requestId)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedContinuations")
    @DisplayName("a continuation whose fields do not fit together is refused (dead-lettered on its first "
            + "delivery): nothing replayed, nothing recorded, no further step")
    void refusedContinuation(String why, String body, String refusal) {
        append(fresh(), TOPIC);
        int markers = requestMarkers();

        assertThatThrownBy(() -> replayer.handle(raw(body)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(refusal);

        assertThat(requestMarkers()).isEqualTo(markers);
        assertThat(all(output, "oriah.replay.finance")).isEmpty();
    }

    static Stream<Arguments> refusedContinuations() {
        String id = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("throughOutboxId without afterOutboxId",
                        json(id, "probe", "finance", 1L, 35L, null), "come together"),
                Arguments.of("afterOutboxId without throughOutboxId",
                        json(id, "probe", "finance", 1L, null, 10L), "come together"),
                Arguments.of("afterOutboxId before the first row asked for",
                        json(id, "probe", "finance", 5L, 35L, 3L), "afterOutboxId is fromOutboxId - 1 or more"),
                Arguments.of("throughOutboxId before afterOutboxId",
                        json(id, "probe", "finance", 1L, 9L, 10L), "throughOutboxId is afterOutboxId or more"));
    }

    @Test
    @DisplayName("a duplicate request (Kafka delivers at least once) replays once")
    void duplicateReplaysOnce() {
        AppendedEvent last = append(fresh(), TOPIC);
        String consumer = freshConsumer();
        Message<byte[]> request = request(UUID.randomUUID(), "probe", consumer, last.outboxId());

        replayer.handle(request);
        replayer.handle(request);

        List<Message<byte[]>> stream = all(output, "oriah.replay." + consumer);
        assertThat(headers(stream, "event-type")).containsExactly("order.created", "outbox.replay.completed");
        assertThat(requestMarker(request)).isEqualTo(1);
    }

    @Test
    @DisplayName("a request for another producer is ignored: nothing sent, no marker, no error")
    void otherProducerIgnored() {
        append(fresh(), TOPIC);
        String consumer = freshConsumer();
        Message<byte[]> request = request(UUID.randomUUID(), "payment", consumer, 0L);

        replayer.handle(request);

        assertThat(all(output, "oriah.replay." + consumer)).isEmpty();
        assertThat(requestMarker(request)).isZero();
    }

    @Test
    @DisplayName("rows appended during a replay are after H: not in it, and published only on the live topic")
    void rowsAppendedDuringReplayGoOnlyLive() {
        String a = fresh();
        AppendedEvent first = append(a, TOPIC);
        AppendedEvent second = append(a, TOPIC);
        HeldContinuations held = new HeldContinuations(streams);
        AppendingDuringReplay appending = new AppendingDuringReplay(held, () -> requiresNew(() ->
                writer.append(draft(a, TOPIC))));
        // One row per step, so a later step reads the log again after the append.
        OutboxReplayer replaying = new OutboxReplayer(dataSource, transactionManager, appending, "probe", 1);
        String consumer = freshConsumer();

        replaying.handle(request(UUID.randomUUID(), "probe", consumer, first.outboxId()));
        answer(held, replaying);

        AppendedEvent during = appending.appended();
        assertThat(during).as("a row was appended mid-replay").isNotNull();
        assertThat(during.outboxId()).isGreaterThan(second.outboxId());
        List<Message<byte[]>> stream = all(output, "oriah.replay." + consumer);
        assertThat(headers(stream, "outbox-id")).containsExactly(
                Long.toString(first.outboxId()), Long.toString(second.outboxId()), null);
        assertMarker(stream.get(2), second.outboxId(), second.outboxId());

        while (relay.relayBatch() > 0) {
            // publish the live backlog
        }
        assertThat(headers(all(output, TOPIC), "event-id")).containsExactly(
                first.eventId().toString(), second.eventId().toString(), during.eventId().toString());
    }

    @ParameterizedTest
    @EnumSource(FlakyStreams.Failure.class)
    @DisplayName("a send that fails stops the replay with no marker and no request marker, "
            + "so the redelivered request replays the whole range again")
    void failedSendReplaysAgain(FlakyStreams.Failure failure) {
        String a = fresh();
        AppendedEvent first = append(a, TOPIC);
        AppendedEvent second = append(a, TOPIC);
        FlakyStreams flaky = new FlakyStreams(streams, second.eventId(), failure);
        OutboxReplayer replaying = new OutboxReplayer(dataSource, transactionManager, flaky, "probe", 100);
        String consumer = freshConsumer();
        Message<byte[]> request = request(UUID.randomUUID(), "probe", consumer, first.outboxId());

        assertThatThrownBy(() -> replaying.handle(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(Long.toString(second.outboxId()));
        assertThat(requestMarker(request)).isZero();
        assertThat(headers(all(output, "oriah.replay." + consumer), "outbox-id"))
                .containsExactly(Long.toString(first.outboxId()));

        flaky.heal();
        replaying.handle(request);

        assertThat(headers(all(output, "oriah.replay." + consumer), "outbox-id")).containsExactly(
                Long.toString(first.outboxId()), Long.toString(second.outboxId()), null);
        assertThat(requestMarker(request)).isEqualTo(1);
    }

    /** Kafka's part in a replay of several steps: each continuation held back is delivered, until none is left. */
    private static void answer(HeldContinuations held, OutboxReplayer replaying) {
        List<Message<byte[]>> continuations;
        while (!(continuations = held.take()).isEmpty()) {
            continuations.forEach(replaying::handle);
        }
    }

    /**
     * The real StreamBridge, except that what a replayer built by the test
     * sends to the requests topic -- its continuations -- is kept here
     * instead, for the test to deliver as Kafka would: when it chooses, as
     * often as it chooses. Passed on, the test binder would hand them to
     * the context's own bound listener at once.
     */
    private static final class HeldContinuations implements StreamOperations {

        private final StreamOperations real;
        private final List<Message<byte[]>> held = new ArrayList<>();

        HeldContinuations(StreamOperations real) {
            this.real = real;
        }

        /** What was held since the last call, oldest first. */
        List<Message<byte[]>> take() {
            List<Message<byte[]>> taken = List.copyOf(held);
            held.clear();
            return taken;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean send(String bindingName, Object data) {
            if (REQUESTS.equals(bindingName)) {
                held.add((Message<byte[]>) data);
                return true;
            }
            return real.send(bindingName, data);
        }

        @Override
        public boolean send(String bindingName, Object data, MimeType outputContentType) {
            return send(bindingName, data);
        }

        @Override
        public boolean send(String bindingName, String binderName, Object data) {
            return send(bindingName, data);
        }

        @Override
        public boolean send(String bindingName, String binderName, Object data, MimeType outputContentType) {
            return send(bindingName, data);
        }
    }

    /** A continuation as the producer sends it to itself: the request plus H and the last row sent, keyed, JSON. */
    private static void assertContinuation(Message<byte[]> continuation, UUID requestId, String consumer, long from,
                                           long through, long after) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = EventJson.mapper().readValue(continuation.getPayload(), Map.class);
        assertThat(body.get("requestId")).isEqualTo(requestId.toString());
        assertThat(body.get("producer")).isEqualTo("probe");
        assertThat(body.get("consumer")).isEqualTo(consumer);
        assertThat(((Number) body.get("fromOutboxId")).longValue()).isEqualTo(from);
        assertThat(((Number) body.get("throughOutboxId")).longValue()).as("H").isEqualTo(through);
        assertThat(((Number) body.get("afterOutboxId")).longValue()).as("the last row sent").isEqualTo(after);
        assertThat((byte[]) continuation.getHeaders().get("kafka_messageKey")).as("the key")
                .isEqualTo(requestId.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(MimeType.valueOf(header(continuation, MessageHeaders.CONTENT_TYPE)))
                .isEqualTo(MimeType.valueOf("application/json"));
    }

    private static String id(List<AppendedEvent> rows, int index) {
        return Long.toString(rows.get(index).outboxId());
    }

    private static Message<byte[]> only(List<Message<byte[]>> messages) {
        assertThat(messages).hasSize(1);
        return messages.get(0);
    }

    private int stepMarkers(UUID step) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = 'replay-requests' AND event_id = ?",
                Integer.class, step);
    }

    private static void assertMarker(Message<byte[]> marker, long through, long last) {
        assertThat(header(marker, "event-type")).isEqualTo("outbox.replay.completed");
        assertThat(header(marker, "through-outbox-id")).isEqualTo(Long.toString(through));
        assertThat(header(marker, "last-outbox-id")).isEqualTo(Long.toString(last));
    }

    /** The real StreamBridge, plus one append in its own committed transaction at the first replay send. */
    private static final class AppendingDuringReplay implements StreamOperations {

        private final StreamOperations real;
        private final Supplier<AppendedEvent> append;
        private AppendedEvent appended;

        AppendingDuringReplay(StreamOperations real, Supplier<AppendedEvent> append) {
            this.real = real;
            this.append = append;
        }

        AppendedEvent appended() {
            return appended;
        }

        @Override
        public boolean send(String bindingName, Object data) {
            if (appended == null && bindingName.startsWith("oriah.replay.")) {
                appended = append.get();
            }
            return real.send(bindingName, data);
        }

        @Override
        public boolean send(String bindingName, Object data, MimeType outputContentType) {
            return send(bindingName, data);
        }

        @Override
        public boolean send(String bindingName, String binderName, Object data) {
            return send(bindingName, data);
        }

        @Override
        public boolean send(String bindingName, String binderName, Object data, MimeType outputContentType) {
            return send(bindingName, data);
        }
    }

    private Message<byte[]> request(UUID requestId, String producer, String consumer, Long fromOutboxId) {
        return raw(json(requestId.toString(), producer, consumer, fromOutboxId));
    }

    private static Message<byte[]> raw(String body) {
        return MessageBuilder.withPayload(body.getBytes(StandardCharsets.UTF_8))
                .setHeader(MessageHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /** The request on the wire, built field by field so a test can leave one out. */
    private static String json(String requestId, String producer, String consumer, Long fromOutboxId) {
        return json(requestId, producer, consumer, fromOutboxId, null, null);
    }

    /** A continuation on the wire: a request plus H and the last row sent, either of which a test can leave out. */
    private static String json(String requestId, String producer, String consumer, Long fromOutboxId,
                               Long throughOutboxId, Long afterOutboxId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        if (producer != null) {
            body.put("producer", producer);
        }
        if (consumer != null) {
            body.put("consumer", consumer);
        }
        if (fromOutboxId != null) {
            body.put("fromOutboxId", fromOutboxId);
        }
        if (throughOutboxId != null) {
            body.put("throughOutboxId", throughOutboxId);
        }
        if (afterOutboxId != null) {
            body.put("afterOutboxId", afterOutboxId);
        }
        return EventJson.write(body);
    }

    private AppendedEvent append(String aggregateId, String topic) {
        return new TransactionTemplate(transactionManager).execute(status -> writer.append(draft(aggregateId, topic)));
    }

    /**
     * An append whose transaction rolls back. Its row never exists, but the
     * outbox_id it drew from the sequence is gone for good (a sequence never
     * hands an id back), so the committed ids have a gap -- as they do in a
     * real service whenever a write fails after its append.
     */
    private void useUpAnOutboxId() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            writer.append(draft(fresh(), TOPIC));
            status.setRollbackOnly();
        });
    }

    private <T> T requiresNew(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }

    private long cursor() {
        return jdbc.queryForObject("SELECT last_outbox_id FROM outbox_relay_cursor WHERE relay = 'main'", Long.class);
    }

    private long maxOutboxId() {
        return jdbc.queryForObject("SELECT max(outbox_id) FROM outbox", Long.class);
    }

    private int requestMarkers() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE consumer = 'replay-requests'",
                Integer.class);
    }

    private int requestMarker(Message<byte[]> request) {
        Map<?, ?> body = EventJson.mapper().readValue(request.getPayload(), Map.class);
        return jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = 'replay-requests' AND event_id = ?",
                Integer.class, UUID.fromString((String) body.get("requestId")));
    }

    private static String freshConsumer() {
        return "probe-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String fresh() {
        return UUID.randomUUID().toString();
    }

    private static EventDraft draft(String aggregateId, String topic) {
        return new EventDraft("order", aggregateId, "order.created", 1, topic, WHEN, "system:probe",
                Map.of("orderId", aggregateId));
    }
}
