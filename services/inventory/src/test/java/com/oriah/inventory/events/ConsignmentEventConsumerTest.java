package com.oriah.inventory.events;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.events.EventHeaders;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.service.StockTestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Procurement's receipts become stock, through the bindings exactly as the real relay delivers them: the live topic,
 * and the replay topic procurement answers inventory's catch-up on. Every receipt becomes lots exactly once, whichever
 * stream brings it and however many times Kafka delivers it, and a receipt that breaks a promise is refused with
 * nothing written.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ConsignmentEventConsumerTest {

    private static final AtomicLong OUTBOX = new AtomicLong(100);
    private static final String RECEIVED_AT = "2026-11-12T10:00:00.123456Z";

    @Autowired
    private InputDestination input;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("consignmentEvents")
    private Consumer<Message<byte[]>> live;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        // processed_event is append-only (even TRUNCATE is refused), so tests use their own ids and count against a baseline.
        StockTestData.wipe(jdbc);
    }

    private int processed() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class);
    }

    /** The ring line as procurement's outbox stores it: field order, scale and all. */
    private static String payload(UUID line, UUID variant, boolean withPosition) {
        return """
                {"consignmentId":"%s","consignmentReference":"C-000045","consignmentLineId":"%s","variantId":"%s","sku":"RING-1",
                 "productTitle":"Aurelia Ring","variantTitle":"Aurelia Ring - RING-1","kind":"SELLABLE",
                 "supplierId":"%s","supplierName":"Dubai Gold","receivedAt":"%s","isEstimated":false,"currency":"ZMW","quantity":7,
                 "goodsTotal":10500.00,"sharedCost":202.90,"landedTotal":10702.90,
                 "slices":[{"quantity":4,"unitCost":1528.99},{"quantity":3,"unitCost":1528.98}]%s}"""
                .formatted(UUID.randomUUID(), line, variant, UUID.randomUUID(), RECEIVED_AT, withPosition ? ",\"linePosition\":1" : "");
    }

    /** A message as the relay sends it: the payload as stored, keyed by the line id, with every envelope header. */
    private static MessageBuilder<byte[]> relayed(UUID eventId, String type, UUID line, long outboxId, String occurredAt, String payload) {
        return MessageBuilder.withPayload(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.EVENT_ID, eventId.toString())
                .setHeader(EventHeaders.EVENT_TYPE, type)
                .setHeader(EventHeaders.AGGREGATE_TYPE, "consignment_line")
                .setHeader(EventHeaders.AGGREGATE_ID, line.toString())
                .setHeader(EventHeaders.SEQ, "1")
                .setHeader(EventHeaders.OUTBOX_ID, Long.toString(outboxId))
                .setHeader(EventHeaders.OCCURRED_AT, occurredAt)
                .setHeader(EventHeaders.RECORDED_AT, "2026-11-12T10:00:00.500Z")
                .setHeader(EventHeaders.ACTOR, "user:kc-maya")
                .setHeader(EventHeaders.SCHEMA_VERSION, "1")
                .setHeader(EventHeaders.PRODUCER, "procurement")
                .setHeader(EventHeaders.KAFKA_KEY, line.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Message<byte[]> receipt(UUID eventId, UUID line, UUID variant) {
        return relayed(eventId, "consignment.received", line, OUTBOX.incrementAndGet(), RECEIVED_AT, payload(line, variant, true)).build();
    }

    /** The same message, as a replay delivers it. */
    private Message<byte[]> replayOf(Message<byte[]> live) {
        UUID request = jdbc.queryForObject("SELECT request_id FROM outbox_catch_up WHERE producer = 'procurement'", UUID.class);
        long through = jdbc.queryForObject("SELECT replayed_through FROM outbox_catch_up WHERE producer = 'procurement'", Long.class);
        return MessageBuilder.fromMessage(live)
                .setHeader(EventHeaders.REPLAY_REQUEST_ID, request.toString())
                .setHeader(EventHeaders.REPLAY_PREV_OUTBOX_ID, Long.toString(through))
                .build();
    }

    private int lots(UUID variant) {
        return jdbc.queryForObject("SELECT count(*) FROM cost_lot WHERE variant_id = ?", Integer.class, variant);
    }

    private int onHand(UUID variant) {
        return jdbc.queryForObject("SELECT coalesce(max(on_hand), 0) FROM inventory WHERE variant_id = ?", Integer.class, variant);
    }

    @Test
    @DisplayName("inventory asks procurement for its history at start: the catch-up is recorded, waiting for the answer")
    void theCatchUpWasRequested() {
        // One row for the one producer inventory catches up from. (How far it has got moves as other tests replay.)
        assertThat(jdbc.queryForList("SELECT producer, request_id FROM outbox_catch_up")).hasSize(1)
                .allSatisfy(row -> assertThat(row).containsEntry("producer", "procurement").doesNotContainEntry("request_id", null));
    }

    @Test
    @DisplayName("a receipt on the live topic becomes lots: 4 x K1,528.99 and 3 x K1,528.98, seven on hand")
    void aLiveReceiptBecomesLots() {
        UUID line = UUID.randomUUID();
        UUID variant = UUID.randomUUID();

        input.send(receipt(UUID.randomUUID(), line, variant), "oriah.procurement");

        assertThat(lots(variant)).isEqualTo(2);
        assertThat(onHand(variant)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT sum(qty_received * unit_cost) FROM cost_lot WHERE variant_id = ?",
                java.math.BigDecimal.class, variant)).isEqualByComparingTo("10702.90");
        assertThat(jdbc.queryForObject("SELECT actor FROM stock_action WHERE variant_id = ?", String.class, variant)).isEqualTo("user:kc-maya");
        assertThat(jdbc.queryForObject("SELECT fifo_tiebreak FROM cost_lot WHERE variant_id = ? AND slice_position = 1", Long.class, variant))
                .as("keyed by the line's position in its consignment").isEqualTo(1L);
    }

    @Test
    @DisplayName("Kafka delivering the same receipt again changes nothing: it cannot double the stock and its cost")
    void aRedeliveryIsApplyOnce() {
        UUID line = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> message = receipt(eventId, line, variant);

        input.send(message, "oriah.procurement");
        input.send(message, "oriah.procurement");

        assertThat(lots(variant)).isEqualTo(2);
        assertThat(onHand(variant)).isEqualTo(7);
    }

    @Test
    @DisplayName("a receipt from before inventory existed arrives on the replay topic and becomes lots; delivered live too, it is not doubled")
    void aReplayedReceiptBecomesLotsExactlyOnce() {
        UUID line = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        Message<byte[]> message = receipt(UUID.randomUUID(), line, variant);

        input.send(replayOf(message), "oriah.replay.inventory");
        assertThat(onHand(variant)).as("the replay alone made the stock").isEqualTo(7);

        input.send(message, "oriah.procurement");
        input.send(replayOf(message), "oriah.replay.inventory");

        assertThat(lots(variant)).isEqualTo(2);
        assertThat(onHand(variant)).isEqualTo(7);
    }

    @Test
    @DisplayName("a receipt whose slices do not add up to its landed total is refused, and nothing is written")
    void aWrongReceiptIsRefused() {
        UUID line = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        String wrong = payload(line, variant, true).replace("\"unitCost\":1528.98", "\"unitCost\":1528.97");
        Message<byte[]> message = relayed(UUID.randomUUID(), "consignment.received", line, 1, RECEIVED_AT, wrong).build();
        int before = processed();

        assertThatThrownBy(() -> live.accept(message)).isInstanceOf(IllegalArgumentException.class);

        assertThat(lots(variant)).isZero();
        assertThat(processed()).as("nothing recorded as applied").isEqualTo(before);
    }

    @Test
    @DisplayName("a body about another line than the envelope names is refused")
    void aBodyForAnotherLine() {
        UUID line = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        Message<byte[]> message = relayed(UUID.randomUUID(), "consignment.received", UUID.randomUUID(), 1, RECEIVED_AT,
                payload(line, variant, true)).build();

        assertThatThrownBy(() -> live.accept(message)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("envelope");
        assertThat(lots(variant)).isZero();
    }

    @Test
    @DisplayName("a receipt time that is not the time the event says it happened is refused")
    void aReceiptTimeThatDisagreesWithTheEnvelope() {
        UUID line = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        Message<byte[]> message = relayed(UUID.randomUUID(), "consignment.received", line, 1, "2026-11-12T10:00:01Z",
                payload(line, variant, true)).build();

        assertThatThrownBy(() -> live.accept(message)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("receivedAt");
        assertThat(lots(variant)).isZero();
    }

    @Test
    @DisplayName("an event type the declaration does not list is refused before anything is recorded")
    void anUndeclaredType() {
        UUID line = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        Message<byte[]> message = relayed(UUID.randomUUID(), "consignment.shipped", line, 1, RECEIVED_AT, payload(line, variant, true)).build();
        int before = processed();

        assertThatThrownBy(() -> live.accept(message)).isInstanceOf(IllegalArgumentException.class);
        assertThat(lots(variant)).isZero();
        assertThat(processed()).isEqualTo(before);
    }

    @Test
    @DisplayName("an event written before linePosition existed is accepted and keyed by its outbox id")
    void anOlderEvent() {
        UUID line = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        Message<byte[]> message = relayed(UUID.randomUUID(), "consignment.received", line, 4242, RECEIVED_AT, payload(line, variant, false)).build();

        input.send(message, "oriah.procurement");

        assertThat(jdbc.queryForObject("SELECT fifo_tiebreak FROM cost_lot WHERE variant_id = ? AND slice_position = 1", Long.class, variant))
                .isEqualTo(4242L);
    }

    @Test
    @DisplayName("day-one stock arrives with no supplier and is estimated, and so are its lots")
    void openingStock() {
        UUID line = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        String opening = payload(line, variant, true).replaceAll("\"supplierId\":\"[^\"]+\"", "\"supplierId\":null")
                .replace("\"supplierName\":\"Dubai Gold\"", "\"supplierName\":null").replace("\"isEstimated\":false", "\"isEstimated\":true");

        input.send(relayed(UUID.randomUUID(), "consignment.received", line, 7, RECEIVED_AT, opening).build(), "oriah.procurement");

        List<Map<String, Object>> lots = jdbc.queryForList("SELECT is_estimated, supplier_id, supplier_name FROM cost_lot WHERE variant_id = ?", variant);
        assertThat(lots).hasSize(2).allSatisfy(l -> assertThat(l).containsEntry("is_estimated", true).containsEntry("supplier_id", null));
    }
}
