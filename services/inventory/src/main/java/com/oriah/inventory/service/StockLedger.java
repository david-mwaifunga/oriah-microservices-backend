package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.inventory.ledger.FifoConformer;
import com.oriah.inventory.ledger.FifoConformer.Action;
import com.oriah.inventory.ledger.FifoConformer.Kind;
import com.oriah.inventory.ledger.FifoConformer.LiveChunk;
import com.oriah.inventory.ledger.FifoConformer.Lot;
import com.oriah.inventory.ledger.FifoConformer.Outcome;
import com.oriah.inventory.ledger.FifoConformer.Recut;
import com.oriah.inventory.ledger.StockReason;

/**
 * Everything that reads or writes the cost lots and the stock ledger, in SQL. It never decides what the stock
 * SHOULD be: {@link FifoConformer} does, as a pure function, and this class makes the tables say so.
 *
 * <p>Every method runs inside the caller's transaction (MANDATORY): a half-written ledger must be impossible.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class StockLedger {

    /** The advisory-lock class for "the stock of one variant". Held to the end of the transaction. */
    private static final int LOCK_CLASS = 0x494E5653;   // "INVS"

    private final JdbcTemplate jdbc;

    StockLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A lot as it is held now. */
    record HeldLotRow(UUID id, UUID variantId, UUID consignmentLineId, int slicePosition, int qtyReceived, int qtyRemaining,
                      BigDecimal unitCost, Instant receivedAt, boolean estimated, String kind) {
    }

    /** What a receipt needs to make one lot. */
    record NewLot(UUID id, UUID variantId, String kind, String sku, String productTitle, String variantTitle, UUID consignmentId,
                  String consignmentReference, UUID consignmentLineId, UUID supplierId, String supplierName, Instant receivedAt,
                  long fifoTiebreak, int slicePosition, int revision, int qtyReceived, int qtyRemaining, BigDecimal unitCost,
                  boolean estimated) {
    }

    /**
     * Serialises everything that changes the stock of one variant, even before its stock row exists (two lines of a new
     * variant arriving at once must not race to create it).
     */
    void lockVariant(UUID variantId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?, hashtext(?))", (RowCallbackHandler) rs -> { }, LOCK_CLASS, variantId.toString());
    }

    boolean actionExists(UUID actionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM stock_action WHERE id = ?)", Boolean.class, actionId));
    }

    /** An action as it was recorded, for deciding whether a repeat is the same request, and for answering it again. */
    record ActionRow(UUID id, UUID variantId, String kind, StockReason reason, int qty, Instant occurredAt, Instant recordedAt, String actor,
                     String note, UUID reversesActionId, String digest) {
    }

    java.util.Optional<ActionRow> findAction(UUID id) {
        return jdbc.query("""
                SELECT id, variant_id, kind, reason, qty, occurred_at, recorded_at, actor, note, reverses_action_id, request_digest
                  FROM stock_action WHERE id = ?""",
                (rs, n) -> new ActionRow(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class), rs.getString("kind"),
                        StockReason.fromCode(rs.getString("reason")), rs.getInt("qty"), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getTimestamp("recorded_at").toInstant(), rs.getString("actor"), rs.getString("note"),
                        rs.getObject("reverses_action_id", UUID.class), rs.getString("request_digest")),
                id).stream().findFirst();
    }

    /** How many units of a write-off or give-away have been taken back. */
    int takenBack(UUID writeOffActionId) {
        return jdbc.queryForObject("SELECT coalesce(sum(qty), 0) FROM stock_action WHERE reverses_action_id = ?", Integer.class, writeOffActionId);
    }

    /** When the newest lot an action's live rows touch was received: the earliest the action could have happened. */
    Instant latestReceivedAmongLiveRows(UUID actionId) {
        Timestamp at = jdbc.queryForObject("""
                SELECT max(l.received_at) FROM live_movement m JOIN cost_lot l ON l.id = m.lot_id WHERE m.action_id = ?""",
                Timestamp.class, actionId);
        return at == null ? null : at.toInstant();
    }

    void insertAction(UUID id, UUID variantId, String kind, StockReason reason, int qty, Instant occurredAt, String actor, String note,
                      UUID reversesAction, String digest) {
        jdbc.update("""
                INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor, note, reverses_action_id, request_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, variantId, kind, reason.name(), qty, Timestamp.from(occurredAt), actor, note, reversesAction, digest);
    }

    void insertLot(NewLot l) {
        jdbc.update("""
                INSERT INTO cost_lot (id, variant_id, kind, sku, product_title, variant_title, consignment_id, consignment_reference,
                                      consignment_line_id, supplier_id, supplier_name, received_at, fifo_tiebreak, slice_position,
                                      revision, qty_received, qty_remaining, unit_cost, is_estimated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                l.id(), l.variantId(), l.kind(), l.sku(), l.productTitle(), l.variantTitle(), l.consignmentId(),
                l.consignmentReference(), l.consignmentLineId(), l.supplierId(), l.supplierName(), Timestamp.from(l.receivedAt()),
                l.fifoTiebreak(), l.slicePosition(), l.revision(), l.qtyReceived(), l.qtyRemaining(), l.unitCost(), l.estimated());
    }

    long insertMovement(UUID variantId, UUID actionId, UUID lotId, StockReason reason, String role, int qtyDelta, BigDecimal unitCost,
                        boolean estimated, String kind, Long reverses, Long replaces, Instant occurredAt, String actor) {
        return jdbc.queryForObject("""
                INSERT INTO stock_movement (variant_id, action_id, lot_id, reason, role, qty_delta, unit_cost, is_estimated, kind,
                                            reverses_movement_id, replaces_movement_id, occurred_at, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, variantId, actionId, lotId, reason.name(), role, qtyDelta, unitCost, estimated, kind, reverses, replaces,
                Timestamp.from(occurredAt), actor);
    }

    /** The lots still current, oldest first: the order FIFO draws in. */
    List<HeldLotRow> heldLots(UUID variantId) {
        return jdbc.query("""
                SELECT id, variant_id, consignment_line_id, slice_position, qty_received, qty_remaining, unit_cost, received_at,
                       is_estimated, kind
                  FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL
                 ORDER BY received_at, fifo_tiebreak, slice_position, id""",
                (rs, n) -> new HeldLotRow(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class),
                        rs.getObject("consignment_line_id", UUID.class), rs.getInt("slice_position"), rs.getInt("qty_received"),
                        rs.getInt("qty_remaining"), rs.getBigDecimal("unit_cost"), rs.getTimestamp("received_at").toInstant(),
                        rs.getBoolean("is_estimated"), rs.getString("kind")),
                variantId);
    }

    boolean lineHasLots(UUID consignmentLineId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM cost_lot WHERE consignment_line_id = ?)",
                Boolean.class, consignmentLineId));
    }

    /** What the current lots hold: the number the stock count must equal. */
    int heldQuantity(UUID variantId) {
        return jdbc.queryForObject("SELECT coalesce(sum(qty_remaining), 0) FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL",
                Integer.class, variantId);
    }

    /**
     * Makes the ledger say what oldest-first selling says it should, for one variant, and brings every lot's remaining
     * quantity in line. Anything already right is untouched; a no-op in the ordinary case.
     *
     * @return how many actions were re-cut
     */
    int conform(UUID variantId, String actor) {
        List<HeldLotRow> held = heldLots(variantId);
        List<Lot> lots = held.stream().map(l -> new Lot(l.id(), l.qtyReceived())).toList();

        List<Action> actions = new ArrayList<>();
        Map<UUID, ActionInfo> info = new HashMap<>();
        jdbc.query("""
                SELECT id, seq, kind, reason, qty, occurred_at, reverses_action_id
                  FROM stock_action
                 WHERE variant_id = ? AND kind IN ('SALE', 'WRITE_OFF', 'GIVE_AWAY', 'TAKE_BACK')
                 ORDER BY seq""",
                (RowCallbackHandler) rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    Kind kind = "TAKE_BACK".equals(rs.getString("kind")) ? Kind.RESTORE : Kind.DRAIN;
                    actions.add(new Action(id, rs.getLong("seq"), kind, rs.getInt("qty"), rs.getObject("reverses_action_id", UUID.class)));
                    info.put(id, new ActionInfo(kind, StockReason.fromCode(rs.getString("reason")), rs.getTimestamp("occurred_at").toInstant()));
                }, variantId);

        List<LiveChunk> live = jdbc.query("""
                SELECT m.id, m.action_id, m.lot_id, abs(m.qty_delta) AS qty
                  FROM live_movement m JOIN stock_action a ON a.variant_id = m.variant_id AND a.id = m.action_id
                 WHERE m.variant_id = ? AND a.kind IN ('SALE', 'WRITE_OFF', 'GIVE_AWAY', 'TAKE_BACK')""",
                (rs, n) -> new LiveChunk(rs.getLong("id"), rs.getObject("action_id", UUID.class), rs.getObject("lot_id", UUID.class),
                        rs.getInt("qty")),
                variantId);

        Outcome outcome = FifoConformer.conform(lots, actions, live);
        Map<UUID, HeldLotRow> byId = new HashMap<>();
        held.forEach(l -> byId.put(l.id(), l));

        for (Recut recut : outcome.recuts()) {
            write(variantId, recut, info.get(recut.actionId()), byId, actor);
        }
        for (HeldLotRow lot : held) {
            int remaining = outcome.remainingByLot().get(lot.id());
            if (remaining != lot.qtyRemaining()) {
                jdbc.update("UPDATE cost_lot SET qty_remaining = ? WHERE id = ?", remaining, lot.id());
            }
        }
        return outcome.recuts().size();
    }

    private record ActionInfo(Kind kind, StockReason reason, Instant occurredAt) {
    }

    private record OldRow(long id, UUID lotId, int qtyDelta, StockReason reason, BigDecimal unitCost, boolean estimated, String kind,
                          Instant occurredAt) {
    }

    private void write(UUID variantId, Recut recut, ActionInfo action, Map<UUID, HeldLotRow> lots, String actor) {
        boolean initial = recut.reversedMovementIds().isEmpty();
        for (long oldId : recut.reversedMovementIds()) {
            OldRow old = jdbc.queryForObject("""
                    SELECT id, lot_id, qty_delta, reason, unit_cost, is_estimated, kind, occurred_at FROM stock_movement WHERE id = ?""",
                    (rs, n) -> new OldRow(rs.getLong("id"), rs.getObject("lot_id", UUID.class), rs.getInt("qty_delta"),
                            StockReason.fromCode(rs.getString("reason")), rs.getBigDecimal("unit_cost"), rs.getBoolean("is_estimated"),
                            rs.getString("kind"), rs.getTimestamp("occurred_at").toInstant()),
                    oldId);
            insertMovement(variantId, recut.actionId(), old.lotId(), old.reason(), "RECUT_REVERSAL", -old.qtyDelta(), old.unitCost(),
                    old.estimated(), old.kind(), old.id(), null, old.occurredAt(), actor);
        }
        Long firstOld = initial ? null : recut.reversedMovementIds().stream().min(Long::compare).orElseThrow();
        for (FifoConformer.Chunk chunk : recut.replacements()) {
            HeldLotRow lot = lots.get(chunk.lotId());
            int delta = action.kind() == Kind.DRAIN ? -chunk.qty() : chunk.qty();
            Long restores = null;
            if (action.kind() == Kind.RESTORE) {
                UUID drain = jdbc.queryForObject("SELECT reverses_action_id FROM stock_action WHERE id = ?", UUID.class, recut.actionId());
                restores = jdbc.queryForObject("""
                        SELECT id FROM live_movement WHERE action_id = ? AND lot_id = ? AND qty_delta < 0""",
                        Long.class, drain, chunk.lotId());
            }
            insertMovement(variantId, recut.actionId(), lot.id(), action.reason(), initial ? "ORIGINAL" : "RECUT_REPLACEMENT", delta,
                    lot.unitCost(), lot.estimated(), lot.kind(), restores, firstOld, action.occurredAt(), actor);
        }
    }

    /**
     * The invariants, checked after every change: each lot holds exactly what its ledger rows add up to, and the lots
     * of the variant hold exactly the stock count.
     */
    void verify(UUID variantId, int onHand) {
        List<UUID> broken = jdbc.queryForList("""
                SELECT l.id FROM cost_lot l
                 WHERE l.variant_id = ?
                   AND l.qty_remaining <> coalesce((SELECT sum(m.qty_delta) FROM stock_movement m WHERE m.lot_id = l.id), 0)""",
                UUID.class, variantId);
        if (!broken.isEmpty()) {
            throw new IllegalStateException("the ledger and the lots disagree for variant " + variantId + ": lots " + broken);
        }
        int held = heldQuantity(variantId);
        if (held != onHand) {
            throw new IllegalStateException("stock count " + onHand + " and its lots (" + held + ") disagree for variant " + variantId);
        }
    }
}
