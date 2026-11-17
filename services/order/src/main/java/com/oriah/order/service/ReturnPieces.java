package com.oriah.order.service;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.service.Corrections.Correction;
import com.oriah.order.service.Corrections.Piece;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * The database halves of assessing a piece that came back (Phase 12, plan 4.6), each one short local transaction: {@link #decide} writes the decision
 * down (IN_PROGRESS) under the order's lock, {@link #done} marks it DONE once inventory has recorded it, and {@link #backToAwaiting} takes the decision
 * back, whole, when inventory proves it did not record it. {@link AssessmentService} asks catalog and inventory around them, outside any transaction.
 *
 * <p>Locks, in the order every correction takes them: the order's row, the return's row, then the piece's row.
 */
@Service
@Slf4j
public class ReturnPieces {

    public static final String AWAITING = "AWAITING";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String DONE = "DONE";

    /** A piece with what is needed to act on it: the return it belongs to and the sale line it was sold under. */
    public record Found(Piece piece, Correction ret, UUID productId, UUID variantId) {
    }

    private final JdbcTemplate jdbc;
    private final Corrections corrections;
    private final OrderLocks locks;

    public ReturnPieces(JdbcTemplate jdbc, Corrections corrections, OrderLocks locks) {
        this.jdbc = jdbc;
        this.corrections = corrections;
        this.locks = locks;
    }

    /**
     * The piece as it stands, with no lock: enough to refuse early and to know what to ask catalog. Everything is judged again under the locks.
     *
     * @throws ResourceNotFoundException 404: no such return, or the piece is not a piece of it
     */
    public Found find(UUID returnId, UUID pieceId) {
        Correction ret = corrections.find(returnId).filter(c -> Corrections.RETURN.equals(c.kind()))
                .orElseThrow(() -> new ResourceNotFoundException("Return", "id", returnId.toString()));
        Piece piece = corrections.pieces(returnId).stream().filter(p -> p.id().equals(pieceId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Returned piece", "id", pieceId.toString()));
        Corrections.Line line = corrections.lines(returnId).stream().filter(l -> l.saleLineId().equals(piece.saleLineId())).findFirst().orElseThrow();
        return new Found(piece, ret, line.productId(), line.variantId());
    }

    /** The piece by its id alone (the job knows no return). */
    public Found find(UUID pieceId) {
        UUID returnId = jdbc.queryForList("SELECT correction_id FROM order_return_piece WHERE id = ?", UUID.class, pieceId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Returned piece", "id", pieceId.toString()));
        return find(returnId, pieceId);
    }

    /**
     * What may be said of this piece now.
     *
     * @return true when the piece is already DONE with this same outcome (a replay); false when it may be decided or carried on
     * @throws SalesProblem 409 {@code return-not-received-yet}, {@code piece-already-assessed}, {@code assessment-in-progress}
     */
    static boolean judge(Found found, String outcome) {
        if (!Corrections.DONE.equals(found.ret().state())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "return-not-received-yet", "this return is " + found.ret().state() + ": a piece is looked at once the "
                    + "stock system has taken it back").with("returnState", found.ret().state());
        }
        Piece piece = found.piece();
        if (DONE.equals(piece.state())) {
            if (outcome.equals(piece.outcome())) {
                return true;
            }
            throw new SalesProblem(HttpStatus.CONFLICT, "piece-already-assessed", "this piece was already assessed as " + piece.outcome()
                    + ": an assessment is made once").with("outcome", piece.outcome());
        }
        if (IN_PROGRESS.equals(piece.state()) && !outcome.equals(piece.outcome())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "assessment-in-progress", "this piece was assessed as " + piece.outcome() + " and the stock system has "
                    + "not recorded it yet: repeat that assessment to finish it (it is also retried by itself)").with("outcome", piece.outcome());
        }
        return false;
    }

    /**
     * Writes the decision down, whole (the outcome, who, when, the note and the marked-down listing), and marks the piece IN_PROGRESS. A piece already
     * IN_PROGRESS with this same outcome is returned as it stands: the assessment carries on.
     *
     * @param markdownVariantId the listing catalog made for the piece; only for MARKED_DOWN
     * @throws SalesProblem as {@link #judge}
     */
    @Transactional
    public Found decide(UUID returnId, UUID pieceId, String outcome, String note, UUID markdownVariantId, String actor) {
        locks.lock(corrections.orderOf(returnId));
        corrections.lock(returnId);
        jdbc.queryForList("SELECT id FROM order_return_piece WHERE id = ? AND correction_id = ? FOR UPDATE", UUID.class, pieceId, returnId);
        Found found = find(returnId, pieceId);
        if (judge(found, outcome) || IN_PROGRESS.equals(found.piece().state())) {
            return found;
        }
        // Never dated before the parcel came back (a return may be typed a moment ahead of this clock): inventory holds the same rule.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant askedAt = now.isBefore(found.ret().occurredAt()) ? found.ret().occurredAt() : now;
        jdbc.update("""
                UPDATE order_return_piece SET state = 'IN_PROGRESS', outcome = ?, note = ?, markdown_variant_id = ?, asked_by = ?, asked_at = ?,
                    attempts = 0, next_attempt_at = NULL
                 WHERE id = ? AND state = 'AWAITING'""", outcome, note, markdownVariantId, actor, Timestamp.from(askedAt), pieceId);
        log.info("Return {}: piece {} assessed as {} by {}; asking inventory", returnId, pieceId, outcome, actor);
        return find(returnId, pieceId);
    }

    /** Inventory has recorded the outcome: the piece is DONE. Idempotent. */
    @Transactional
    public Found done(UUID pieceId) {
        jdbc.update("UPDATE order_return_piece SET state = 'DONE', assessed_at = ?, next_attempt_at = NULL WHERE id = ? AND state = 'IN_PROGRESS'",
                Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)), pieceId);
        return find(pieceId);
    }

    /** Inventory proved it did not record the outcome: the decision is taken back, whole, and the piece waits again. Idempotent. */
    @Transactional
    public void backToAwaiting(UUID pieceId) {
        jdbc.update("""
                UPDATE order_return_piece SET state = 'AWAITING', outcome = NULL, note = NULL, markdown_variant_id = NULL, asked_by = NULL, asked_at = NULL,
                    attempts = 0, next_attempt_at = NULL
                 WHERE id = ? AND state = 'IN_PROGRESS'""", pieceId);
    }

    /** Inventory's answer settled nothing: the piece stays IN_PROGRESS, to be asked again after a wait that doubles from 15 seconds to five minutes. */
    @Transactional
    public Found retryLater(UUID pieceId) {
        Found found = find(pieceId);
        jdbc.update("UPDATE order_return_piece SET attempts = attempts + 1, next_attempt_at = ? WHERE id = ? AND state = 'IN_PROGRESS'",
                Timestamp.from(Instant.now().plus(CorrectionReplies.backoff(found.piece().attempts())).truncatedTo(ChronoUnit.MICROS)), pieceId);
        return find(pieceId);
    }

    /** The pieces whose decision inventory has not recorded and whose time to ask again has come, the longest waiting first. */
    public List<UUID> due(Instant now, Instant neverAskedBefore, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM order_return_piece
                 WHERE state = 'IN_PROGRESS'
                   AND ((next_attempt_at IS NOT NULL AND next_attempt_at <= ?) OR (next_attempt_at IS NULL AND asked_at <= ?))
                 ORDER BY asked_at LIMIT ?""", UUID.class, Timestamp.from(now), Timestamp.from(neverAskedBefore), limit);
    }
}
