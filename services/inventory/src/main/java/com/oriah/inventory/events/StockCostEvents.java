package com.oriah.inventory.events;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.oriah.events.EventDraft;
import com.oriah.events.OutboxWriter;
import com.oriah.inventory.ledger.StockReason;

import jakarta.persistence.EntityManager;

/**
 * Announces what every row of the stock ledger moved ({@link StockCostRecorded}, Phase 10), as part of the transaction that wrote it.
 *
 * <p>The ledger tells this class about each row as it is written ({@link #movementWritten}); the rows are announced in ONE step just before the transaction
 * commits. That is on purpose: the outbox's lock is held until commit and is taken by whoever appends first, so appending in the middle of a transaction that
 * still has other variants to lock would let two of them wait for each other's lock for ever. Taking it last, after every variant lock this transaction needs is
 * already held, makes the order the same for everyone. No method that moves stock has to remember to announce: whatever writes a ledger row is announced, and a
 * transaction that rolls back announces nothing.
 *
 * <p>A row is announced once: {@code stock_cost_event} marks it in the same transaction as its event, so the start-up back-fill ({@link StockCostBackfill}) and
 * this path can never both announce one.
 */
@Component
public class StockCostEvents {

    /** The pending movement ids of the current transaction. */
    private static final Object PENDING = new Object();

    private static final Pattern ACTOR = Pattern.compile("(user|system):\\S.*");
    private static final String FALLBACK_ACTOR = "system:inventory";
    private static final int CHUNK = 200;

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final EntityManager entityManager;

    public StockCostEvents(JdbcTemplate jdbc, OutboxWriter outbox, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.entityManager = entityManager;
    }

    /**
     * Whether a ledger row moves a cost: not a receipt, not the transfer of a lot an amendment replaced, and not a returned piece moving to
     * its own marked-down listing (it only changed shelf: its cost is recognised when the marked-down piece is sold).
     */
    public static boolean qualifies(String role, StockReason reason) {
        return ("ORIGINAL".equals(role) || "RECUT_REVERSAL".equals(role) || "RECUT_REPLACEMENT".equals(role)) && reason != StockReason.CONSIGNMENT_RECEIVED
                && reason != StockReason.RELISTED_MARKED_DOWN;
    }

