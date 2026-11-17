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
        Inventory row = inventories.findAllForUpdate(List.of(variantId)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("the stock row of variant " + variantId + " is gone while piece " + pieceId + " waits in it"));
        UUID actionId = switch (outcome) {
            case "RELEASED" -> release(piece, row, when, actor);
            case "WRITTEN_OFF" -> writeOff(piece, row, when, actor);
            default -> throw new IllegalArgumentException("a piece cannot be relisted yet");
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

    private static UUID named(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
