package com.oriah.events;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.util.MimeType;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.oriah.events.RunningConsumer.CatchUpRow;
import com.oriah.events.RunningConsumer.Kind;
import com.oriah.events.latecomer.FactProbe;

import static com.oriah.events.Relayed.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * CatchUp, rule by rule: a consumer that starts after its producer has been
 * running asks for a replay of the producer's log, applies each replayed
 * record through the same Inbox method as its live deliveries, and records how
 * far the replay has arrived WITHOUT A GAP (replayed_through). The catch-up is
 * complete only when the producer's end-of-replay marker says the last record
 * it sent is exactly that far.
 *
 * <p>Real services: the producer probe and the fact probe each run as their
 * own application on their own database (one Postgres container). The test
 * plays Kafka: it carries the consumer's request to the producer and each
 * replayed record back, in an order it chooses. Restarting the consumer is
 * closing it and starting a new one on the same database.
 */
class CatchUpTest {

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
    @DisplayName("first start: a catch-up row per producer (a new request id, replayed_through 0) and one "
            + "request each for the whole log, from outbox id 1")
    void firstStartAsksEveryProducerForItsWholeLog() {
        RunningConsumer consumer = consumer(Kind.FACT, FreshDatabase.in(postgres),
                "oriah.events.inbox.catch-up.producers=probe,catalog");

        Map<String, Map<String, Object>> sent = new HashMap<>();
        for (Message<byte[]> request : consumer.requests()) {
            assertThat(MimeType.valueOf(header(request, MessageHeaders.CONTENT_TYPE)))
                    .isEqualTo(MimeType.valueOf("application/json"));
            Map<String, Object> body = body(request);
            sent.put((String) body.get("producer"), body);
        }

        assertThat(sent).containsOnlyKeys("probe", "catalog");
        for (String producer : List.of("probe", "catalog")) {
            Map<String, Object> request = sent.get(producer);
            CatchUpRow row = consumer.catchUp(producer);
            assertThat(request.get("consumer")).isEqualTo("fact-probe");
            // From 1, not 0: outbox ids start at 1, and a request from
            // replayed_through + 1 makes its first record's
            // replay-prev-outbox-id equal replayed_through (0), so the very
            // first record already continues the chain.
            assertThat(((Number) request.get("fromOutboxId")).longValue()).isEqualTo(1L);
            assertThat(request.get("requestId")).isEqualTo(row.requestId().toString());
            assertThat(row.replayedThrough()).isZero();
            assertThat(row.completedThrough()).isNull();
            assertThat(row.completedAt()).isNull();
        }
        assertThat(consumer.catchUp("probe").requestId()).isNotEqualTo(consumer.catchUp("catalog").requestId());
    }

    @Test
    @DisplayName("each replay record of the current request moves replayed_through to its outbox id; "
            + "the marker then completes the catch-up at H")
    void chainAdvancesThenTheMarkerCompletes() {
        RunningProducer producer = producer();
        List<AppendedEvent> sales = List.of(producer.sale(fresh()), producer.sale(fresh()), producer.sale(fresh()));
        RunningConsumer consumer = consumer(Kind.FACT, FreshDatabase.in(postgres));

        List<Message<byte[]>> replay = producer.answer(only(consumer.requests()), consumer.name());
        assertThat(replay).hasSize(4);

        for (int i = 0; i < sales.size(); i++) {
            consumer.replay(replay.get(i));
            assertThat(consumer.catchUp("probe").replayedThrough()).isEqualTo(sales.get(i).outboxId());
            assertThat(consumer.catchUp("probe").completedAt()).isNull();
        }
        consumer.replay(replay.get(3));

        CatchUpRow row = consumer.catchUp("probe");
        assertThat(row.completedThrough()).isEqualTo(sales.get(2).outboxId());
        assertThat(row.completedAt()).isNotNull();
        assertThat(consumer.appliedSales()).containsExactlyInAnyOrderElementsOf(eventIds(sales));
    }