    /** Called by the ledger for every row it writes; announces those that move a cost, just before the transaction commits. */
    public void movementWritten(long movementId, String role, StockReason reason) {
        if (!qualifies(role, reason)) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("a ledger row is written inside a transaction: there is none to announce it with");
        }
        @SuppressWarnings("unchecked")
        List<Long> pending = (List<Long>) TransactionSynchronizationManager.getResource(PENDING);
        if (pending == null) {
            pending = new ArrayList<>();
            TransactionSynchronizationManager.bindResource(PENDING, pending);
            TransactionSynchronizationManager.registerSynchronization(new Announce(pending));
        }
        pending.add(movementId);
    }

    /** Announces the rows just before the commit, then lets go of the list whatever happens to the transaction. */
    private final class Announce implements TransactionSynchronization {

        private final List<Long> pending;

        Announce(List<Long> pending) {
            this.pending = pending;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            // Entity changes are written to the database at commit; do it now, so the outbox's lock is the last one this transaction takes.
            entityManager.flush();
            announce(pending);
        }

        @Override
        public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(PENDING);
        }
    }

    /**
     * Announces these rows, each once, in the caller's transaction. A row already marked is skipped. This is the caller's last write.
     *
     * @return how many events were appended
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int announce(List<Long> movementIds) {
        int announced = 0;
        for (int from = 0; from < movementIds.size(); from += CHUNK) {
            List<Long> chunk = movementIds.subList(from, Math.min(movementIds.size(), from + CHUNK));
            String in = String.join(",", chunk.stream().map(id -> "?").toList());
            List<Row> rows = jdbc.query("""
                    SELECT m.id, m.action_id, a.kind AS action_kind, m.reason, m.role, m.variant_id, m.lot_id, l.consignment_line_id, m.kind, m.qty_delta,
                           m.unit_cost, m.currency, m.is_estimated, m.reverses_movement_id, m.replaces_movement_id, a.reverses_action_id,
                           ra.reason AS reverses_reason, m.occurred_at, m.recorded_at, m.actor
                      FROM stock_movement m
                      JOIN stock_action a ON a.variant_id = m.variant_id AND a.id = m.action_id
                      JOIN cost_lot l ON l.id = m.lot_id
                      LEFT JOIN stock_action ra ON ra.id = a.reverses_action_id
                     WHERE m.id IN (%s) ORDER BY m.id""".formatted(in),
                    (rs, n) -> new Row(rs.getLong("id"), rs.getObject("action_id", UUID.class), rs.getString("action_kind"), rs.getString("reason"),
                            rs.getString("role"), rs.getObject("variant_id", UUID.class), rs.getObject("lot_id", UUID.class),
                            rs.getObject("consignment_line_id", UUID.class), rs.getString("kind"), rs.getInt("qty_delta"), rs.getBigDecimal("unit_cost"),
                            rs.getString("currency"), rs.getBoolean("is_estimated"), (Long) rs.getObject("reverses_movement_id"),
                            (Long) rs.getObject("replaces_movement_id"), rs.getObject("reverses_action_id", UUID.class), rs.getString("reverses_reason"),
                            rs.getTimestamp("occurred_at"), rs.getTimestamp("recorded_at"), rs.getString("actor")),
                    chunk.toArray());
            for (Row row : rows) {
                if (jdbc.update("INSERT INTO stock_cost_event (movement_id) VALUES (?) ON CONFLICT DO NOTHING", row.id()) == 0) {
                    continue;   // already announced: by the live path or by the back-fill
                }
                StockCostRecorded payload = new StockCostRecorded(row.id(), row.actionId(), row.actionKind(), row.reason(), row.role(), row.variantId(), row.lotId(),
                        row.consignmentLineId(), row.kind(), productOf(row), row.qtyDelta(), row.unitCost(), row.currency(), row.estimated(),
                        row.reversesMovementId(), row.replacesMovementId(), row.reversesActionId(), row.reversesReason(), row.recordedAt().toInstant());
                outbox.append(new EventDraft(StockCostRecorded.AGGREGATE, row.actionId().toString(), StockCostRecorded.TYPE, 1, ReservationEvents.TOPIC,
                        row.occurredAt().toInstant(), actor(row.actor()), payload));
                announced++;
            }
        }
        return announced;
    }

    /** {@code user:<subject>} or {@code system:<name>}: a ledger actor that is anything else (the consumers pass a header that can read "null") is the service. */
    static String actor(String held) {
        return held != null && held.length() <= 128 && ACTOR.matcher(held).matches() ? held : FALLBACK_ACTOR;
    }

    private record Row(long id, UUID actionId, String actionKind, String reason, String role, UUID variantId, UUID lotId, UUID consignmentLineId, String kind,
                       int qtyDelta, java.math.BigDecimal unitCost, String currency, boolean estimated, Long reversesMovementId, Long replacesMovementId,
                       UUID reversesActionId, String reversesReason, Timestamp occurredAt, Timestamp recordedAt, String actor) {
    }

    /**
     * The product a cost belongs to, in this order: the product a BOX line of a sale was sold with (the action is that line's id); the product of a box swap
     * (either of its two actions); the product of the action this one undoes; the product of the variant itself. Null when nothing says.
     */
    private UUID productOf(Row row) {
        return productOfAction(row.actionId(), row.variantId(), row.reversesActionId());
    }

    private UUID productOfAction(UUID actionId, UUID variantId, UUID reversesActionId) {
        UUID product = first("SELECT product_id FROM reservation_line WHERE sale_line_id = ? AND kind = 'BOX' LIMIT 1", actionId);
        if (product != null) {
            return product;
        }
        product = first("SELECT product_id FROM box_swap WHERE replacement_action_id = ? OR original_action_id = ? LIMIT 1", actionId, actionId);
        if (product != null) {
            return product;
        }
        if (reversesActionId != null) {
            List<UUID[]> undone = jdbc.query("SELECT variant_id, reverses_action_id FROM stock_action WHERE id = ?",
                    (rs, n) -> new UUID[] {rs.getObject("variant_id", UUID.class), rs.getObject("reverses_action_id", UUID.class)}, reversesActionId);
            if (!undone.isEmpty()) {
                return productOfAction(reversesActionId, undone.get(0)[0], undone.get(0)[1]);
            }
        }
        return first("SELECT product_id FROM product_index WHERE variant_id = ?", variantId);
    }

    private UUID first(String sql, Object... args) {
        List<UUID> found = jdbc.queryForList(sql, UUID.class, args);
        return found.isEmpty() ? null : found.get(0);
    }
}
