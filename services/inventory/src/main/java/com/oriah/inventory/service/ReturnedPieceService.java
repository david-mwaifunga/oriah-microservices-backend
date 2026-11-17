package com.oriah.inventory.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * What becomes of a piece a customer sent back (Phase 12), once somebody has looked at it. The piece was put back into the lot it left and
 * HELD there by {@link SaleReversalService}: on the shelf, counted, not for sale. The order system then says, once and for good:
 *
 * <ul>
 *   <li>{@code RELEASED}: it is fine. It is ordinary stock again; nothing moves and no cost is recognised;</li>
 *   <li>{@code WRITTEN_OFF}: it cannot be sold. It leaves the lot it went back to, at that lot's cost (reason {@code WRITE_OFF_RETURNED}),
 *       and finance is told;</li>
 *   <li>{@code RELISTED}: it will be sold again at a lower price, under a marked-down listing of its own.</li>
 * </ul>
 *
 * One transaction, under the piece's own advisory lock, so two answers for one piece wait for each other: the same answer again is a repeat
 * ({@code replay}), another answer is refused. The stock row is locked before anything is judged, as everywhere else.
 */
@Service
@Slf4j
public class ReturnedPieceService {

    /** A decision as recorded. {@code replay}: this was a repeat of one already made. */
    public record Decision(UUID pieceId, String outcome, UUID variantId, UUID childVariantId, Instant occurredAt, boolean replay) {
    }

    private record Waiting(UUID pieceId, UUID variantId, Instant restoredAt, String outcome, UUID childVariantId, Instant outcomeAt) {
    }

    /** The advisory-lock class for "one returned piece, by its id". Held to the end of the transaction. */
    private static final int PIECE_LOCK_CLASS = 0x52505043;   // "RPPC"
    private static final Duration CLOCK_ALLOWANCE = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final InventoryRepository inventories;
    private final StockLedger ledger;
    private final Clock clock;

