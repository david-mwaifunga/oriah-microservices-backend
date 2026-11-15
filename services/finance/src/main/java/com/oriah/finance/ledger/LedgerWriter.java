package com.oriah.finance.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way a row reaches the ledger. A row happens ONCE: its key is derived from the source's own identity (a confirmation, a stock movement, a consignment
 * revision), so a replayed or repeated event is a no-op; a row that arrives again with a DIFFERENT account, amount or day is not silently ignored but raised as a
 * {@code conflicting-duplicate} anomaly (a reset producer reusing an id, or a defect), and never overwrites the first.
 *
 * <p>Every writer holds the ledger lock first ({@link Locks#writer()}); taking it again here is free and means a writer that forgot cannot write.
 */
@Component
public class LedgerWriter {

    private final JdbcTemplate jdbc;
    private final Locks locks;
    private final AnomalyService anomalies;

    public LedgerWriter(JdbcTemplate jdbc, Locks locks, AnomalyService anomalies) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.anomalies = anomalies;
    }

    /** What happened to a row offered to the ledger. */
    public record Posted(boolean inserted, long entryId) {
    }

    /** One row to post. Built with {@link #of}; the rest is optional and says what the row is about. */
    public static final class Entry {
        final String key;
        final String account;
        final BigDecimal amount;
        final Instant occurredAt;
        final LocalDate effectDay;
        final String source;
        final String actor;
        String category;
        String reason;
        Instant sourceRecordedAt;
        UUID sourceEventId;
        String producer;
        Long outboxId;
        UUID orderId;
        UUID saleLineId;
        UUID confirmationId;
        UUID consignmentId;
        UUID consignmentLineId;
        UUID variantId;
        UUID productId;
        UUID stockActionId;
        Long movementId;
        UUID promotionId;
        String stockKind;
        boolean estimated;
        Long reversesEntryId;

        private Entry(String key, String account, BigDecimal amount, Instant occurredAt, LocalDate effectDay, String source, String actor) {
            this.key = key;
            this.account = account;
            this.amount = amount;
            this.occurredAt = occurredAt;
            this.effectDay = effectDay;
            this.source = source;
            this.actor = actor;
        }

        /** @param amount in the account's natural direction (positive); a reversal is negative */
        public static Entry of(String key, String account, BigDecimal amount, Instant occurredAt, LocalDate effectDay, String source, String actor) {
            return new Entry(key, account, amount, occurredAt, effectDay, source, actor);
        }

        public Entry category(String v) { this.category = v; return this; }
        public Entry reason(String v) { this.reason = v; return this; }
        public Entry sourceRecordedAt(Instant v) { this.sourceRecordedAt = v; return this; }
        public Entry event(UUID id, String producer, Long outboxId) { this.sourceEventId = id; this.producer = producer; this.outboxId = outboxId; return this; }
        public Entry order(UUID v) { this.orderId = v; return this; }
        public Entry saleLine(UUID v) { this.saleLineId = v; return this; }
        public Entry confirmation(UUID v) { this.confirmationId = v; return this; }
        public Entry consignment(UUID id, UUID lineId) { this.consignmentId = id; this.consignmentLineId = lineId; return this; }
        public Entry variant(UUID v) { this.variantId = v; return this; }
        public Entry product(UUID v) { this.productId = v; return this; }
        public Entry stockAction(UUID v) { this.stockActionId = v; return this; }
        public Entry movement(Long v) { this.movementId = v; return this; }
        public Entry promotion(UUID v) { this.promotionId = v; return this; }
        public Entry stockKind(String v) { this.stockKind = v; return this; }
        public Entry estimated(boolean v) { this.estimated = v; return this; }
        public Entry reverses(Long v) { this.reversesEntryId = v; return this; }
    }

    /**
     * Offers a row to the ledger. @throws IllegalArgumentException an amount finer than an ngwee
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Posted post(Entry e) {
        locks.writer();
        BigDecimal amount;
        try {
            amount = e.amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException finer) {
            throw new IllegalArgumentException("an amount is a whole number of ngwee, was " + e.amount.toPlainString());
        }
        List<Long> inserted = jdbc.query("""
                INSERT INTO ledger_entry (entry_key, account, category, reason, amount, occurred_at, effect_day, source_recorded_at, source, source_event_id, producer,
                                          outbox_id, order_id, sale_line_id, confirmation_id, consignment_id, consignment_line_id, variant_id, product_id,
                                          stock_action_id, movement_id, promotion_id, stock_kind, estimated, reverses_entry_id, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (entry_key) DO NOTHING RETURNING entry_id""",
                (rs, n) -> rs.getLong(1), e.key, e.account, e.category, e.reason, amount, Timestamp.from(e.occurredAt), e.effectDay,
                e.sourceRecordedAt == null ? null : Timestamp.from(e.sourceRecordedAt), e.source, e.sourceEventId, e.producer, e.outboxId, e.orderId, e.saleLineId,
                e.confirmationId, e.consignmentId, e.consignmentLineId, e.variantId, e.productId, e.stockActionId, e.movementId, e.promotionId, e.stockKind,
                e.estimated, e.reversesEntryId, e.actor);
        if (!inserted.isEmpty()) {
            return new Posted(true, inserted.get(0));
        }
        Map<String, Object> held = jdbc.queryForMap("SELECT entry_id, account, amount, effect_day FROM ledger_entry WHERE entry_key = ?", e.key);
        boolean same = e.account.equals(held.get("account")) && amount.compareTo((BigDecimal) held.get("amount")) == 0
                && e.effectDay.equals(((java.sql.Date) held.get("effect_day")).toLocalDate());
        if (!same) {
            anomalies.raise("conflicting-duplicate", e.key, AnomalyService.Severity.ERROR,
                    Map.of("key", e.key, "account", e.account, "heldAmount", held.get("amount").toString(), "newAmount", amount.toPlainString()), e.sourceEventId);
        }
        return new Posted(false, ((Number) held.get("entry_id")).longValue());
    }
}
