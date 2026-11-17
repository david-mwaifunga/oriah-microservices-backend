package com.oriah.inventory.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
 * A sale put back into stock (Phase 12). The order system asks once per correction, under an id it chose, and one transaction answers.
 *
 * <ul>
 *   <li><b>CANCELLATION</b>: a paid order cancelled before it left the shop. Its pieces and its boxes go back to the lots they came out of,
 *       at the cost they left at (a take-back of each sale line, reason {@code CANCELLED_BEFORE_DISPATCH}), and are ordinary stock again.
 *       Boxes that came out of a product's set-aside go back into it if it still stands; otherwise they are simply free.</li>
 *   <li><b>RETURN</b>: a parcel that came back. Each piece is its own take-back of ONE unit (reason {@code RETURN}; the action's id is the
 *       piece's id), put back into the newest lot its sale drained that has not been given back yet, at that lot's cost, and HELD there: on
 *       the shelf, counted, not for sale until somebody has looked at it. The box stays sold.</li>
 * </ul>
 *
 * Both are dated to the day of the correction ({@code occurredAt}), never to the day of the sale: the sale's own month does not move.
 * Every ledger row written here is announced to finance as a cost row naming the sale it reverses ({@code stock.cost_recorded}).
 *
 * <p><b>Asked once.</b> Everything is keyed by the reversal id, and one attempt per id runs at a time (an advisory lock taken before
 * anything is read, by the read too): the same request again answers what was done ({@code replay}), another request under that id is
 * refused, and a read that finds nothing PROVES nothing moved, because no attempt can be half-way while the read holds the lock.
 *
 * <p>Locks, in the order the reservation service uses: the reversal's own lock, the hold's row, the stock rows in variant-id order, then
 * the products' promises; the outbox last (the cost rows are announced just before commit).
 */
@Service
@Slf4j
public class SaleReversalService {

    /** Pieces of a cancelled order's line, back to ordinary stock. */
    public record GoodsLine(UUID saleLineId, UUID variantId, int quantity) {
    }

    /** The boxes of a cancelled order's line: the box line that is LIVE (after a swap, the replacement). */
    public record BoxLine(UUID boxLineId, UUID boxVariantId, UUID productId, int quantity) {
    }

    /** One piece that came back. {@code pieceId} is the order system's id for it and becomes the id of the movement that puts it back. */
    public record Piece(UUID pieceId, UUID saleLineId, UUID variantId) {
    }

    public record Request(UUID reversalId, String kind, UUID orderId, UUID reservationId, Instant occurredAt, List<GoodsLine> goods, List<BoxLine> boxes,
                          List<Piece> pieces) {
    }

    /** Where the boxes of one line went: back into the product's set-aside, or free. */
    public record BoxBack(UUID boxLineId, int toSetAside, int toFree) {
    }

    /** A reversal as recorded. {@code replay}: this was a repeat of one already made. */
    public record Reversal(UUID reversalId, String kind, UUID orderId, Instant occurredAt, Instant recordedAt, List<BoxBack> boxes, boolean replay) {
    }

    private record Stored(UUID id, String kind, UUID orderId, Instant occurredAt, Instant recordedAt, String digest) {
    }

    /** The advisory-lock class for "one sale reversal, by its id". Held to the end of the transaction. */
    private static final int REVERSAL_LOCK_CLASS = 0x53524556;   // "SREV"
    private static final Duration CLOCK_ALLOWANCE = Duration.ofMinutes(5);
    private static final String CANCELLATION = "CANCELLATION";
    private static final String RETURN = "RETURN";

    private final JdbcTemplate jdbc;
    private final InventoryRepository inventories;
    private final StockLedger ledger;
    private final BoxCommitmentService commitments;
    private final Clock clock;

    public SaleReversalService(JdbcTemplate jdbc, InventoryRepository inventories, StockLedger ledger, BoxCommitmentService commitments, Clock clock) {
        this.jdbc = jdbc;
        this.inventories = inventories;
        this.ledger = ledger;
        this.commitments = commitments;
        this.clock = clock;
    }

    /**
     * @return the reversal; the same request again under the same id answers it ({@code replay}) and changes nothing
     * @throws CommitmentProblem 404 {@code sale-line-not-found}, {@code reservation-not-found}; 409 {@code reversal-id-used},
     *                           {@code not-a-sale}, {@code reservation-not-consumed}, {@code box-line-not-live},
     *                           {@code more-than-outstanding}, {@code piece-id-used}, {@code reversal-abandoned}; 400 {@code invalid-occurred-at}
     * @throws IllegalArgumentException the request is not one of the two shapes
     */
    @Transactional
    public Reversal reverse(Request request, String actor) {
        requireShape(request);
        UUID id = request.reversalId();
        lock(id);
        String digest = digest(request);
        Optional<Stored> stored = stored(id);
        if (stored.isPresent()) {
            if (!stored.get().digest().equals(digest)) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "reversal-id-used", "reversal " + id + " was already used for a different request");
            }
            return view(stored.get(), true);
        }
        if (abandoned(id)) {
            // The order system gave this id up (it was told nothing had moved, and wrote that down): a request that was still on its way must not
            // move stock for an order that now carries on as paid.
            throw new CommitmentProblem(HttpStatus.CONFLICT, "reversal-abandoned", "reversal " + id + " was abandoned: nothing is done under it");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant when = request.occurredAt().truncatedTo(ChronoUnit.MICROS);
        if (when.isAfter(now.plus(CLOCK_ALLOWANCE))) {
            throw new CommitmentProblem(HttpStatus.BAD_REQUEST, "invalid-occurred-at", "occurredAt " + when + " is in the future");
        }
        boolean cancellation = CANCELLATION.equals(request.kind());
        if (cancellation) {
            requireConsumedHold(request);
        }

        // Every stock row this touches, locked in variant-id order BEFORE anything is judged: whatever else moves these variants waits here,
        // so what is still out cannot change between the check and the write.
        Set<UUID> variantIds = new TreeSet<>();
        request.goods().forEach(line -> variantIds.add(line.variantId()));
        request.boxes().forEach(line -> variantIds.add(line.boxVariantId()));
        request.pieces().forEach(piece -> variantIds.add(piece.variantId()));
        Map<UUID, Inventory> rows = new LinkedHashMap<>();
        for (Inventory row : inventories.findAllForUpdate(variantIds)) {
            rows.put(row.variantId(), row);
        }

        // Judge everything first: a refusal must mean nothing moved.
        Map<UUID, Integer> asked = new LinkedHashMap<>();
        Map<UUID, StockLedger.ActionRow> sales = new LinkedHashMap<>();
        for (GoodsLine line : request.goods()) {
            sales.put(line.saleLineId(), saleOf(line.saleLineId(), line.variantId(), "SELLABLE", request, rows, when));
            asked.merge(line.saleLineId(), line.quantity(), Integer::sum);
        }
        for (BoxLine line : request.boxes()) {
            sales.put(line.boxLineId(), saleOf(line.boxLineId(), line.boxVariantId(), "PACKAGING", request, rows, when));
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM box_swap WHERE original_action_id = ?)", Boolean.class, line.boxLineId()))) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "box-line-not-live", "the box of line " + line.boxLineId()
                        + " was swapped for another after payment: the box to put back is the one that went with the order");
            }
            asked.merge(line.boxLineId(), line.quantity(), Integer::sum);
        }
        for (Piece piece : request.pieces()) {
            sales.put(piece.saleLineId(), saleOf(piece.saleLineId(), piece.variantId(), "SELLABLE", request, rows, when));
            if (ledger.actionExists(piece.pieceId())) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "piece-id-used", "piece " + piece.pieceId() + " has already been put back");
            }
            asked.merge(piece.saleLineId(), 1, Integer::sum);
        }
        for (Map.Entry<UUID, Integer> line : asked.entrySet()) {
            int outstanding = sales.get(line.getKey()).qty() - ledger.takenBack(line.getKey());
            if (line.getValue() > outstanding) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "more-than-outstanding", "line " + line.getKey() + " has " + outstanding
                        + " still out and " + line.getValue() + " cannot come back").with("saleLineId", line.getKey()).with("outstanding", outstanding);
            }
        }

        // Nothing below refuses for a reason the caller could have known.
        jdbc.update("""
                INSERT INTO sale_reversal (id, kind, order_id, reservation_id, occurred_at, request_digest, recorded_at, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                id, request.kind(), request.orderId(), request.reservationId(), Timestamp.from(when), digest, Timestamp.from(now), actor);
        String mark = mark(id);
        for (GoodsLine line : request.goods()) {
            rows.get(line.variantId()).takeBack(line.quantity());
            ledger.insertAction(named("cancel-goods:" + id + ":" + line.saleLineId()), line.variantId(), "TAKE_BACK", StockReason.CANCELLED_BEFORE_DISPATCH,
                    line.quantity(), when, actor, "cancellation " + id, line.saleLineId(), mark);
        }
        for (BoxLine line : request.boxes()) {
            Inventory row = rows.get(line.boxVariantId());
            UUID actionId = named("cancel-box:" + id + ":" + line.boxLineId());
            row.takeBack(line.quantity());
            ledger.insertAction(actionId, line.boxVariantId(), "TAKE_BACK", StockReason.CANCELLED_BEFORE_DISPATCH, line.quantity(), when, actor,
                    "cancellation " + id, line.boxLineId(), mark);
            // Boxes that came out of the product's set-aside go back into it, if it still stands on this box.
            int fromSetAside = Math.min(line.quantity(), commitments.soldFromSetAside(line.boxLineId()));
            int back = fromSetAside <= 0 ? 0 : commitments.returnSold(line.productId(), line.boxVariantId(), fromSetAside, actionId, actor, when);
            if (back > 0) {
                row.commit(back);
            }
        }
        for (Piece piece : request.pieces()) {
            rows.get(piece.variantId()).takeBackToQuarantine();
            ledger.insertAction(piece.pieceId(), piece.variantId(), "TAKE_BACK", StockReason.RETURN, 1, when, actor, "return " + id, piece.saleLineId(), mark);
            jdbc.update("INSERT INTO returned_piece (piece_id, reversal_id, sale_line_id, variant_id, restored_at) VALUES (?, ?, ?, ?, ?)",
                    piece.pieceId(), id, piece.saleLineId(), piece.variantId(), Timestamp.from(when));
        }
        for (Inventory row : rows.values()) {
            inventories.saveAndFlush(row);
            ledger.conform(row.variantId(), actor);
            ledger.verify(row.variantId(), row.onHand());
            if ("PACKAGING".equals(row.kind())) {
                commitments.verifyBox(row.variantId());
            }
        }
        log.info("Sale reversed for order {}: {} {} ({} goods line(s), {} box line(s), {} piece(s), dated {})", request.orderId(), request.kind(), id,
                request.goods().size(), request.boxes().size(), request.pieces().size(), when);
        return view(new Stored(id, request.kind(), request.orderId(), when, now, digest), false);
    }

    /**
     * What was done under an id, or empty: nothing was. It waits for an attempt in flight under that id, so "empty" is proof.
     */
    @Transactional
    public Optional<Reversal> find(UUID reversalId) {
        lock(reversalId);
        return stored(reversalId).map(stored -> view(stored, false));
    }

    /** What became of an abandon: {@code applied} is true when the reversal had already been done (nothing is abandoned then). */
    public record Abandoned(UUID reversalId, boolean applied) {
    }

    /**
     * The order system gives a reversal id up. Under the reversal's own lock (so it waits out an attempt in flight): if the reversal was applied,
     * nothing is abandoned and the answer says so; otherwise the id is remembered as abandoned, and every later request under it is refused
     * ({@code reversal-abandoned}) and moves nothing. Asked again, the answer is the same.
     */
    @Transactional
    public Abandoned abandon(UUID reversalId, String actor) {
        lock(reversalId);
        if (stored(reversalId).isPresent()) {
            return new Abandoned(reversalId, true);
        }
        int written = jdbc.update("INSERT INTO sale_reversal_abandoned (id, abandoned_at, actor) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING", reversalId,
                java.sql.Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)), actor);
        if (written == 1) {
            log.warn("Reversal {} was abandoned by the order system before anything moved: no request under this id will be applied", reversalId);
        }
        return new Abandoned(reversalId, false);
    }

    private boolean abandoned(UUID reversalId) {
        return exists("SELECT EXISTS (SELECT 1 FROM sale_reversal_abandoned WHERE id = ?)", reversalId);
    }

    // ---------------------------------------------------------------- the rules
    private static void requireShape(Request request) {
        if (request == null || request.reversalId() == null || request.orderId() == null || request.occurredAt() == null || request.goods() == null
                || request.boxes() == null || request.pieces() == null) {
            throw new IllegalArgumentException("a reversal needs its id, its order, when it happened and what comes back");
        }
        if (CANCELLATION.equals(request.kind())) {
            if (request.reservationId() == null || request.goods().isEmpty() || !request.pieces().isEmpty()) {
                throw new IllegalArgumentException("a cancellation names the hold it undoes and the lines that go back, and no single pieces");
            }
        } else if (RETURN.equals(request.kind())) {
            if (request.reservationId() != null || request.pieces().isEmpty() || !request.goods().isEmpty() || !request.boxes().isEmpty()) {
                throw new IllegalArgumentException("a return names the pieces that came back, one by one, and nothing else: a box that went out stays sold");
            }
        } else {
            throw new IllegalArgumentException("kind must be CANCELLATION or RETURN");
        }
        Set<UUID> seen = new HashSet<>();
        for (GoodsLine line : request.goods()) {
            if (line == null || line.saleLineId() == null || line.variantId() == null || line.quantity() < 1 || !seen.add(line.saleLineId())) {
                throw new IllegalArgumentException("each line that goes back is named once, with its variant and a quantity of at least 1");
            }
        }
        for (BoxLine line : request.boxes()) {
            if (line == null || line.boxLineId() == null || line.boxVariantId() == null || line.productId() == null || line.quantity() < 1
                    || !seen.add(line.boxLineId())) {
                throw new IllegalArgumentException("each box line that goes back is named once, with its box, its product and a quantity of at least 1");
            }
        }
        for (Piece piece : request.pieces()) {
            if (piece == null || piece.pieceId() == null || piece.saleLineId() == null || piece.variantId() == null || !seen.add(piece.pieceId())) {
                throw new IllegalArgumentException("each piece that came back is named once, with its line and its variant");
            }
        }
    }

    /** The hold a cancellation undoes: this order's, and sold. Its row is locked, so nothing else can end it meanwhile. */
    private void requireConsumedHold(Request request) {
        List<Map<String, Object>> found = jdbc.queryForList("SELECT order_id, status FROM reservation WHERE id = ? FOR UPDATE", request.reservationId());
        if (found.isEmpty() || !request.orderId().equals(found.get(0).get("order_id"))) {
            throw new CommitmentProblem(HttpStatus.NOT_FOUND, "reservation-not-found", "order " + request.orderId() + " has no hold " + request.reservationId());
        }
        if (!"CONSUMED".equals(found.get(0).get("status"))) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "reservation-not-consumed", "hold " + request.reservationId() + " is "
                    + found.get(0).get("status") + ", not sold: there is no sale to cancel");
        }
    }

    /** The sale a line names: it exists, it is a sale of that variant and of that kind of thing, it is this order's, and it happened by {@code when}. */
    private StockLedger.ActionRow saleOf(UUID saleLineId, UUID variantId, String kind, Request request, Map<UUID, Inventory> rows, Instant when) {
        StockLedger.ActionRow sale = ledger.findAction(saleLineId).orElseThrow(() -> new CommitmentProblem(HttpStatus.NOT_FOUND, "sale-line-not-found",
                "there is no sale line " + saleLineId));
        Inventory row = rows.get(variantId);
        if (!"SALE".equals(sale.kind()) || !sale.variantId().equals(variantId) || row == null || !kind.equals(row.kind())) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "not-a-sale", "line " + saleLineId + " is not the sale of variant " + variantId
                    + ("PACKAGING".equals(kind) ? " as a box" : " as goods"));
        }
        boolean ofThisOrder = request.reservationId() != null
                ? exists("SELECT EXISTS (SELECT 1 FROM reservation_line WHERE reservation_id = ? AND sale_line_id = ?)", request.reservationId(), saleLineId)
                : exists("""
                        SELECT EXISTS (SELECT 1 FROM reservation_line l JOIN reservation r ON r.id = l.reservation_id
                                        WHERE r.order_id = ? AND l.sale_line_id = ?)""", request.orderId(), saleLineId);
        // A box swapped in after payment was never part of the hold: the swap says whose it is.
        if (!ofThisOrder && !exists("SELECT EXISTS (SELECT 1 FROM box_swap WHERE replacement_action_id = ? AND order_id = ?)", saleLineId, request.orderId())) {
            throw new CommitmentProblem(HttpStatus.NOT_FOUND, "sale-line-not-found", "order " + request.orderId() + " has no sale line " + saleLineId);
        }
        if (when.isBefore(sale.occurredAt())) {
            throw new CommitmentProblem(HttpStatus.BAD_REQUEST, "invalid-occurred-at", "occurredAt " + when + " is before the sale it reverses ("
                    + sale.occurredAt() + ")");
        }
        return sale;
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    // ---------------------------------------------------------------- plumbing

    /** One attempt per reversal at a time, the read included. */
    private void lock(UUID reversalId) {
        if (reversalId == null) {
            throw new IllegalArgumentException("a reversal id is required");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(?, hashtext(?))", (RowCallbackHandler) rs -> { }, REVERSAL_LOCK_CLASS, reversalId.toString());
    }

    private Optional<Stored> stored(UUID id) {
        return jdbc.query("SELECT id, kind, order_id, occurred_at, recorded_at, request_digest FROM sale_reversal WHERE id = ?",
                (rs, n) -> new Stored(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("order_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("recorded_at").toInstant(), rs.getString("request_digest")),
                id).stream().findFirst();
    }

    /** The answer, worked out from what is stored, so a repeat reads exactly what the first answer read. */
    private Reversal view(Stored stored, boolean replay) {
        List<BoxBack> boxes = new ArrayList<>();
        if (CANCELLATION.equals(stored.kind())) {
            String mark = mark(stored.id());
            jdbc.query("""
                    SELECT a.reverses_action_id, a.qty,
                           coalesce((SELECT sum(c.qty_delta) FROM box_commitment_change c WHERE c.action_id = a.id AND c.kind = 'RETURN'), 0) AS to_set_aside
                      FROM stock_action a JOIN inventory i ON i.variant_id = a.variant_id
                     WHERE a.request_digest = ? AND a.request_digest LIKE 'reversal:%' AND i.kind = 'PACKAGING'
                     ORDER BY a.seq""",
                    (RowCallbackHandler) rs -> boxes.add(new BoxBack(rs.getObject("reverses_action_id", UUID.class), rs.getInt("to_set_aside"),
                            rs.getInt("qty") - rs.getInt("to_set_aside"))),
                    mark);
        }
        return new Reversal(stored.id(), stored.kind(), stored.orderId(), stored.occurredAt(), stored.recordedAt(), List.copyOf(boxes), replay);
    }

    /** What every stock action of one reversal carries, so its movements can be found again. */
    private static String mark(UUID reversalId) {
        return "reversal:" + reversalId;
    }

    private static UUID named(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** A fingerprint of what was asked, whatever order the lines were sent in. */
    static String digest(Request request) {
        StringBuilder text = new StringBuilder("oriah-sale-reversal/1|").append(request.kind()).append('|').append(request.orderId()).append('|')
                .append(request.reservationId()).append('|').append(request.occurredAt().truncatedTo(ChronoUnit.MICROS));
        request.goods().stream().map(l -> "g:" + l.saleLineId() + ":" + l.variantId() + ":" + l.quantity()).sorted().forEach(l -> text.append('|').append(l));
        request.boxes().stream().map(l -> "b:" + l.boxLineId() + ":" + l.boxVariantId() + ":" + l.productId() + ":" + l.quantity()).sorted()
                .forEach(l -> text.append('|').append(l));
        request.pieces().stream().map(p -> "p:" + p.pieceId() + ":" + p.saleLineId() + ":" + p.variantId()).sorted().forEach(p -> text.append('|').append(p));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always there", e);
        }
    }
}
