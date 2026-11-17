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

import com.oriah.inventory.events.StockCostEvents;
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
    private final StockCostEvents costEvents;

    StockLedger(JdbcTemplate jdbc, StockCostEvents costEvents) {
        this.jdbc = jdbc;
        this.costEvents = costEvents;
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
        insertAction(id, variantId, kind, reason, qty, occurredAt, actor, note, reversesAction, null, digest);
    }

    /**
     * @param assessesAction only for the outcome of a returned piece ({@code RELEASE}, {@code RETURN_EXIT}): the take-back that put the piece
     *                       back, which is the piece's id
     */
    void insertAction(UUID id, UUID variantId, String kind, StockReason reason, int qty, Instant occurredAt, String actor, String note,
                      UUID reversesAction, UUID assessesAction, String digest) {
        jdbc.update("""
                INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor, note, reverses_action_id, assesses_action_id, request_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, variantId, kind, reason.name(), qty, Timestamp.from(occurredAt), actor, note, reversesAction, assessesAction, digest);
    }

    void insertLot(NewLot l) {
        insertLot(l, null, null);
    }

    /**
     * The one-piece lot of a marked-down listing (Phase 12): a lot like any other, that also says which lot its piece came out of and which
     * movement took it out. It shares that lot's delivery line, so the line still accounts for every piece it brought.
     */
    void insertChildLot(NewLot l, UUID sourceLotId, UUID sourceActionId) {
        insertLot(l, sourceLotId, sourceActionId);
    }

    private void insertLot(NewLot l, UUID sourceLotId, UUID sourceActionId) {
        jdbc.update("""
                INSERT INTO cost_lot (id, variant_id, kind, sku, product_title, variant_title, consignment_id, consignment_reference,
                                      consignment_line_id, supplier_id, supplier_name, received_at, fifo_tiebreak, slice_position,
                                      revision, qty_received, qty_remaining, unit_cost, is_estimated, source_lot_id, source_action_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                l.id(), l.variantId(), l.kind(), l.sku(), l.productTitle(), l.variantTitle(), l.consignmentId(),
                l.consignmentReference(), l.consignmentLineId(), l.supplierId(), l.supplierName(), Timestamp.from(l.receivedAt()),
                l.fifoTiebreak(), l.slicePosition(), l.revision(), l.qtyReceived(), l.qtyRemaining(), l.unitCost(), l.estimated(),
                sourceLotId, sourceActionId);
    }

    // ---------------------------------------------------------------- the marked-down lot family (Phase 12)

    /** A lot as it was made: everything a lot that takes its place (or its piece) copies from it. */
    record LotIdentity(UUID id, UUID variantId, String kind, String sku, String productTitle, String variantTitle, UUID consignmentId,
                       String consignmentReference, UUID consignmentLineId, UUID supplierId, String supplierName, Instant receivedAt, long fifoTiebreak,
                       int slicePosition, int revision, BigDecimal unitCost, boolean estimated) {
    }

    LotIdentity lot(UUID lotId) {
        return jdbc.queryForObject("""
                SELECT id, variant_id, kind, sku, product_title, variant_title, consignment_id, consignment_reference, consignment_line_id, supplier_id,
                       supplier_name, received_at, fifo_tiebreak, slice_position, revision, unit_cost, is_estimated FROM cost_lot WHERE id = ?""",
                (rs, n) -> new LotIdentity(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class), rs.getString("kind"), rs.getString("sku"),
                        rs.getString("product_title"), rs.getString("variant_title"), rs.getObject("consignment_id", UUID.class),
                        rs.getString("consignment_reference"), rs.getObject("consignment_line_id", UUID.class), rs.getObject("supplier_id", UUID.class),
                        rs.getString("supplier_name"), rs.getTimestamp("received_at").toInstant(), rs.getLong("fifo_tiebreak"), rs.getInt("slice_position"),
                        rs.getInt("revision"), rs.getBigDecimal("unit_cost"), rs.getBoolean("is_estimated")),
                lotId);
    }

    /** The lot a returned piece left from, and at what cost: the live row of the movement that took it out. */
    record Exit(UUID lotId, BigDecimal unitCost, boolean estimated) {
    }

    Exit exitOf(UUID exitActionId) {
        return jdbc.queryForObject("SELECT lot_id, unit_cost, is_estimated FROM live_movement WHERE action_id = ?",
                (rs, n) -> new Exit(rs.getObject("lot_id", UUID.class), rs.getBigDecimal("unit_cost"), rs.getBoolean("is_estimated")), exitActionId);
    }

    /** The marked-down listings that hold pieces of this variant: their stock rows are locked together with its own. */
    List<UUID> childrenOf(UUID sourceVariantId) {
        return jdbc.queryForList("SELECT child_variant_id FROM markdown_relist WHERE source_variant_id = ? ORDER BY child_variant_id", UUID.class, sourceVariantId);
    }

    /**
     * One marked-down piece of a variant, as its family stands: the lot and cost its piece left at NOW (the live row of its exit), beside the
     * one-piece lot that holds it. In step: the lot points at that lot and costs that cost. Any of the lot columns may be null when the
     * books are broken, which is exactly what the reconciliation reports.
     */
    record Family(UUID pieceId, UUID childVariantId, UUID exitActionId, UUID exitLotId, BigDecimal exitUnitCost, Boolean exitEstimated, UUID childLotId,
                  UUID sourceLotId, BigDecimal childUnitCost, Boolean childEstimated, Integer childRevision) {

        boolean inStep() {
            return exitLotId != null && childLotId != null && exitLotId.equals(sourceLotId) && exitUnitCost.compareTo(childUnitCost) == 0
                    && exitEstimated.equals(childEstimated);
        }
    }

    /** @param sourceVariantId one variant's marked-down pieces, or null for every one there is */
    List<Family> families(UUID sourceVariantId) {
        return jdbc.query("""
                SELECT r.piece_id, r.child_variant_id, r.exit_action_id, x.lot_id AS exit_lot, x.unit_cost AS exit_cost, x.is_estimated AS exit_estimated,
                       c.id AS child_lot, c.source_lot_id, c.unit_cost AS child_cost, c.is_estimated AS child_estimated, c.revision AS child_revision
                  FROM markdown_relist r
                  LEFT JOIN live_movement x ON x.action_id = r.exit_action_id
                  LEFT JOIN cost_lot c ON c.variant_id = r.child_variant_id AND c.superseded_at IS NULL
                 WHERE (?::uuid IS NULL OR r.source_variant_id = ?)
                 ORDER BY r.piece_id""",
                (rs, n) -> new Family(rs.getObject("piece_id", UUID.class), rs.getObject("child_variant_id", UUID.class), rs.getObject("exit_action_id", UUID.class),
                        rs.getObject("exit_lot", UUID.class), rs.getBigDecimal("exit_cost"), (Boolean) rs.getObject("exit_estimated"),
                        rs.getObject("child_lot", UUID.class), rs.getObject("source_lot_id", UUID.class), rs.getBigDecimal("child_cost"),
                        (Boolean) rs.getObject("child_estimated"), (Integer) rs.getObject("child_revision")),
                sourceVariantId, sourceVariantId);
    }

    /**
     * Brings every marked-down lot of a variant back in step with its piece, after something changed WHICH lot the piece left from or what
     * it cost there. Only two things can: a corrected cost (the lots are replaced) and an older delivery keyed late (oldest-first hands the
     * sale, its return and the piece's exit to the older lot). Both run this right after re-cutting the variant.
     *
     * <p>For each piece out of step the one-piece lot is REPLACED, exactly as an amendment replaces a lot: a new one-piece lot at the piece's
     * cost now, pointing at the lot it now came from (and carrying that lot's delivery line); whatever drained the old one (the marked-down
     * piece's sale) is re-cut onto the new one at its own date; and the old one is closed and kept.
     *
     * <p>The caller holds the stock rows of the variant and of its marked-down listings, taken together in id order.
     *
     * @param eventId the event that caused it: with the piece's id it names the correction, so applying the event again changes nothing twice
     * @return how many marked-down lots were replaced
     */
    int followFamily(UUID sourceVariantId, UUID eventId, Instant at, String actor) {
        int replaced = 0;
        for (Family family : families(sourceVariantId)) {
            if (family.inStep()) {
                continue;
            }
            if (family.exitLotId() == null || family.childLotId() == null) {
                throw new IllegalStateException("marked-down piece " + family.pieceId() + " has lost " + (family.exitLotId() == null ? "the movement that took it out"
                        : "its own lot") + ": the books need a person");
            }
            UUID child = family.childVariantId();
            LotIdentity from = lot(family.exitLotId());
            LotIdentity old = lot(family.childLotId());
            UUID correctionId = UUID.nameUUIDFromBytes(("markdown-recost:" + eventId + ":" + family.pieceId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            UUID lotId = UUID.randomUUID();
            insertAction(correctionId, child, "CORRECTION", StockReason.COST_CORRECTED, 1, at, actor, "the marked-down piece follows the lot it came from", null, null);
            insertChildLot(new NewLot(lotId, child, old.kind(), old.sku(), from.productTitle(), old.variantTitle(), from.consignmentId(), from.consignmentReference(),
                    from.consignmentLineId(), from.supplierId(), from.supplierName(), from.receivedAt(), from.fifoTiebreak(), from.slicePosition(),
                    old.revision() + 1, 1, 1, family.exitUnitCost(), family.exitEstimated()), from.id(), family.exitActionId());
            insertMovement(child, correctionId, lotId, StockReason.COST_CORRECTED, "LOT_OPEN", 1, family.exitUnitCost(), family.exitEstimated(), old.kind(), null, null,
                    at, actor);
            java.util.Set<UUID> staged = java.util.Set.of(old.id());
            conform(child, actor, staged);
            closeLots(child, staged, correctionId, StockReason.COST_CORRECTED, at, actor);
            verify(child, jdbc.queryForObject("SELECT on_hand FROM inventory WHERE variant_id = ?", Integer.class, child));
            replaced++;
        }
        return replaced;
    }

    long insertMovement(UUID variantId, UUID actionId, UUID lotId, StockReason reason, String role, int qtyDelta, BigDecimal unitCost,
                        boolean estimated, String kind, Long reverses, Long replaces, Instant occurredAt, String actor) {
        long id = jdbc.queryForObject("""
                INSERT INTO stock_movement (variant_id, action_id, lot_id, reason, role, qty_delta, unit_cost, is_estimated, kind,
                                            reverses_movement_id, replaces_movement_id, occurred_at, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, variantId, actionId, lotId, reason.name(), role, qtyDelta, unitCost, estimated, kind, reverses, replaces,
                Timestamp.from(occurredAt), actor);
        // Every row that moves a cost is announced to finance, just before this transaction commits (a receipt and a lot transfer move none).
        costEvents.movementWritten(id, role, reason);
        return id;
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

    /** A lot of one consignment line, with what an amendment copies from it. */
    record LineLot(UUID id, UUID variantId, String kind, String sku, String productTitle, String variantTitle, UUID consignmentId,
                   String consignmentReference, UUID supplierId, String supplierName, Instant receivedAt, long fifoTiebreak, int slicePosition,
                   int revision, int qtyReceived, BigDecimal unitCost, boolean estimated) {
    }

    /**
     * The lots of one consignment line that are current (not replaced), in slice order: the line's OWN lots. A marked-down piece's one-piece
     * lot carries the line too (the line still accounts for that piece), but it is not a slice of the delivery and an amendment does not
     * restate it: it follows ({@link #followFamily}).
     */
    List<LineLot> currentLotsOfLine(UUID consignmentLineId) {
        return jdbc.query("""
                SELECT id, variant_id, kind, sku, product_title, variant_title, consignment_id, consignment_reference, supplier_id, supplier_name,
                       received_at, fifo_tiebreak, slice_position, revision, qty_received, unit_cost, is_estimated
                  FROM cost_lot WHERE consignment_line_id = ? AND superseded_at IS NULL AND source_lot_id IS NULL ORDER BY slice_position""",
                (rs, n) -> new LineLot(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class), rs.getString("kind"), rs.getString("sku"),
                        rs.getString("product_title"), rs.getString("variant_title"), rs.getObject("consignment_id", UUID.class),
                        rs.getString("consignment_reference"), rs.getObject("supplier_id", UUID.class), rs.getString("supplier_name"),
                        rs.getTimestamp("received_at").toInstant(), rs.getLong("fifo_tiebreak"), rs.getInt("slice_position"), rs.getInt("revision"),
                        rs.getInt("qty_received"), rs.getBigDecimal("unit_cost"), rs.getBoolean("is_estimated")),
                consignmentLineId);
    }

    /**
     * Closes lots an amendment replaced, AFTER everything that drained them has been re-cut away: a lot transfer row takes
     * back what is left in each (all of it, by then), and the lot is marked replaced and emptied in one step.
     */
    void closeLots(UUID variantId, java.util.Set<UUID> staged, UUID correctionActionId, StockReason reason, Instant at, String actor) {
        for (UUID lotId : staged) {
            LineLot lot = jdbc.queryForObject("""
                    SELECT id, variant_id, kind, sku, product_title, variant_title, consignment_id, consignment_reference, supplier_id, supplier_name,
                           received_at, fifo_tiebreak, slice_position, revision, qty_received, unit_cost, is_estimated FROM cost_lot WHERE id = ?""",
                    (rs, n) -> new LineLot(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class), rs.getString("kind"), rs.getString("sku"),
                            rs.getString("product_title"), rs.getString("variant_title"), rs.getObject("consignment_id", UUID.class),
                            rs.getString("consignment_reference"), rs.getObject("supplier_id", UUID.class), rs.getString("supplier_name"),
                            rs.getTimestamp("received_at").toInstant(), rs.getLong("fifo_tiebreak"), rs.getInt("slice_position"), rs.getInt("revision"),
                            rs.getInt("qty_received"), rs.getBigDecimal("unit_cost"), rs.getBoolean("is_estimated")),
                    lotId);
            insertMovement(variantId, correctionActionId, lotId, reason, "LOT_CLOSE", -lot.qtyReceived(), lot.unitCost(), lot.estimated(), lot.kind(),
                    null, null, at, actor);
            jdbc.update("UPDATE cost_lot SET qty_remaining = 0, superseded_at = ? WHERE id = ?", Timestamp.from(at), lotId);
        }
    }

    /**
     * The valuation invariant for one line: what its current lots still hold, at their cost, plus what has been recognised
     * out of them (sales, write-offs, give-aways, net of take-backs, over the rows in force), is exactly the line's landed
     * total, to the ngwee. Whatever has happened to the line, no ngwee has been lost or invented.
     *
     * <p>A returned piece moved to a marked-down listing recognises nothing: it left one lot of the line and sits in another lot of the same
     * line (its own), at the same cost. So those two rows are not "recognised", the one-piece lot counts as held, and when the marked-down
     * piece is sold its sale is recognised like any other.
     */
    void verifyLineValuation(UUID consignmentLineId, BigDecimal landedTotal) {
        BigDecimal held = jdbc.queryForObject(
                "SELECT coalesce(sum(qty_remaining * unit_cost), 0) FROM cost_lot WHERE consignment_line_id = ? AND superseded_at IS NULL",
                BigDecimal.class, consignmentLineId);
        BigDecimal recognised = jdbc.queryForObject("""
                SELECT coalesce(sum(-m.qty_delta * m.unit_cost), 0)
                  FROM live_movement m JOIN cost_lot l ON l.id = m.lot_id
                 WHERE l.consignment_line_id = ? AND l.superseded_at IS NULL
                   AND m.reason NOT IN ('CONSIGNMENT_RECEIVED', 'RELISTED_MARKED_DOWN')""",
                BigDecimal.class, consignmentLineId);
        if (held.add(recognised).compareTo(landedTotal) != 0) {
            throw new IllegalStateException("line " + consignmentLineId + " is worth " + held.add(recognised).toPlainString()
                    + " (held " + held.toPlainString() + ", recognised " + recognised.toPlainString() + ") but its landed total is "
                    + landedTotal.toPlainString());
        }
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
        return conform(variantId, actor, java.util.Set.of());
    }

    /**
     * As {@link #conform(UUID, String)}, with some lots STAGED: kept out of the simulation, as if they were already gone.
     * Whatever drained them is re-cut onto the lots that remain, which is how a corrected cost is laid over the units.
     * The caller closes the staged lots afterwards ({@link #closeLots}).
     */
    int conform(UUID variantId, String actor, java.util.Set<UUID> staged) {
        List<HeldLotRow> held = heldLots(variantId).stream().filter(l -> !staged.contains(l.id())).toList();
        List<Lot> lots = held.stream().map(l -> new Lot(l.id(), l.qtyReceived())).toList();

        List<Action> actions = new ArrayList<>();
        Map<UUID, ActionInfo> info = new HashMap<>();
        jdbc.query("""
                SELECT id, seq, kind, reason, qty, occurred_at, reverses_action_id, assesses_action_id
                  FROM stock_action
                 WHERE variant_id = ? AND kind IN ('SALE', 'WRITE_OFF', 'GIVE_AWAY', 'TAKE_BACK', 'RELEASE', 'RETURN_EXIT')
                 ORDER BY seq""",
                (RowCallbackHandler) rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    // A take-back puts units back where they came from. A RETURNED piece is put back and HELD there (not for sale
                    // until somebody has looked at it); every other take-back is ordinary stock again. The held piece is then either
                    // released (ordinary stock; nothing moves) or it leaves, out of the lot it is in.
                    Kind kind = switch (rs.getString("kind")) {
                        case "TAKE_BACK" -> "RETURN".equals(rs.getString("reason")) ? Kind.RESTORE_HELD : Kind.RESTORE;
                        case "RELEASE" -> Kind.RELEASE;
                        case "RETURN_EXIT" -> Kind.REDRAW;
                        default -> Kind.DRAIN;
                    };
                    actions.add(new Action(id, rs.getLong("seq"), kind, rs.getInt("qty"), rs.getObject("reverses_action_id", UUID.class),
                            rs.getObject("assesses_action_id", UUID.class)));
                    info.put(id, new ActionInfo(kind, StockReason.fromCode(rs.getString("reason")), rs.getTimestamp("occurred_at").toInstant()));
                }, variantId);

        List<LiveChunk> live = jdbc.query("""
                SELECT m.id, m.action_id, m.lot_id, abs(m.qty_delta) AS qty
                  FROM live_movement m JOIN stock_action a ON a.variant_id = m.variant_id AND a.id = m.action_id
                 WHERE m.variant_id = ? AND a.kind IN ('SALE', 'WRITE_OFF', 'GIVE_AWAY', 'TAKE_BACK', 'RELEASE', 'RETURN_EXIT')""",
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
            // Out of the lot for a drain and for a returned piece that leaves; into it for a restore.
            int delta = action.kind() == Kind.DRAIN || action.kind() == Kind.REDRAW ? -chunk.qty() : chunk.qty();
            Long restores = null;
            if (action.kind() == Kind.RESTORE || action.kind() == Kind.RESTORE_HELD) {
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
        // Every marked-down piece of this variant still costs what its piece left at. Only a corrected cost or an older delivery keyed late
        // can change that, and both bring the family back in step before they get here: anything else that did would be a bug, said loudly.
        for (Family family : families(variantId)) {
            if (!family.inStep()) {
                throw new IllegalStateException("marked-down piece " + family.pieceId() + " is out of step with the lot it came from (lot "
                        + family.exitLotId() + " at " + family.exitUnitCost() + ", its own lot " + family.childLotId() + " at " + family.childUnitCost() + ")");
            }
        }
    }
}