    public ReturnedPieceService(JdbcTemplate jdbc, InventoryRepository inventories, StockLedger ledger, Clock clock) {
        this.jdbc = jdbc;
        this.inventories = inventories;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * @param outcome        {@code RELEASED}, {@code WRITTEN_OFF} or {@code RELISTED}
     * @param childVariantId only for {@code RELISTED}: the marked-down listing the piece moves to
     * @param occurredAt     when it was decided; null means now; never before the piece came back, never in the future
     * @throws CommitmentProblem 404 {@code returned-piece-not-found}; 409 {@code piece-outcome-recorded}; 400 {@code invalid-occurred-at}
     * @throws IllegalArgumentException the outcome is not one of the three, or a listing is named for an outcome that has none
     */
    @Transactional
    public Decision decide(UUID pieceId, String outcome, UUID childVariantId, Instant occurredAt, String actor) {
        if (pieceId == null || outcome == null) {
            throw new IllegalArgumentException("a decision names the piece and what becomes of it");
        }
        if (!List.of("RELEASED", "WRITTEN_OFF", "RELISTED").contains(outcome)) {
            throw new IllegalArgumentException("outcome must be RELEASED, WRITTEN_OFF or RELISTED");
        }
        if ("RELISTED".equals(outcome) != (childVariantId != null)) {
            throw new IllegalArgumentException("a marked-down listing is named for a piece that is relisted, and for no other");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(?, hashtext(?))", (RowCallbackHandler) rs -> { }, PIECE_LOCK_CLASS, pieceId.toString());
        Waiting piece = jdbc.query("SELECT piece_id, variant_id, restored_at, outcome, child_variant_id, outcome_at FROM returned_piece WHERE piece_id = ?",
                (rs, n) -> new Waiting(rs.getObject("piece_id", UUID.class), rs.getObject("variant_id", UUID.class), rs.getTimestamp("restored_at").toInstant(),
                        rs.getString("outcome"), rs.getObject("child_variant_id", UUID.class),
                        rs.getTimestamp("outcome_at") == null ? null : rs.getTimestamp("outcome_at").toInstant()), pieceId)
                .stream().findFirst().orElseThrow(() -> new CommitmentProblem(HttpStatus.NOT_FOUND, "returned-piece-not-found",
                        "no piece " + pieceId + " has been put back into stock"));
        if (piece.outcome() != null) {
            if (!piece.outcome().equals(outcome) || !Objects.equals(piece.childVariantId(), childVariantId)) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "piece-outcome-recorded", "piece " + pieceId + " was already decided: " + piece.outcome())
                        .with("outcome", piece.outcome());
            }
            return new Decision(pieceId, piece.outcome(), piece.variantId(), piece.childVariantId(), piece.outcomeAt(), true);
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant when = occurredAt == null ? now : occurredAt.truncatedTo(ChronoUnit.MICROS);
        if (when.isAfter(now.plus(CLOCK_ALLOWANCE))) {
            throw new CommitmentProblem(HttpStatus.BAD_REQUEST, "invalid-occurred-at", "occurredAt " + when + " is in the future");
        }
        if (when.isBefore(piece.restoredAt())) {
            if (occurredAt != null) {
                throw new CommitmentProblem(HttpStatus.BAD_REQUEST, "invalid-occurred-at", "occurredAt " + when + " is before the piece came back ("
                        + piece.restoredAt() + ")");
            }
            // No date was given, so "now": a piece put back a little ahead of this clock is decided at the moment it came back, never before it.
            when = piece.restoredAt();
        }

        UUID variantId = piece.variantId();
        java.util.TreeSet<UUID> toLock = new java.util.TreeSet<>(List.of(variantId));
        if (childVariantId != null) {
            // Everything that changes the stock of either variant waits here, the listing's too: its stock row does not exist yet, so only this
            // can keep two pieces from being moved onto one listing at once.
            ledger.lockVariant(variantId);
            ledger.lockVariant(childVariantId);
            toLock.add(childVariantId);
        }
        java.util.Map<UUID, Inventory> rows = new java.util.HashMap<>();
        inventories.findAllForUpdate(toLock).forEach(found -> rows.put(found.variantId(), found));
        Inventory row = rows.get(variantId);
        if (row == null) {
            throw new IllegalStateException("the stock row of variant " + variantId + " is gone while piece " + pieceId + " waits in it");
        }
        UUID actionId = switch (outcome) {
            case "RELEASED" -> release(piece, row, when, actor);
            case "WRITTEN_OFF" -> writeOff(piece, row, when, actor);
            default -> relist(piece, row, childVariantId, rows.get(childVariantId), when, now, actor);
        };
        inventories.saveAndFlush(row);
        ledger.conform(variantId, actor);
        ledger.verify(variantId, row.onHand());
        jdbc.update("UPDATE returned_piece SET outcome = ?, outcome_action_id = ?, child_variant_id = ?, outcome_at = ?, outcome_actor = ? WHERE piece_id = ?",
                outcome, actionId, childVariantId, Timestamp.from(when), actor, pieceId);
        log.info("Returned piece {} of {} decided: {} (dated {})", pieceId, variantId, outcome, when);
        return new Decision(pieceId, outcome, variantId, childVariantId, when, false);
    }

    /** Fine: ordinary stock again. The action is recorded so the ledger knows the unit is no longer held; it writes no movement. */
    private UUID release(Waiting piece, Inventory row, Instant when, String actor) {
        UUID actionId = named("piece-release:" + piece.pieceId());
        row.releaseQuarantine();
        ledger.insertAction(actionId, piece.variantId(), "RELEASE", StockReason.RETURN, 1, when, actor, null, null, piece.pieceId(), null);
        return actionId;
    }

    /** Not sellable: out of the lot it went back to, at that lot's cost. */
    private UUID writeOff(Waiting piece, Inventory row, Instant when, String actor) {
        UUID actionId = named("piece-exit:" + piece.pieceId());
        row.exitQuarantined();
        ledger.insertAction(actionId, piece.variantId(), "RETURN_EXIT", StockReason.WRITE_OFF_RETURNED, 1, when, actor, null, null, piece.pieceId(), null);
        return actionId;
    }

    /**
     * Marked down: out of the lot it went back to and into a one-piece lot of its own listing, at exactly the cost it had. Nothing is
     * recognised: the piece only changed shelf.
     *
     * @throws CommitmentProblem 409 {@code markdown-variant-not-known-yet} (catalog's snapshot of the listing has not arrived: try again),
     *                           {@code markdown-variant-of-another-product}, {@code markdown-variant-in-use}, {@code already-marked-down}
     */
    private UUID relist(Waiting piece, Inventory row, UUID childVariantId, Inventory childRow, Instant when, Instant now, String actor) {
        UUID source = piece.variantId();
        // What catalog has told inventory about the two variants. Judged before anything is written: a refusal means nothing moved.
        List<java.util.Map<String, Object>> listing = jdbc.queryForList("SELECT product_id, title, sku FROM product_index WHERE variant_id = ?", childVariantId);
        if (listing.isEmpty() || listing.get(0).get("sku") == null) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "markdown-variant-not-known-yet", "inventory has not heard of the marked-down listing "
                    + childVariantId + " yet: it learns of it from catalog a moment after it is made");
        }
        List<UUID> ofProduct = jdbc.queryForList("SELECT product_id FROM product_index WHERE variant_id = ?", UUID.class, source);
        if (ofProduct.isEmpty()) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "markdown-variant-not-known-yet", "inventory has not heard of variant " + source + " from catalog yet");
        }
        if (!ofProduct.get(0).equals(listing.get(0).get("product_id"))) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "markdown-variant-of-another-product", "the listing " + childVariantId
                    + " belongs to another product than the piece");
        }
        if (childRow != null || ledger.heldQuantity(childVariantId) > 0
                || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM cost_lot WHERE variant_id = ?)", Boolean.class, childVariantId))) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "markdown-variant-in-use", "the listing " + childVariantId
                    + " already holds stock: a marked-down listing is made for one piece");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM markdown_relist WHERE child_variant_id = ?)", Boolean.class, source))) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "already-marked-down", "this piece is already a marked-down piece: it is put back on sale as it is, "
                    + "or written off, never marked down a second time");
        }

        // 1. Out of the lot it went back to.
        UUID exitId = named("piece-exit:" + piece.pieceId());
        row.exitQuarantined();
        inventories.saveAndFlush(row);
        ledger.insertAction(exitId, source, "RETURN_EXIT", StockReason.RELISTED_MARKED_DOWN, 1, when, actor, null, null, piece.pieceId(), null);
        ledger.conform(source, actor);
        StockLedger.Exit exit = ledger.exitOf(exitId);
        StockLedger.LotIdentity from = ledger.lot(exit.lotId());

        // 2. Into a one-piece lot of its own listing, at exactly that cost. It keeps the delivery line it arrived on (the line still accounts
        //    for it), and takes its name and SKU from the listing.
        UUID inId = named("relist-in:" + piece.pieceId());
        UUID lotId = UUID.randomUUID();
        Inventory child = Inventory.open(childVariantId, "SELLABLE");
        child.receive(1);
        inventories.saveAndFlush(child);
        ledger.insertChildLot(new StockLedger.NewLot(lotId, childVariantId, "SELLABLE", (String) listing.get(0).get("sku"), from.productTitle(),
                (String) listing.get(0).get("title"), from.consignmentId(), from.consignmentReference(), from.consignmentLineId(), from.supplierId(),
                from.supplierName(), from.receivedAt(), from.fifoTiebreak(), from.slicePosition(), 1, 1, 1, exit.unitCost(), exit.estimated()), from.id(), exitId);
        ledger.insertAction(inId, childVariantId, "RELIST_IN", StockReason.RELISTED_MARKED_DOWN, 1, when, actor, null, null, null, null);
        ledger.insertMovement(childVariantId, inId, lotId, StockReason.RELISTED_MARKED_DOWN, "ORIGINAL", 1, exit.unitCost(), exit.estimated(), "SELLABLE", null,
                null, when, actor);
        jdbc.update("""
                INSERT INTO markdown_relist (piece_id, source_variant_id, child_variant_id, exit_action_id, in_action_id, occurred_at, recorded_at, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                piece.pieceId(), source, childVariantId, exitId, inId, Timestamp.from(when), Timestamp.from(now), actor);
        ledger.verify(childVariantId, child.onHand());
        return exitId;
    }

    private static UUID named(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
