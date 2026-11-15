package com.oriah.order.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.events.OrderEvent;
import com.oriah.order.events.OrderEventLog;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.service.DispatchDates.Dated;

import lombok.extern.slf4j.Slf4j;

/**
 * Packing, sending and handing over a paid order (Phase 8): three steps, each recorded once with the date it truly happened, each the only way to its
 * status. They are the dispatch record the books and the refund line are read from: before DISPATCHED a cancellation refunds delivery, from it goods only,
 * and the courier fare Oriah paid is a fact of the DISPATCHED step, published in {@code order.shipped} and nowhere else.
 *
 * <ul>
 *   <li>{@code pack}: PAID to FULFILLED. A delivered order needs its recipient and address (an order placed before Phase 8 has none and is refused).</li>
 *   <li>{@code ship}: FULFILLED to SHIPPED, by the option the shopper chose. Records the fare when Oriah pays the courier ({@link FareRule}) and a
 *       reference. Refused while a box swap on the order is with inventory.</li>
 *   <li>{@code deliver}: SHIPPED to DELIVERED; for a pickup, FULFILLED to DELIVERED (the hand-over, which is the pickup's dispatch).</li>
 * </ul>
 *
 * <p>Every step takes the order's row lock first ({@link OrderLocks}), so two packers, a swap and a step all queue behind each other and the second one
 * judges the order as the first left it. A step already recorded answers 200 {@code replay} when everything the caller sent equals what was recorded, and
 * 409 naming what stands otherwise: safe to repeat after a lost reply, never a second record.
 *
 * <p>Money never reaches the packer: the delivery charge is never read here and never changed (Q24), and the fare comes back only to a caller who may see
 * cost.
 */
@Service
@Slf4j
public class DispatchService {

    public enum Step { PACKED, DISPATCHED, DELIVERED }

    /** Who is acting, and what they may see and do beyond taking a step. */
    public record Caller(String actor, boolean mayBackdate, boolean mayViewCost) {
    }

    /** One recorded step. */
    public record Recorded(Step step, Instant occurredAt, String recordedBy, String reference, BigDecimal farePaid, String backdateReason) {
    }

    /** What a step answers: where the order stands now and when each step happened. {@code farePaid} is null unless the caller may see cost. */
    public record Summary(UUID orderId, OrderStatus status, String stage, Instant packedAt, Instant dispatchedAt, Instant deliveredAt, String reference,
                          BigDecimal farePaid, boolean replay) {
    }

    private final OrderLocks locks;
    private final JdbcTemplate jdbc;
    private final OrderEventLog events;
    private final BoxSwaps swaps;
    private final Duration backdateWindow;
    private final BigDecimal maxFare;

    public DispatchService(OrderLocks locks, JdbcTemplate jdbc, OrderEventLog events, BoxSwaps swaps,
                           @Value("${oriah.sales.backdate-window:PT72H}") Duration backdateWindow,
                           @Value("${oriah.sales.max-fare:5000.00}") BigDecimal maxFare) {
        this.locks = locks;
        this.jdbc = jdbc;
        this.events = events;
        this.swaps = swaps;
        this.backdateWindow = backdateWindow;
        this.maxFare = maxFare;
    }

    // ------------------------------------------------------------------ the three steps

    @Transactional
    public Summary pack(UUID orderId, Instant requestedAt, String backdateReason, Caller caller) {
        Order order = lock(orderId);
        Map<Step, Recorded> steps = stepsOf(orderId);
        Recorded packed = steps.get(Step.PACKED);
        if (packed != null) {
            return replay(order, steps, caller, "already-recorded", requestedAt == null || sameInstant(requestedAt, packed.occurredAt()),
                    "it was already packed at " + packed.occurredAt());
        }
        requireStatus(order, OrderStatus.PAID, "pack");
        if (!order.isSelfCollect() && !order.hasDeliveryDetails()) {
            throw new SalesProblem(HttpStatus.CONFLICT, "delivery-details-missing", "this order has no recipient or delivery address kept (it was placed before "
                    + "they were taken): it cannot be packed until they are corrected");
        }
        // The packer packs the box the slip names: a swap that is with inventory may change it, so packing waits for it to be finished.
        swaps.requireNoneInFlight(orderId);
        Dated when = DispatchDates.check(requestedAt, order.paidAt(), Instant.now(), backdateWindow, caller.mayBackdate(), backdateReason);
        insert(orderId, Step.PACKED, when, caller, null, null);
        OrderStatus previous = order.status();
        order.transitionTo(OrderStatus.FULFILLED);
        events.moved(order, previous, caller.actor(), when.occurredAt());
        log.info("Order {} packed at {} by {}", orderId, when.occurredAt(), caller.actor());
        return summary(order, stepsOf(orderId), false, caller);
    }

