package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventDraft;
import com.oriah.events.OutboxWriter;
import com.oriah.inventory.events.ReservationEvents;
import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * A box changed after the order was paid (Phase 7). One transaction, keyed by the amendment id the order system mints, so a retry changes
 * nothing twice:
 *
 * <ol>
 *   <li>the original box's sale is REVERSED: a take-back of that sale (reason {@code BOX_SWAPPED}) returns the boxes to the lots they came
 *       out of, at the cost they left at;</li>
 *   <li>the replacement is SOLD: oldest lot first, from the product's set-aside if that is where the replacement box is promised to it,
 *       from free boxes otherwise.</li>
 * </ol>
 *
 * Both are dated to the day of the SALE ({@code occurred_at}) and recorded now, so the sale's date never moves and its month's packaging
 * cost moves by exactly the difference. Both rows say so in the ledger, {@code box_swap} links the two actions for the profit report, and
 * {@code box.swapped} announces what each side cost.
 *
 * <p>This is its own primitive. A write-off's take-back ({@link StockService#takeBack}) still refuses a sale: only a swap may unwind one, and
 * only a box's. Boxes go back to FREE, not to the product's promise ("a promise that was cut is not remade").
 *
 * <p>Locks, in the order the reservation service uses: the stock rows in variant-id order, then the product's promise; the outbox last.
 */
@Service
@Slf4j
public class BoxSwapService {

    /** A swap as recorded. {@code replay}: this was a repeat of one already made. */
    public record Swap(UUID amendmentId, UUID orderId, UUID productId, UUID originalSaleLineId, UUID replacementSaleLineId, UUID replacementBoxVariantId,
                       int quantity, Instant occurredAt, Instant recordedAt, String reason, boolean replay) {
    }

    private final JdbcTemplate jdbc;
    private final InventoryRepository inventories;
    private final StockLedger ledger;
    private final StockService stock;
    private final BoxCommitmentService commitments;
    private final OutboxWriter outbox;
    private final Clock clock;

    public BoxSwapService(JdbcTemplate jdbc, InventoryRepository inventories, StockLedger ledger, StockService stock, BoxCommitmentService commitments,
                          OutboxWriter outbox, Clock clock) {
        this.jdbc = jdbc;
        this.inventories = inventories;
        this.ledger = ledger;
        this.stock = stock;
        this.commitments = commitments;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * @param originalSaleLineId    the sale line (stock action) the original box was sold under
     * @param replacementSaleLineId the sale line the replacement is sold under; the order system mints it
     * @return the swap; asking again with the same amendment id and the same request answers it ({@code replay}) and changes nothing
     * @throws CommitmentProblem 404 {@code sale-line-not-found}; 409 {@code amendment-id-used}, {@code not-a-box-sale}, {@code same-box},
     *                           {@code already-swapped}, {@code not-packaging}, {@code insufficient-stock}
     * @throws IllegalArgumentException the reason is not 10 to 200 characters
     */
    @Transactional
    public Swap swap(UUID amendmentId, UUID orderId, UUID productId, UUID originalSaleLineId, UUID replacementSaleLineId, UUID replacementBoxVariantId,
                     String reason, String actor) {
        if (amendmentId == null || orderId == null || productId == null || originalSaleLineId == null || replacementSaleLineId == null
                || replacementBoxVariantId == null) {
            throw new IllegalArgumentException("a swap needs its amendment id, order, product, both sale lines and the replacement box");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 10 || why.length() > 200) {
            throw new IllegalArgumentException("a box swap needs a reason of 10 to 200 characters");
        }
        Optional<Swap> recorded = recorded(amendmentId);
        if (recorded.isPresent()) {
            Swap was = recorded.get();
            if (!was.orderId().equals(orderId) || !was.productId().equals(productId) || !was.originalSaleLineId().equals(originalSaleLineId)
                    || !was.replacementSaleLineId().equals(replacementSaleLineId) || !was.replacementBoxVariantId().equals(replacementBoxVariantId)
                    || !was.reason().equals(why)) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "amendment-id-used", "amendment " + amendmentId + " was already used for a different swap");
            }
            return new Swap(was.amendmentId(), was.orderId(), was.productId(), was.originalSaleLineId(), was.replacementSaleLineId(),
                    was.replacementBoxVariantId(), was.quantity(), was.occurredAt(), was.recordedAt(), was.reason(), true);
        }