    @Test
    @DisplayName("a producer with an empty log: the marker alone completes the catch-up")
    void emptyLogCompletesOnTheMarkerAlone() {
        RunningProducer producer = producer();
        RunningConsumer consumer = consumer(Kind.FACT, FreshDatabase.in(postgres));

        List<Message<byte[]>> replay = producer.answer(only(consumer.requests()), consumer.name());
        assertThat(replay).hasSize(1);
        consumer.replay(replay.get(0));

        CatchUpRow row = consumer.catchUp("probe");
        assertThat(row.replayedThrough()).isZero();
        assertThat(row.completedThrough()).isZero();
        assertThat(row.completedAt()).isNotNull();
    }

    @Test
    @DisplayName("a live delivery never advances replayed_through, even the very record the chain waits for; "
            + "its replayed copy then advances it without applying it again")
    void liveDeliveryNeverAdvances() {
        RunningProducer producer = producer();
        AppendedEvent first = producer.sale(fresh());
        AppendedEvent second = producer.sale(fresh());
        List<Message<byte[]>> live = producer.publish();
        RunningConsumer consumer = consumer(Kind.FACT, FreshDatabase.in(postgres));
        Message<byte[]> request = only(consumer.requests());

        consumer.live(live);

        assertThat(consumer.appliedSales()).containsExactlyInAnyOrder(first.eventId(), second.eventId());
        assertThat(consumer.catchUp("probe").replayedThrough()).isZero();

        List<Message<byte[]>> replay = producer.answer(request, consumer.name());
        consumer.replay(replay.get(0));

        assertThat(consumer.catchUp("probe").replayedThrough()).isEqualTo(first.outboxId());
        assertThat(consumer.appliedSales()).containsExactlyInAnyOrder(first.eventId(), second.eventId());
    }

    @Test
    @DisplayName("a replay record that fails stops the chain there: later records still apply but cannot "
            + "advance it, the marker leaves the catch-up open, and the fixed consumer's next start completes it")
    void failingRecordBlocksCompletion() {
        RunningProducer producer = producer();
        String poison = fresh();
        AppendedEvent first = producer.sale(fresh());
        AppendedEvent second = producer.sale(poison);
        AppendedEvent third = producer.sale(fresh());
        FreshDatabase database = FreshDatabase.in(postgres);
        RunningConsumer consumer = consumer(Kind.FACT, database, "probe.poison=" + poison);
        Message<byte[]> request = only(consumer.requests());
        List<Message<byte[]>> replay = producer.answer(request, consumer.name());

        consumer.replay(replay.get(0));
        consumer.replay(replay.get(1));

        assertThat(consumer.markers(second.eventId())).as("the failed record has no marker").isZero();
        assertThat(consumer.catchUp("probe").replayedThrough()).isEqualTo(first.outboxId());

        consumer.replay(replay.get(2));
        consumer.replay(replay.get(3));

        assertThat(consumer.appliedSales()).containsExactlyInAnyOrder(first.eventId(), third.eventId());
        CatchUpRow open = consumer.catchUp("probe");
        assertThat(open.replayedThrough()).isEqualTo(first.outboxId());
        assertThat(open.completedThrough()).isNull();
        assertThat(open.completedAt()).isNull();

        // The consumer is fixed and restarted: it asks again from the gap.
        consumer.close();
        RunningConsumer fixed = consumer(Kind.FACT, database);
        Message<byte[]> again = only(fixed.requests());
        assertThat(((Number) body(again).get("fromOutboxId")).longValue()).isEqualTo(first.outboxId() + 1);
        assertThat(body(again).get("requestId")).isNotEqualTo(body(request).get("requestId"));

        fixed.replay(producer.answer(again, fixed.name()));

        CatchUpRow done = fixed.catchUp("probe");
        assertThat(done.replayedThrough()).isEqualTo(third.outboxId());
        assertThat(done.completedThrough()).isEqualTo(third.outboxId());
        assertThat(done.completedAt()).isNotNull();
        assertThat(fixed.appliedSales())
                .containsExactlyInAnyOrder(first.eventId(), second.eventId(), third.eventId());
    }

