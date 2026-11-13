package com.oriah.order.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderItem;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The database halves of a box swap (Phase 7), each one short local transaction: {@link #plan} decides whether the swap may be asked of inventory (or
 * recognises one already made), and {@link #record} writes the amendment and puts the new box on the line once inventory has done the stock.
 * {@link BoxSwapService} asks inventory between them, outside any transaction.
 */
@Service
@Slf4j
public class BoxSwaps {

    /** What may be asked of inventory: the order's line as it stands, and the swap that would change its box. */
    public record Plan(UUID amendmentId, UUID orderId, UUID itemId, UUID productId, UUID oldBoxVariantId, UUID oldBoxLineId, UUID newBoxVariantId,
                       int quantity, String reason, Instant paidAt) {
    }

    /** A swap as recorded. {@code replay}: this was a repeat of one already made. */
    public record Swap(UUID amendmentId, UUID orderId, UUID lineId, UUID productId, UUID oldBoxVariantId, UUID newBoxVariantId, int quantity, String reason,
                       String amendedBy, Instant occurredAt, Instant recordedAt, boolean replay) {
    }

    /** What {@link #plan} found: a swap to ask inventory for, or one already recorded. */
    public record Planned(Plan plan, Swap recorded) {
    }

    private final OrderRepository repository;
    private final JdbcTemplate jdbc;

    public BoxSwaps(OrderRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /**
     * @throws ResourceNotFoundException the order or the line does not exist
     * @throws SalesProblem 409: {@code amendment-id-used} (that id was used for a different swap), {@code box-swap-not-allowed} (the order is not PAID,
     *                      or has been fulfilled), {@code payment-being-applied}, {@code no-box-on-line}, {@code same-box}
     */
    @Transactional(readOnly = true)
    public Planned plan(UUID orderId, UUID lineId, UUID newBoxVariantId, String reason, UUID requestedAmendmentId) {
        Order order = repository.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId.toString()));
        String why = reason.trim();
        UUID amendmentId = requestedAmendmentId != null ? requestedAmendmentId : derivedAmendmentId(order, lineId, newBoxVariantId);
        Swap recorded = find(amendmentId);
        if (recorded != null) {
            if (!recorded.orderId().equals(orderId) || !recorded.lineId().equals(lineId) || !recorded.newBoxVariantId().equals(newBoxVariantId)
                    || !recorded.reason().equals(why)) {
                throw new SalesProblem(HttpStatus.CONFLICT, "amendment-id-used", "amendment " + amendmentId + " was already used for a different swap");
            }
            return new Planned(null, new Swap(recorded.amendmentId(), recorded.orderId(), recorded.lineId(), recorded.productId(), recorded.oldBoxVariantId(),
                    recorded.newBoxVariantId(), recorded.quantity(), recorded.reason(), recorded.amendedBy(), recorded.occurredAt(), recorded.recordedAt(), true));
        }
        if (order.settlingConfirmationId() != null) {
            throw new SalesProblem(HttpStatus.CONFLICT, "payment-being-applied", "a confirmed payment is being applied to this order right now: try again in a moment");
        }
        if (order.status() != OrderStatus.PAID) {
            throw new SalesProblem(HttpStatus.CONFLICT, "box-swap-not-allowed",
                    "only a paid order that has not been fulfilled can change its box; this one is " + order.status());
        }
        OrderItem line = order.items().stream().filter(i -> i.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Order line", "id", lineId.toString()));
        if (line.boxVariantId() == null || line.boxLineId() == null || line.productId() == null) {
            throw new SalesProblem(HttpStatus.CONFLICT, "no-box-on-line", "this line has no box recorded to swap");
        }
        if (line.boxVariantId().equals(newBoxVariantId)) {
            throw new SalesProblem(HttpStatus.CONFLICT, "same-box", "the line already goes out in that box");
        }
        return new Planned(new Plan(amendmentId, orderId, lineId, line.productId(), line.boxVariantId(), line.boxLineId(), newBoxVariantId, line.quantity(), why,
                order.paidAt()), null);
    }

    /**
     * The id of a swap whose caller sent none: worked out from the line AS IT STANDS (the sale line its box is sold under now) and the box asked for, so a
     * blind retry after a lost reply -- inventory swapped, order never heard, the caller never learned a minted id -- asks inventory for exactly the same
     * swap and records it. A swap that has been recorded changes the line's box-sale line, so the same change asked later is another swap with its own id.
     */
    private static UUID derivedAmendmentId(Order order, UUID lineId, UUID newBoxVariantId) {
        return order.items().stream().filter(i -> i.id().equals(lineId)).findFirst()
                .map(line -> UUID.nameUUIDFromBytes(("order-box-swap-request:" + order.id() + ":" + lineId + ":" + line.boxLineId() + ":" + newBoxVariantId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .orElseGet(UUID::randomUUID);
    }

    /**
     * Inventory has done the stock: the amendment is written and the line carries the new box -- ONE transaction. Idempotent by the amendment id: a repeat
     * (a retry after a lost reply, or two of them at once) writes nothing more.
     */
    @Transactional
    public Swap record(Plan plan, UUID newBoxLineId, Instant occurredAt, String actor) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        int written = jdbc.update("""
                INSERT INTO order_box_amendment (id, order_id, item_id, product_id, old_box_variant_id, new_box_variant_id, old_box_line_id, new_box_line_id,
                                                 quantity, reason, amended_by, occurred_at, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING""", plan.amendmentId(), plan.orderId(), plan.itemId(), plan.productId(), plan.oldBoxVariantId(),
                plan.newBoxVariantId(), plan.oldBoxLineId(), newBoxLineId, plan.quantity(), plan.reason(), actor,
                Timestamp.from(occurredAt != null ? occurredAt : plan.paidAt()), Timestamp.from(now));
        if (written == 1) {
            jdbc.update("UPDATE order_items SET box_variant_id = ?, box_line_id = ? WHERE id = ?", plan.newBoxVariantId(), newBoxLineId, plan.itemId());
            log.info("Order {} line {}: box {} replaced by {} (amendment {}, by {})", plan.orderId(), plan.itemId(), plan.oldBoxVariantId(),
                    plan.newBoxVariantId(), plan.amendmentId(), actor);
        }
        return find(plan.amendmentId());
    }

    private Swap find(UUID amendmentId) {
        return jdbc.query("SELECT * FROM order_box_amendment WHERE id = ?", (rs, n) -> new Swap(rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getObject("item_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("old_box_variant_id", UUID.class), rs.getObject("new_box_variant_id", UUID.class), rs.getInt("quantity"), rs.getString("reason"),
                rs.getString("amended_by"), rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("recorded_at").toInstant(), false), amendmentId)
                .stream().findFirst().orElse(null);
    }
}