        StockLedger.ActionRow original = ledger.findAction(originalSaleLineId).orElseThrow(() -> new CommitmentProblem(HttpStatus.NOT_FOUND,
                "sale-line-not-found", "there is no sale line " + originalSaleLineId));
        Inventory originalProbe = inventories.findById(original.variantId()).orElse(null);
        if (!"SALE".equals(original.kind()) || originalProbe == null || !"PACKAGING".equals(originalProbe.kind())) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "not-a-box-sale", "sale line " + originalSaleLineId + " is not the sale of a box: only a box can be swapped");
        }
        if (original.variantId().equals(replacementBoxVariantId)) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "same-box", "the replacement is the box that was sold: nothing to swap");
        }
        Integer reversed = jdbc.queryForObject("SELECT count(*) FROM stock_action WHERE reverses_action_id = ?", Integer.class, originalSaleLineId);
        if (reversed != null && reversed > 0) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "already-swapped", "the box of sale line " + originalSaleLineId + " has already been swapped");
        }

        int qty = original.qty();
        Instant when = original.occurredAt();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Map<UUID, Inventory> rows = new LinkedHashMap<>();
        for (Inventory row : inventories.findAllForUpdate(new TreeSet<>(List.of(original.variantId(), replacementBoxVariantId)))) {
            rows.put(row.variantId(), row);
        }
        Inventory originalRow = rows.get(original.variantId());
        Inventory replacementRow = rows.get(replacementBoxVariantId);
        if (replacementRow != null && !"PACKAGING".equals(replacementRow.kind())) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "not-packaging", "variant " + replacementBoxVariantId + " is not packaging and cannot be a box");
        }
        Optional<BoxCommitmentService.Commitment> promise = commitments.lockPromise(productId).filter(c -> c.boxVariantId().equals(replacementBoxVariantId));
        int unheld = promise.map(c -> c.quantity() - c.held()).orElse(0);
        boolean fromPromise = unheld >= qty;
        int free = replacementRow == null ? 0 : replacementRow.free();
        if (!fromPromise && qty > free) {
            List<Map<String, Object>> shortages = new ArrayList<>();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variantId", replacementBoxVariantId);
            m.put("kind", "BOX");
            m.put("requested", qty);
            m.put("available", free);
            shortages.add(m);
            throw new CommitmentProblem(HttpStatus.CONFLICT, "insufficient-stock", "the replacement box is short: " + qty + " wanted, " + free + " free")
                    .with("shortages", shortages);
        }

        // 1. The original box's sale is reversed: its boxes go back to the lots they came out of, at the cost they left at.
        UUID reversalId = UUID.nameUUIDFromBytes(("box-swap-reversal:" + amendmentId).getBytes(StandardCharsets.UTF_8));
        originalRow.takeBack(qty);
        inventories.saveAndFlush(originalRow);
        ledger.insertAction(reversalId, original.variantId(), "TAKE_BACK", StockReason.BOX_SWAPPED, qty, when, actor, "box swap " + amendmentId, originalSaleLineId,
                "swap:" + amendmentId);
        ledger.conform(original.variantId(), actor);
        ledger.verify(original.variantId(), originalRow.onHand());

        // 2. The replacement is sold: from the product's set-aside if it is promised there, from free boxes otherwise.
        if (fromPromise) {
            stock.consumeCommitted(productId, replacementBoxVariantId, qty, replacementSaleLineId, when, actor)
                    .orElseThrow(() -> new IllegalStateException("the replacement box " + replacementBoxVariantId + " has no stock row"));
        } else {
            stock.consumeFromFree(replacementBoxVariantId, qty, replacementSaleLineId, when, actor)
                    .orElseThrow(() -> new CommitmentProblem(HttpStatus.CONFLICT, "insufficient-stock", "the replacement box has no stock").with("available", 0));
        }

        jdbc.update("""
                INSERT INTO box_swap (amendment_id, order_id, product_id, original_action_id, replacement_action_id, quantity, reason, occurred_at, recorded_at, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                amendmentId, orderId, productId, originalSaleLineId, replacementSaleLineId, qty, why, Timestamp.from(when), Timestamp.from(now), actor);
        commitments.verifyBox(original.variantId());
        commitments.verifyBox(replacementBoxVariantId);

        // Announced last, in this transaction.
        outbox.append(new EventDraft(ReservationEvents.SWAP_AGGREGATE, amendmentId.toString(), ReservationEvents.BOX_SWAPPED, ReservationEvents.VERSION,
                ReservationEvents.TOPIC, when, actor,
                new ReservationEvents.BoxSwapped(amendmentId, orderId, productId, qty, "ZMW",
                        new ReservationEvents.BoxSwapped.Side(originalSaleLineId, original.variantId(), restoredCost(reversalId)),
                        new ReservationEvents.BoxSwapped.Side(replacementSaleLineId, replacementBoxVariantId, drainedCost(replacementSaleLineId)),
                        why, now)));
        log.info("Box swapped for order {}: {} x {} -> {} (amendment {}, dated {})", orderId, qty, original.variantId(), replacementBoxVariantId, amendmentId, when);
        return new Swap(amendmentId, orderId, productId, originalSaleLineId, replacementSaleLineId, replacementBoxVariantId, qty, when, now, why, false);
    }

    private Optional<Swap> recorded(UUID amendmentId) {
        return jdbc.query("""
                SELECT amendment_id, order_id, product_id, original_action_id, replacement_action_id, quantity, reason, occurred_at, recorded_at,
                       (SELECT variant_id FROM stock_action WHERE id = replacement_action_id) AS replacement_variant_id
                  FROM box_swap WHERE amendment_id = ?""", this::swap, amendmentId).stream().findFirst();
    }

    private Swap swap(ResultSet rs, int row) throws SQLException {
        return new Swap(rs.getObject("amendment_id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("original_action_id", UUID.class), rs.getObject("replacement_action_id", UUID.class),
                rs.getObject("replacement_variant_id", UUID.class), rs.getInt("quantity"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(), rs.getString("reason"), false);
    }

    /** What the boxes handed back had cost: the live rows the reversal restored, at the cost each lot gave them. */
    private BigDecimal restoredCost(UUID reversalId) {
        BigDecimal cost = jdbc.queryForObject("SELECT coalesce(sum(qty_delta * unit_cost), 0) FROM live_movement WHERE action_id = ?", BigDecimal.class, reversalId);
        return cost == null ? BigDecimal.ZERO.setScale(2) : cost.setScale(2);
    }

    /** What the replacement boxes cost: the live rows the sale drained. */
    private BigDecimal drainedCost(UUID saleLineId) {
        BigDecimal cost = jdbc.queryForObject("SELECT coalesce(sum(-qty_delta * unit_cost), 0) FROM live_movement WHERE action_id = ?", BigDecimal.class, saleLineId);
        return cost == null ? BigDecimal.ZERO.setScale(2) : cost.setScale(2);
    }
}