    @Transactional
    public Summary ship(UUID orderId, String reference, BigDecimal farePaid, Instant requestedAt, String backdateReason, Caller caller) {
        Order order = lock(orderId);
        Map<Step, Recorded> steps = stepsOf(orderId);
        Recorded sent = steps.get(Step.DISPATCHED);
        if (sent != null) {
            boolean same = (reference == null || reference.equals(sent.reference())) && (farePaid == null || sent.farePaid() != null && farePaid.compareTo(sent.farePaid()) == 0)
                    && (requestedAt == null || sameInstant(requestedAt, sent.occurredAt()));
            return replay(order, steps, caller, "already-dispatched", same, "it was already dispatched at " + sent.occurredAt()
                    + (sent.reference() != null ? " with reference " + sent.reference() : "") + (caller.mayViewCost() && sent.farePaid() != null ? " and fare " + sent.farePaid() : ""));
        }
        if (order.isSelfCollect()) {
            throw new SalesProblem(HttpStatus.CONFLICT, "dispatch-step-not-allowed", "this order is collected by the shopper: it is handed over (deliver), never shipped");
        }
        requireStatus(order, OrderStatus.FULFILLED, "ship");
        if (!order.hasDeliveryDetails()) {
            throw new SalesProblem(HttpStatus.CONFLICT, "delivery-details-missing", "this order has no recipient or delivery address kept: it cannot be shipped");
        }
        swaps.requireNoneInFlight(orderId);
        BigDecimal fare = FareRule.check(order.deliveryOption(), order.deliveryPayer(), farePaid, maxFare);
        Dated when = DispatchDates.check(requestedAt, instant(steps.get(Step.PACKED)), Instant.now(), backdateWindow, caller.mayBackdate(), backdateReason);
        insert(orderId, Step.DISPATCHED, when, caller, reference, fare);
        OrderStatus previous = order.status();
        order.transitionTo(OrderStatus.SHIPPED);
        events.shipped(order, previous, caller.actor(), when.occurredAt(), new OrderEvent.Dispatch(when.occurredAt(), fare, order.deliveryPayer()));
        log.info("Order {} dispatched at {} by {} (fare recorded: {})", orderId, when.occurredAt(), caller.actor(), fare != null);
        return summary(order, stepsOf(orderId), false, caller);
    }

    @Transactional
    public Summary deliver(UUID orderId, Instant requestedAt, String backdateReason, Caller caller) {
        Order order = lock(orderId);
        Map<Step, Recorded> steps = stepsOf(orderId);
        Recorded delivered = steps.get(Step.DELIVERED);
        if (delivered != null) {
            return replay(order, steps, caller, "already-recorded", requestedAt == null || sameInstant(requestedAt, delivered.occurredAt()),
                    "it was already delivered at " + delivered.occurredAt());
        }
        Instant notBefore;
        if (order.isSelfCollect()) {
            requireStatus(order, OrderStatus.FULFILLED, "hand over");
            // The hand-over is a pickup's dispatch: a swap that is with inventory must be finished first, or the box handed over is not the box the books say.
            swaps.requireNoneInFlight(orderId);
            notBefore = instant(steps.get(Step.PACKED));
        } else {
            requireStatus(order, OrderStatus.SHIPPED, "deliver");
            notBefore = instant(steps.get(Step.DISPATCHED));
        }
        Dated when = DispatchDates.check(requestedAt, notBefore, Instant.now(), backdateWindow, caller.mayBackdate(), backdateReason);
        insert(orderId, Step.DELIVERED, when, caller, null, null);
        OrderStatus previous = order.status();
        order.transitionTo(OrderStatus.DELIVERED);
        events.moved(order, previous, caller.actor(), when.occurredAt());
        log.info("Order {} delivered at {} by {}", orderId, when.occurredAt(), caller.actor());
        return summary(order, stepsOf(orderId), false, caller);
    }

