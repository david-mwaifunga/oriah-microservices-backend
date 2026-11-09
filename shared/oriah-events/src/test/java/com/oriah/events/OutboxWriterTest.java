package com.oriah.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Appending a fact to the outbox: only inside the caller's own transaction
 * (so the fact and the change it describes commit or vanish together), only
 * to a declared topic, and numbered 1, 2, 3 within its aggregate.
 */
@ProbeTest
class OutboxWriterTest {

    static final Instant WHEN = Instant.parse("2026-11-09T07:30:00.123456Z");

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("an append outside a transaction is refused and writes nothing")
    void outsideTransactionRefused() {
        String order = fresh();

        assertThatThrownBy(() -> writer.append(draft(order, "oriah.probe")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rowsFor(order)).isZero();
    }

    @Test
    @DisplayName("a topic the service has not declared is refused and writes nothing")
    void undeclaredTopicRefused() {
        String order = fresh();

        assertThatThrownBy(() -> inTransaction(() -> writer.append(draft(order, "oriah.prboe"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oriah.prboe");
        assertThat(rowsFor(order)).isZero();
    }

    @Test
    @DisplayName("seq counts 1, 2, 3 within an aggregate, and each aggregate counts on its own")
    void seqPerAggregate() {
        String a = fresh();
        String b = fresh();

        AppendedEvent a1 = inTransaction(() -> writer.append(draft(a, "oriah.probe")));
        AppendedEvent a2 = inTransaction(() -> writer.append(draft(a, "oriah.probe")));
        List<AppendedEvent> b1a3 = inTransaction(() -> List.of(
                writer.append(draft(b, "oriah.probe-other")),
                writer.append(draft(a, "oriah.probe"))));

        assertThat(List.of(a1.seq(), a2.seq(), b1a3.get(1).seq())).containsExactly(1L, 2L, 3L);
        assertThat(b1a3.get(0).seq()).isEqualTo(1L);
        assertThat(List.of(a1.outboxId(), a2.outboxId(), b1a3.get(0).outboxId(), b1a3.get(1).outboxId()))
                .isSorted().doesNotHaveDuplicates();
        assertThat(jdbc.queryForList("SELECT seq FROM outbox WHERE aggregate_id = ? ORDER BY outbox_id",
                Long.class, a)).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("the row holds the draft as given, and the payload is stored exactly as the mapper wrote it")
    void rowHoldsTheDraft() {
        String order = fresh();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order);
        payload.put("previousStatus", null);
        payload.put("total", new BigDecimal("150.00"));
        payload.put("currency", "ZMW");
        EventDraft draft = new EventDraft("order", order, "order.created", 1, "oriah.probe", WHEN,
                "user:5b1c-subject", payload);

        AppendedEvent appended = inTransaction(() -> writer.append(draft));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT event_id, aggregate_type, aggregate_id, seq, event_type, schema_version, topic,
                       occurred_at, recorded_at, actor, payload::text AS payload
                FROM outbox WHERE outbox_id = ?""", appended.outboxId());
        assertThat(row.get("event_id")).isEqualTo(appended.eventId());
        assertThat(row.get("aggregate_type")).isEqualTo("order");
        assertThat(row.get("aggregate_id")).isEqualTo(order);
        assertThat(row.get("seq")).isEqualTo(1L);
        assertThat(row.get("event_type")).isEqualTo("order.created");
        assertThat(row.get("schema_version")).isEqualTo(1);
        assertThat(row.get("topic")).isEqualTo("oriah.probe");
        assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(WHEN);
        assertThat(row.get("recorded_at")).isNotNull();
        assertThat(row.get("actor")).isEqualTo("user:5b1c-subject");
        assertThat(row.get("payload"))
                .isEqualTo(EventJson.write(payload))
                .isEqualTo("{\"orderId\":\"" + order + "\",\"previousStatus\":null,\"total\":150.00,\"currency\":\"ZMW\"}");
    }

    @Test
    @DisplayName("an append rolled back with its transaction leaves no row")
    void rollbackLeavesNothing() {
        String order = fresh();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            writer.append(draft(order, "oriah.probe"));
            status.setRollbackOnly();
        });

        assertThat(rowsFor(order)).isZero();
    }

    static String fresh() {
        return UUID.randomUUID().toString();
    }

    static EventDraft draft(String orderId, String topic) {
        return new EventDraft("order", orderId, "order.created", 1, topic, WHEN, "system:probe",
                Map.of("orderId", orderId));
    }

    private long rowsFor(String aggregateId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, aggregateId);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
