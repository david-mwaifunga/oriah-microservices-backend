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

import lombok.extern.slf4j.Slf4j;

/**
 * The database halves of a box swap (Phase 7, reworked in Phase 8), each one short local transaction: {@link #plan} decides whether the swap may be asked
 * of inventory (or recognises one already made, or one already in flight), and {@link #record} writes the amendment and puts the new box on the line once
 * inventory has done the stock. {@link BoxSwapService} asks inventory between them, outside any transaction.
 *
 * <h2>A swap in flight</h2>
 *
 * Between the two halves the order carries a durable record, {@code order_box_swap_inflight} (one per order; Flyway V9). It is written by {@link #plan},
 * deleted by {@link #record}, and deleted by {@link #abandon} only when inventory has refused in a way that proves it moved nothing. It never expires: a
 * swap that was asked and never answered may have happened at inventory, and a parcel must never leave with one box while the books say another. While it
 * stands nothing else can be swapped on the order ({@code box-swap-in-progress}) and the order cannot be shipped ({@link #requireNoneInFlight}); the way
 * out is to repeat the same swap, which inventory answers once and order then records.
 *
 * <h2>The order's lock</h2>
 *
 * {@link #plan} takes the order's row lock ({@link OrderLocks}) before judging it, and every dispatch takes it before looking for a swap in flight
 * ({@link #requireNoneInFlight}), so the two queue behind each other and the second one sees what the first left: an order shipped a moment ago is refused,
 * never swapped from a stale look, and a swap planned a moment ago stops the shipment. {@link #record} needs no lock of its own: while the swap is in
 * flight nothing can dispatch the order, and a single statement deletes the record.
 *
 * <p>The cut-off is DISPATCH: a box may change while the order is PAID or FULFILLED (packed, still on the shelf), not once it is SHIPPED. That is the
 * client's own line (CLIENT_DECISIONS 4.2: a box change "before dispatch"); Phase 7 stopped at packing only because no dispatch record existed yet.
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

    /** The swap that is with inventory (or was, and never finished): what a repeat of it must match. */
    private record InFlight(UUID amendmentId, UUID itemId, UUID newBoxVariantId, String reason, Instant startedAt) {
    }

    private final JdbcTemplate jdbc;
    private final OrderLocks locks;

    public BoxSwaps(JdbcTemplate jdbc, OrderLocks locks) {
        this.jdbc = jdbc;
        this.locks = locks;
    }

    /**
     * @param actor {@code user:<keycloak subject>} of the member of staff, kept with the swap in flight
     * @throws ResourceNotFoundException the order or the line does not exist
     * @throws SalesProblem 409: {@code amendment-id-used} (that id was used for a different swap, recorded or in flight), {@code box-swap-not-allowed}
     *                      (the order is not PAID or FULFILLED: unpaid, or already dispatched), {@code payment-being-applied}, {@code no-box-on-line},
     *                      {@code same-box}, {@code box-swap-in-progress} (another swap on this order is with inventory: repeat it first)
     */
    @Transactional
    public Planned plan(UUID orderId, UUID lineId, UUID newBoxVariantId, String reason, UUID requestedAmendmentId, String actor) {
        Order order = locks.lock(orderId).orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId.toString()));
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
        if (order.status() != OrderStatus.PAID && order.status() != OrderStatus.FULFILLED) {
            throw new SalesProblem(HttpStatus.CONFLICT, "box-swap-not-allowed",
                    "only a paid order that has not been dispatched can change its box; this one is " + order.status());
        }
        OrderItem line = order.items().stream().filter(i -> i.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Order line", "id", lineId.toString()));
        if (line.boxVariantId() == null || line.boxLineId() == null || line.productId() == null) {
            throw new SalesProblem(HttpStatus.CONFLICT, "no-box-on-line", "this line has no box recorded to swap");
        }
        if (line.boxVariantId().equals(newBoxVariantId)) {
            throw new SalesProblem(HttpStatus.CONFLICT, "same-box", "the line already goes out in that box");
        }
        InFlight flight = inFlight(orderId);
        if (flight == null) {
            jdbc.update("""
                    INSERT INTO order_box_swap_inflight (order_id, amendment_id, item_id, new_box_variant_id, reason, requested_by, started_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", orderId, amendmentId, lineId, newBoxVariantId, why, actor, Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
        } else if (!flight.amendmentId().equals(amendmentId)) {
            throw inProgress(flight);
        } else if (!flight.itemId().equals(lineId) || !flight.newBoxVariantId().equals(newBoxVariantId) || !flight.reason().equals(why)) {
            throw new SalesProblem(HttpStatus.CONFLICT, "amendment-id-used", "amendment " + amendmentId + " is already in use for a different swap that is with inventory");
        }
        // else: the very swap that was asked and never finished, asked again: it resumes (inventory answers a repeat once).
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
     * Inventory has done the stock: the amendment is written, the line carries the new box and the swap is no longer in flight -- ONE transaction.
     * Idempotent by the amendment id: a repeat (a retry after a lost reply, or two of them at once) writes nothing more.
     *
     * <p>The line takes the new box only if it still carries the box line the swap was made from. If something else changed it meanwhile, or the order has
     * been dispatched (neither can happen while the swap is in flight), inventory's swap is STILL recorded -- the stock has moved, and the books must say
     * so -- and the mismatch is logged at ERROR for a person to look at.
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
            int moved = jdbc.update("UPDATE order_items SET box_variant_id = ?, box_line_id = ? WHERE id = ? AND box_line_id = ?",
                    plan.newBoxVariantId(), newBoxLineId, plan.itemId(), plan.oldBoxLineId());
            if (moved == 0) {
                log.error("Order {} line {}: box swap {} was made at inventory but the line no longer carries the box line it was made from: the swap is "
                        + "recorded and the line is NOT changed -- someone must look at this order", plan.orderId(), plan.itemId(), plan.amendmentId());
            } else {
                log.info("Order {} line {}: box {} replaced by {} (amendment {}, by {})", plan.orderId(), plan.itemId(), plan.oldBoxVariantId(),
                        plan.newBoxVariantId(), plan.amendmentId(), actor);
            }
            String status = jdbc.query("SELECT status FROM orders WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, plan.orderId());
            if (status != null && !status.equals("PAID") && !status.equals("FULFILLED")) {
                log.error("Order {} is {} but box swap {} was recorded for it: the parcel may have left in the old box -- someone must look at this order",
                        plan.orderId(), status, plan.amendmentId());
            }
        }
        jdbc.update("DELETE FROM order_box_swap_inflight WHERE order_id = ? AND amendment_id = ?", plan.orderId(), plan.amendmentId());
        return find(plan.amendmentId());
    }

    /**
     * Inventory refused in a way that proves it moved nothing: the swap is over, and the order is free again. (An answer that does NOT prove it -- a
     * timeout, a 5xx, an amendment id inventory says it has already used -- never comes here: the swap stays in flight until it is resumed and recorded.)
     */
    @Transactional
    public void abandon(Plan plan) {
        int gone = jdbc.update("DELETE FROM order_box_swap_inflight WHERE order_id = ? AND amendment_id = ?", plan.orderId(), plan.amendmentId());
        if (gone == 1) {
            log.info("Order {}: box swap {} was refused by inventory before anything moved: no longer in flight", plan.orderId(), plan.amendmentId());
        }
    }

    /**
     * Refuses while a swap on this order is with inventory (or never finished): the parcel must not leave with one box while the books say another. Called by
     * everything that dispatches an order, inside its own transaction and after taking the order's lock, so a swap cannot be planned between the check and
     * the dispatch.
     *
     * @throws SalesProblem 409 {@code box-swap-in-progress}, naming the swap to repeat
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireNoneInFlight(UUID orderId) {
        InFlight flight = inFlight(orderId);
        if (flight != null) {
            throw inProgress(flight);
        }
    }

    private static SalesProblem inProgress(InFlight flight) {
        // Everything a person needs to repeat the swap EXACTLY (an amendment id alone is not enough: the request names the line, the box and the reason).
        return new SalesProblem(HttpStatus.CONFLICT, "box-swap-in-progress",
                "a box swap on this order was asked of inventory and has not been finished: repeat that swap (the same request) to finish it before anything else is done to the order")
                .with("amendmentId", flight.amendmentId().toString()).with("startedAt", flight.startedAt().toString())
                .with("lineId", flight.itemId().toString()).with("replacementBoxVariantId", flight.newBoxVariantId().toString()).with("reason", flight.reason());
    }

    private InFlight inFlight(UUID orderId) {
        return jdbc.query("SELECT amendment_id, item_id, new_box_variant_id, reason, started_at FROM order_box_swap_inflight WHERE order_id = ?",
                (rs, n) -> new InFlight(rs.getObject("amendment_id", UUID.class), rs.getObject("item_id", UUID.class),
                        rs.getObject("new_box_variant_id", UUID.class), rs.getString("reason"), rs.getTimestamp("started_at").toInstant()), orderId)
                .stream().findFirst().orElse(null);
    }

    private Swap find(UUID amendmentId) {
        return jdbc.query("SELECT * FROM order_box_amendment WHERE id = ?", (rs, n) -> new Swap(rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getObject("item_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("old_box_variant_id", UUID.class), rs.getObject("new_box_variant_id", UUID.class), rs.getInt("quantity"), rs.getString("reason"),
                rs.getString("amended_by"), rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("recorded_at").toInstant(), false), amendmentId)
                .stream().findFirst().orElse(null);
    }
}