    // ------------------------------------------------------------------ plumbing

    private Order lock(UUID orderId) {
        return locks.lock(orderId).orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId.toString()));
    }

    /** The order must be in {@code needed} for this step; an unpaid or ended order says so, any other status says what to do first. */
    private static void requireStatus(Order order, OrderStatus needed, String what) {
        if (order.status() == needed) {
            return;
        }
        if (List.of(OrderStatus.CREATED, OrderStatus.PAYMENT_SUBMITTED, OrderStatus.EXPIRED, OrderStatus.CANCELLED).contains(order.status())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "order-not-paid", "this order is " + order.status() + ", not paid: nothing is packed or sent until it is");
        }
        String hint = order.status() == OrderStatus.PAID ? "it must be packed first" : "it is " + order.status();
        throw new SalesProblem(HttpStatus.CONFLICT, "dispatch-step-not-allowed", "cannot " + what + " this order: " + hint);
    }

    private static boolean sameInstant(Instant a, Instant b) {
        return a.truncatedTo(ChronoUnit.MICROS).equals(b.truncatedTo(ChronoUnit.MICROS));
    }

    private static Instant instant(Recorded step) {
        return step == null ? null : step.occurredAt();
    }

    private Summary replay(Order order, Map<Step, Recorded> steps, Caller caller, String code, boolean same, String standing) {
        if (!same) {
            throw new SalesProblem(HttpStatus.CONFLICT, code, "this step is already recorded and what you sent is not what was recorded: " + standing);
        }
        return summary(order, steps, true, caller);
    }

    private void insert(UUID orderId, Step step, Dated when, Caller caller, String reference, BigDecimal fare) {
        jdbc.update("""
                INSERT INTO order_dispatch_step (id, order_id, step, occurred_at, recorded_at, recorded_by, reference, fare_paid, backdate_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), orderId, step.name(), Timestamp.from(when.occurredAt()),
                Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)), caller.actor(), reference, fare, when.backdateReason());
    }

    Map<Step, Recorded> stepsOf(UUID orderId) {
        Map<Step, Recorded> steps = new EnumMap<>(Step.class);
        jdbc.query("SELECT step, occurred_at, recorded_by, reference, fare_paid, backdate_reason FROM order_dispatch_step WHERE order_id = ?", rs -> {
            Step step = Step.valueOf(rs.getString("step"));
            steps.put(step, new Recorded(step, rs.getTimestamp("occurred_at").toInstant(), rs.getString("recorded_by"), rs.getString("reference"),
                    rs.getBigDecimal("fare_paid"), rs.getString("backdate_reason")));
        }, orderId);
        return steps;
    }

    static String stage(OrderStatus status) {
        return switch (status) {
            case PAID -> "TO_PACK";
            case FULFILLED -> "PACKED";
            case SHIPPED -> "ON_THE_WAY";
            case DELIVERED -> "DELIVERED";
            default -> status.name();
        };
    }

    private static Summary summary(Order order, Map<Step, Recorded> steps, boolean replay, Caller caller) {
        Recorded sent = steps.get(Step.DISPATCHED);
        return new Summary(order.id(), order.status(), stage(order.status()), instant(steps.get(Step.PACKED)), instant(sent), instant(steps.get(Step.DELIVERED)),
                sent == null ? null : sent.reference(), caller.mayViewCost() && sent != null ? sent.farePaid() : null, replay);
    }
}