    @Test
    @DisplayName("a restart mid-replay sends a NEW request from replayed_through + 1; the old request's "
            + "leftovers still apply once but never advance it, nor does its marker complete it")
    void restartMidReplayResumes() {
        RunningProducer producer = producer();
        List<AppendedEvent> sales = List.of(
                producer.sale(fresh()), producer.sale(fresh()), producer.sale(fresh()), producer.sale(fresh()));
        FreshDatabase database = FreshDatabase.in(postgres);
        RunningConsumer consumer = consumer(Kind.FACT, database);
        Message<byte[]> first = only(consumer.requests());
        List<Message<byte[]>> oldReplay = producer.answer(first, consumer.name());

        consumer.replay(oldReplay.subList(0, 2));
        assertThat(consumer.catchUp("probe").replayedThrough()).isEqualTo(sales.get(1).outboxId());
        consumer.close();

        RunningConsumer restarted = consumer(Kind.FACT, database);
        Message<byte[]> second = only(restarted.requests());
        assertThat(((Number) body(second).get("fromOutboxId")).longValue()).isEqualTo(sales.get(1).outboxId() + 1);
        assertThat(body(second).get("requestId")).isNotEqualTo(body(first).get("requestId"));
        assertThat(restarted.catchUp("probe").requestId().toString()).isEqualTo(body(second).get("requestId"));

        // Kafka still holds the rest of the old replay; the restarted consumer reads it first.
        restarted.replay(oldReplay.subList(2, oldReplay.size()));

        assertThat(restarted.appliedSales()).containsExactlyInAnyOrderElementsOf(eventIds(sales));
        CatchUpRow stillOpen = restarted.catchUp("probe");
        assertThat(stillOpen.replayedThrough()).isEqualTo(sales.get(1).outboxId());
        assertThat(stillOpen.completedAt()).isNull();

        List<Message<byte[]>> newReplay = producer.answer(second, restarted.name());
        assertThat(Relayed.headers(newReplay, "outbox-id")).containsExactly(
                Long.toString(sales.get(2).outboxId()), Long.toString(sales.get(3).outboxId()), null);
        restarted.replay(newReplay);

        CatchUpRow done = restarted.catchUp("probe");
        assertThat(done.replayedThrough()).isEqualTo(sales.get(3).outboxId());
        assertThat(done.completedThrough()).isEqualTo(sales.get(3).outboxId());
        assertThat(restarted.appliedSales()).containsExactlyInAnyOrderElementsOf(eventIds(sales));
    }

    @Test
    @DisplayName("a completed catch-up sends no request on the next start, and its row is left as it was")
    void completedCatchUpSendsNothing() {
        RunningProducer producer = producer();
        producer.sale(fresh());
        FreshDatabase database = FreshDatabase.in(postgres);
        RunningConsumer consumer = consumer(Kind.FACT, database);
        consumer.replay(producer.answer(only(consumer.requests()), consumer.name()));
        CatchUpRow completed = consumer.catchUp("probe");
        assertThat(completed.completedAt()).isNotNull();
        consumer.close();

        RunningConsumer restarted = consumer(Kind.FACT, database);

        assertThat(restarted.requests()).isEmpty();
        assertThat(restarted.catchUp("probe")).isEqualTo(completed);
    }

    @ParameterizedTest(name = "the binder {0}")
    @EnumSource(value = WatchedRequests.Refusal.class, names = {"ANSWER_FALSE", "THROW"})
    @DisplayName("a replay request the binder refuses fails the start, rather than run believing it asked; "
            + "nothing moves, and the next start asks again from the same place with a new request id")
    void refusedRequestFailsTheStart(WatchedRequests.Refusal refusal) {
        RunningProducer producer = producer();
        AppendedEvent first = producer.sale(fresh());
        producer.sale(fresh());
        FreshDatabase database = FreshDatabase.in(postgres);
        RunningConsumer consumer = consumer(Kind.FACT, database);
        Message<byte[]> original = only(consumer.requests());
        consumer.replay(producer.answer(original, consumer.name()).get(0));
        consumer.close();

        Throwable start = catchThrowable(() -> RunningConsumer.start(List.of(WatchedRequests.Config.class),
                Kind.FACT, database, "watch.refuse=" + refusal));

        CatchUpRow refused = RunningConsumer.catchUp(database, "probe");
        assertThat(start).as("the start").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the next start asks again")
                .hasMessageContaining(refused.requestId().toString());
        assertThat(refused.requestId().toString()).isNotEqualTo(body(original).get("requestId"));
        assertThat(refused.replayedThrough()).isEqualTo(first.outboxId());
        assertThat(refused.completedAt()).isNull();

        RunningConsumer restarted = consumer(Kind.FACT, database);
        Message<byte[]> again = only(restarted.requests());
        assertThat(((Number) body(again).get("fromOutboxId")).longValue()).isEqualTo(first.outboxId() + 1);
        assertThat(body(again).get("requestId")).isNotEqualTo(refused.requestId().toString());
        assertThat(restarted.catchUp("probe").requestId().toString()).isEqualTo(body(again).get("requestId"));
    }

    @Test
    @DisplayName("the request is sent only after its row commits, so no replayed record can arrive before the "
            + "row names its request: as each request goes out, the committed row already names it")
    void requestSentOnlyAfterItsRowCommits() {
        FreshDatabase database = FreshDatabase.in(postgres);

        // First start: the row is new.
        RunningConsumer first = consumer(List.of(WatchedRequests.Config.class), Kind.FACT, database);
        WatchedRequests.Seen asked = only(first.bean(WatchedRequests.class).seen());
        assertThat(asked.committed()).as("the request id the committed row named as the request went out")
                .isEqualTo(asked.sent());
        first.close();

        // A restart: the row is there and is given a new request id.
        RunningConsumer restarted = consumer(List.of(WatchedRequests.Config.class), Kind.FACT, database);
        WatchedRequests.Seen askedAgain = only(restarted.bean(WatchedRequests.class).seen());
        assertThat(askedAgain.sent()).isNotEqualTo(asked.sent());
        assertThat(askedAgain.committed()).as("the request id the committed row named as the request went out")
                .isEqualTo(askedAgain.sent());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("damagedReplayHeaders")
    @DisplayName("a replay record or marker with a missing or garbled replay header is refused before any "
            + "marker, nothing is applied and nothing moves")
    void malformedReplayHeadersRefused(String damaged, boolean marker, Consumer<Map<String, Object>> damage) {
        RunningConsumer consumer = consumer(Kind.FACT, FreshDatabase.in(postgres));
        CatchUpRow before = consumer.catchUp("probe");
        UUID eventId = UUID.randomUUID();
        Map<String, Object> headers = marker ? marker(before.requestId()) : record(eventId, before.requestId());
        damage.accept(headers);
        AtomicInteger effects = new AtomicInteger();

        assertThatThrownBy(() -> consumer.transaction().executeWithoutResult(status ->
                consumer.catchUpHelper().once(FactProbe.SALES, headers, effects::incrementAndGet)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(damaged.substring(0, damaged.indexOf(' ')));

        assertThat(effects).hasValue(0);
        assertThat(consumer.markers(eventId)).isZero();
        assertThat(consumer.catchUp("probe")).isEqualTo(before);
    }

    static Stream<Arguments> damagedReplayHeaders() {
        return Stream.of(
                damaged("replay-request-id missing", false, h -> h.remove("replay-request-id")),
                damaged("replay-request-id not a UUID", false, h -> h.put("replay-request-id", "42")),
                damaged("replay-prev-outbox-id missing", false, h -> h.remove("replay-prev-outbox-id")),
                damaged("replay-prev-outbox-id not a number", false, h -> h.put("replay-prev-outbox-id", "none")),
                damaged("outbox-id missing", false, h -> h.remove("outbox-id")),
                damaged("outbox-id zero", false, h -> h.put("outbox-id", "0")),
                damaged("producer missing", false, h -> h.remove("producer")),
                damaged("producer blank", false, h -> h.put("producer", " ")),
                damaged("replay-request-id missing from the marker", true, h -> h.remove("replay-request-id")),
                damaged("last-outbox-id missing from the marker", true, h -> h.remove("last-outbox-id")),
                damaged("last-outbox-id not a number on the marker", true, h -> h.put("last-outbox-id", "x")),
                damaged("through-outbox-id missing from the marker", true, h -> h.remove("through-outbox-id")),
                damaged("producer missing from the marker", true, h -> h.remove("producer")));
    }

    private static Arguments damaged(String why, boolean marker, Consumer<Map<String, Object>> damage) {
        return Arguments.of(why, marker, damage);
    }

    @Test
    @DisplayName("outside a transaction it is refused: the record, its marker and replayed_through must commit together")
    void outsideTransactionRefused() {
        RunningConsumer consumer = consumer(Kind.FACT, FreshDatabase.in(postgres));
        CatchUpRow before = consumer.catchUp("probe");
        UUID eventId = UUID.randomUUID();
        AtomicInteger effects = new AtomicInteger();

        assertThatThrownBy(() -> consumer.catchUpHelper()
                .once(FactProbe.SALES, record(eventId, before.requestId()), effects::incrementAndGet))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(effects).hasValue(0);
        assertThat(consumer.markers(eventId)).isZero();
        assertThat(consumer.catchUp("probe")).isEqualTo(before);
    }

    @Test
    @DisplayName("an end-of-replay marker outside a transaction is refused too: it never reaches the Inbox, "
            + "so only CatchUp's own guard stops it completing the catch-up on its own")
    void markerOutsideTransactionRefused() {
        RunningConsumer consumer = consumer(Kind.FACT, FreshDatabase.in(postgres));
        CatchUpRow before = consumer.catchUp("probe");

        assertThatThrownBy(() -> consumer.catchUpHelper()
                .once(FactProbe.SALES, marker(before.requestId()), () -> { }))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(consumer.catchUp("probe")).isEqualTo(before);
    }

    @Test
    @DisplayName("only the configured consumer may use it: outbox_catch_up keeps one row per producer, "
            + "so a second consumer would move the first one's position")
    void otherConsumerRefused() {
        RunningConsumer consumer = consumer(Kind.FACT, FreshDatabase.in(postgres));
        CatchUpRow before = consumer.catchUp("probe");
        UUID eventId = UUID.randomUUID();
        InboxConsumer someoneElse = InboxConsumer.named("someone-else").handles("sale.recorded", 1);

        assertThatThrownBy(() -> consumer.transaction().executeWithoutResult(status ->
                consumer.catchUpHelper().once(someoneElse, record(eventId, before.requestId()), () -> { })))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fact-probe");

        assertThat(consumer.catchUp("probe")).isEqualTo(before);
    }

    /** A well-formed replay record: the next one the chain waits for (prev 0, outbox id 1). */
    private static Map<String, Object> record(UUID eventId, UUID requestId) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("event-id", eventId.toString());
        headers.put("event-type", "sale.recorded");
        headers.put("aggregate-type", "sale");
        headers.put("aggregate-id", fresh());
        headers.put("seq", "1");
        headers.put("schema-version", "1");
        headers.put("producer", "probe");
        headers.put("outbox-id", "1");
        headers.put("replay-request-id", requestId.toString());
        headers.put("replay-prev-outbox-id", "0");
        return headers;
    }

    /** A well-formed end-of-replay marker that would complete an empty catch-up. */
    private static Map<String, Object> marker(UUID requestId) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("event-type", "outbox.replay.completed");
        headers.put("producer", "probe");
        headers.put("replay-request-id", requestId.toString());
        headers.put("through-outbox-id", "0");
        headers.put("last-outbox-id", "0");
        return headers;
    }

    private RunningProducer producer() {
        RunningProducer producer = RunningProducer.start(FreshDatabase.in(postgres));
        running.push(producer);
        return producer;
    }

    private RunningConsumer consumer(Kind kind, FreshDatabase database, String... extra) {
        return consumer(List.of(), kind, database, extra);
    }

    private RunningConsumer consumer(List<Class<?>> sources, Kind kind, FreshDatabase database, String... extra) {
        RunningConsumer consumer = RunningConsumer.start(sources, kind, database, extra);
        running.push(consumer);
        return consumer;
    }

    private static <T> T only(List<T> sent) {
        assertThat(sent).hasSize(1);
        return sent.get(0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(Message<byte[]> request) {
        return EventJson.mapper().readValue(request.getPayload(), Map.class);
    }

    private static List<UUID> eventIds(List<AppendedEvent> events) {
        return events.stream().map(AppendedEvent::eventId).toList();
    }

    private static String fresh() {
        return UUID.randomUUID().toString();
    }
}
