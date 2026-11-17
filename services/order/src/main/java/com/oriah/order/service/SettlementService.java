package com.oriah.order.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.order.events.InboundEvents.PaymentConfirmed;
import com.oriah.order.events.InboundEvents.PaymentVoided;
import com.oriah.order.events.OrderEventLog;
import com.oriah.order.events.OrderPaidEvent;
import com.oriah.order.model.HoldState;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The database halves of the settlement saga (Phase 7; design FINAL section A), each one short local transaction: money that staff confirmed either PAYS
 * THE SALE or is HELD for the customer, and the order says which. {@link SettlementDriver} asks inventory between them, for the reasons
 * {@link OrderWriter} gives: no transaction is held across that call.
 *
 * <h2>Every state of every order has an outcome</h2>
 *
 * {@link #receive} NEVER throws for an order being in an unexpected state: an event that throws is parked on the dead-letter topic and nobody reads
 * it. Money for an order nobody knows, a cancelled one, one already paid, or for the wrong amount is recorded and announced as UNSETTLED with its
 * reason, and payment holds it.
 *
 * <h2>Locks</h2>
 *
 * Every transaction takes the order's row lock FIRST (a plain {@code SELECT ... FOR UPDATE}), then the settlement's, and re-reads both before it
 * decides: under READ COMMITTED a statement after the lock sees what the other transaction committed. The outbox's own lock is taken last, by the
 * event log, so one lock order serves them all.
 */
@Service
@Slf4j
public class SettlementService {

    static final String ACTOR = "system:order-settlement";

    private static final RowMapper<Settlement> MAP = (rs, n) -> new Settlement(
            rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("confirmation_id", UUID.class), rs.getString("state"),
            rs.getString("reason"), rs.getString("method"), rs.getString("reference"), rs.getBigDecimal("gross"), rs.getBigDecimal("fee"),
            rs.getBigDecimal("net"), rs.getString("currency"), instant(rs.getTimestamp("arrived_at")), rs.getString("confirmed_by"),
            rs.getObject("reservation_id", UUID.class), rs.getInt("attempts"), instant(rs.getTimestamp("next_attempt_at")), rs.getString("deferred_end"),
            instant(rs.getTimestamp("received_at")), instant(rs.getTimestamp("settled_at")), instant(rs.getTimestamp("voided_at")));

    private final JdbcTemplate jdbc;
    private final OrderRepository repository;
    private final OrderEventLog events;
    private final Clock clock;

    public SettlementService(JdbcTemplate jdbc, OrderRepository repository, OrderEventLog events, Clock clock) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** The settlement of this confirmation, or null when order has not heard of it. */
    @Transactional(readOnly = true)
    public Settlement find(UUID confirmationId) {
        return jdbc.query("SELECT * FROM order_settlement WHERE confirmation_id = ?", MAP, confirmationId).stream().findFirst().orElse(null);
    }

    private Settlement findForUpdate(UUID confirmationId) {
        return jdbc.query("SELECT * FROM order_settlement WHERE confirmation_id = ? FOR UPDATE", MAP, confirmationId).stream().findFirst().orElse(null);
    }

    /** Takes the order's row lock (nothing happens when there is no such order: the money may name one nobody knows). */
    void lockOrder(UUID orderId) {
        jdbc.query("SELECT id FROM orders WHERE id = ? FOR UPDATE", rs -> {
        }, orderId);
    }

    // ------------------------------------------------------------------ step 1: the money arrives

    /**
     * {@code payment.confirmed}, applied inside the Inbox's transaction. Decides, records, and (for money that can pay the sale) marks the order as
     * being settled.
     *
     * @return the confirmation to drive (ask inventory to sell the pieces) once this commits, or null when there is nothing more to do
     */
    @Transactional
    public UUID receive(PaymentConfirmed p) {
        Settlement existing = find(p.confirmationId());
        if (existing != null) {
            answerAgain(existing);
            return null;
        }
        lockOrder(p.orderId());
        Order order = repository.findById(p.orderId()).orElse(null);
        Instant now = now();
        String reason = unsettledReason(order, p);
        if (reason != null) {
            if (order != null && p.providerPaymentId() != null && order.endProviderPayment(p.providerPaymentId())) {
                // The attempt's own money came and cannot pay the order (held for the customer): the attempt is over. Other money held for the order
                // (an earlier attempt's, recorded by the owner) leaves the attempt in flight standing.
                repository.flush();
            }
            if (insert(p, "UNSETTLED", reason, null, now) == 1) {
                events.paymentUnsettled(p.orderId(), p.confirmationId(), reason, p.currency().toUpperCase(), now, ACTOR);
                log.info("Confirmation {} of {} for order {} is UNSETTLED: {}", p.confirmationId(), p.gross(), p.orderId(), reason);
            }
            return null;
        }
        // The hold the order still has is sold; an order that expired (or whose hold is not HELD) takes a NEW one, whose id is chosen and kept here so a
        // crash or a lost reply retries the same one.
        UUID reservationId = order.status() != OrderStatus.EXPIRED && order.holdState() == HoldState.HELD ? order.reservationId() : UUID.randomUUID();
        if (insert(p, "CONSUMING", null, reservationId, now) == 0) {
            return null;
        }
        order.beginSettlement(p.confirmationId(), p.providerPaymentId());
        repository.flush();
        log.info("Confirmation {} of {} is applying to order {} (reservation {})", p.confirmationId(), p.gross(), order.id(), reservationId);
        return p.confirmationId();
    }

    private String unsettledReason(Order order, PaymentConfirmed p) {
        if (order == null || order.reservationId() == null || order.holdState() == null || order.holdState() == HoldState.PENDING) {
            return "ORDER_UNKNOWN";
        }
        if (order.status() == OrderStatus.CANCELLED) {
            return "ORDER_CANCELLED";
        }
        boolean paid = order.status() == OrderStatus.PAID || order.status() == OrderStatus.FULFILLED || order.status() == OrderStatus.SHIPPED
                || order.status() == OrderStatus.DELIVERED;
        if (paid || order.settlingConfirmationId() != null
                || jdbc.queryForObject("SELECT count(*) FROM order_settlement WHERE order_id = ? AND state = 'DONE'", Long.class, order.id()) > 0) {
            return "ALREADY_PAID";
        }
        if (!order.currency().equalsIgnoreCase(p.currency()) || p.gross().compareTo(order.total()) != 0) {
            return "WRONG_AMOUNT";
        }
        if (order.saleKind() == com.oriah.order.model.SaleKind.LATE_PAPER && !LusakaDay.same(order.occurredAt(), p.arrivedAt())) {
            // A paper sale counts on the day it happened: money keyed for another day would date its revenue, cost and packaging to that other day while
            // its price and receipt say this one. Held for the caller; staff void it and key it again with the right day.
            return "WRONG_DATE";
        }
        return null;
    }

    private int insert(PaymentConfirmed p, String state, String reason, UUID reservationId, Instant now) {
        return jdbc.update("""
                INSERT INTO order_settlement (id, order_id, confirmation_id, state, reason, method, reference, gross, fee, net, currency, arrived_at,
                                              confirmed_by, reservation_id, attempts, received_at, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                ON CONFLICT (confirmation_id) DO NOTHING""",
                UUID.randomUUID(), p.orderId(), p.confirmationId(), state, reason, p.method(), p.reference(), p.gross(), p.fee(), p.net(),
                p.currency().toUpperCase(), Timestamp.from(p.arrivedAt()), p.confirmedBy() == null ? null : abbreviated(p.confirmedBy()), reservationId,
                Timestamp.from(now), state.equals("UNSETTLED") ? Timestamp.from(now) : null);
    }

    private static String abbreviated(String text) {
        return text.length() <= 128 ? text : text.substring(0, 128);
    }

    /**
     * A confirmation announced AGAIN. Payment announces one again while it has no answer, so a lost answer is recovered: if order has answered, it
     * answers again (the same fact, new event); if the answer is still being worked out, nothing is repeated.
     */
    private void answerAgain(Settlement s) {
        Instant now = now();
        if (s.state().equals("DONE")) {
            repository.findById(s.orderId()).ifPresent(order -> {
                events.paid(order, null, payment(s, now), true, ACTOR);
                log.info("Confirmation {} was announced again: order {}'s payment is answered again", s.confirmationId(), order.id());
            });
        } else if (s.state().equals("UNSETTLED") && !"VOIDED".equals(s.reason())) {
            events.paymentUnsettled(s.orderId(), s.confirmationId(), s.reason(), s.currency(), now, ACTOR);
            log.info("Confirmation {} was announced again: its {} is answered again", s.confirmationId(), s.reason());
        } else {
            log.info("Confirmation {} was announced again while {}: nothing to repeat yet", s.confirmationId(), s.state());
        }
    }

    private static OrderPaidEvent.Payment payment(Settlement s, Instant recordedAt) {
        return new OrderPaidEvent.Payment(s.confirmationId(), s.method(), s.gross(), s.fee(), s.net(), s.arrivedAt(), recordedAt);
    }

    // ------------------------------------------------------------------ money voided

    /**
     * {@code payment.voided}: staff cancelled this confirmation. Before order applied it, it is never applied (a mark is left if order never heard of
     * it, so its late announcement cannot pay a sale); after, the sale is flagged for staff, because the pieces cannot be un-sold.
     */
    @Transactional
    public void voided(PaymentVoided v) {
        lockOrder(v.orderId());
        Settlement s = findForUpdate(v.confirmationId());
        Instant now = now();
        if (s == null) {
            jdbc.update("""
                    INSERT INTO order_settlement (id, order_id, confirmation_id, state, reason, method, reference, gross, fee, net, currency, arrived_at,
                                                  attempts, received_at, settled_at, voided_at)
                    VALUES (?, ?, ?, 'UNSETTLED', 'VOIDED', ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                    ON CONFLICT (confirmation_id) DO NOTHING""",
                    UUID.randomUUID(), v.orderId(), v.confirmationId(), v.method(), v.reference(), v.gross(), v.fee(), v.net(), v.currency().toUpperCase(),
                    // order never heard of this money, so it has no date for its arrival: the void's own date stands in, and the row is never money
                    Timestamp.from(v.voidedAt()), Timestamp.from(now), Timestamp.from(now), Timestamp.from(v.voidedAt()));
            log.info("Confirmation {} was voided before order heard of it: a mark is kept so it can never pay a sale", v.confirmationId());
            return;
        }
        jdbc.update("UPDATE order_settlement SET voided_at = ? WHERE confirmation_id = ? AND voided_at IS NULL", Timestamp.from(v.voidedAt()), v.confirmationId());
        if (s.state().equals("DONE")) {
            log.error("Confirmation {} was VOIDED after it paid order {}: the pieces cannot be un-sold, so staff must look at this sale", v.confirmationId(),
                    s.orderId());
        } else {
            log.info("Confirmation {} was voided while {}", v.confirmationId(), s.state());
        }
    }

    // ------------------------------------------------------------------ steps 2 and 3: after inventory has been asked

    /**
     * A new hold's id, chosen and KEPT before inventory is asked for it, so every retry names the same one.
     *
     * <p>Replaces {@code dead}, the id the caller found gone (lapsed, released, or EXPIRED the moment it was asked for), and only that one: two drivers
     * can find the same hold dead at the same time (the consumer straight after the money arrives, and the retry job), and the second must use the
     * first's replacement, never overwrite it with another, or each would sell the same lines under a different hold.
     *
     * @return the replacement now kept: this call's, or the one a pass that got there first chose
     */
    @Transactional
    public UUID chooseNewReservation(UUID confirmationId, UUID dead) {
        UUID chosen = UUID.randomUUID();
        int changed = jdbc.update("UPDATE order_settlement SET reservation_id = ? WHERE confirmation_id = ? AND reservation_id = ? "
                + "AND state IN ('CONSUMING', 'BLOCKED_BOX')", chosen, confirmationId, dead);
        if (changed == 1) {
            return chosen;
        }
        List<UUID> kept = jdbc.queryForList("SELECT reservation_id FROM order_settlement WHERE confirmation_id = ? AND reservation_id IS NOT NULL", UUID.class,
                confirmationId);
        return kept.isEmpty() ? chosen : kept.get(0);
    }

    /** Re-reads the settlement under the order's lock, and returns it only while it is still in flight (another pass may have ended it). */
    private Settlement lockedInFlight(UUID confirmationId) {
        Settlement first = find(confirmationId);
        if (first == null) {
            return null;
        }
        lockOrder(first.orderId());
        Settlement s = findForUpdate(confirmationId);
        return s != null && s.inFlight() ? s : null;
    }

    /**
     * Inventory has sold the pieces: the order is PAID with this confirmation, dated to the day the money arrived, with the next receipt number, and
     * {@code order.paid} (version 2) is announced -- one transaction. Idempotent: a settlement that already ended is left as it is.
     */
    @Transactional
    public void paid(UUID confirmationId) {
        Settlement s = lockedInFlight(confirmationId);
        if (s == null) {
            log.info("Confirmation {} is not in flight any more: nothing to record as paid", confirmationId);
            return;
        }
        Order order = repository.findById(s.orderId()).orElse(null);
        if (order == null || !confirmationId.equals(order.settlingConfirmationId())) {
            log.error("Confirmation {} sold the pieces of order {} but the order is not being settled with it: staff must look at this sale", confirmationId,
                    s.orderId());
            return;
        }
        Instant now = now();
        OrderStatus previous = order.status();
        order.settle(confirmationId, s.arrivedAt(), receiptNumber());
        jdbc.update("UPDATE order_settlement SET state = 'DONE', reason = NULL, settled_at = ?, next_attempt_at = NULL WHERE confirmation_id = ?",
                Timestamp.from(now), confirmationId);
        events.paid(order, previous, payment(s, now), false, ACTOR);
        if (s.voidedAt() != null) {
            log.error("Confirmation {} was voided before it paid order {}, but the pieces were already sold: staff must look at this sale", confirmationId,
                    order.id());
        }
        log.info("Order id={} is PAID with confirmation {} (receipt {}, dated {})", order.id(), confirmationId, order.receiptNumber(), s.arrivedAt());
    }

    private String receiptNumber() {
        Long next = jdbc.queryForObject("SELECT nextval('order_receipt_seq')", Long.class);
        return String.format("OR-%06d", next);
    }

    /**
     * The settlement ends WITHOUT paying the sale: the money is held for the customer (payment is told, unless payment itself voided it). A hold that
     * is proved dead (PIECE_SOLD) ends the order; a lapse or a rejection that arrived while the settlement was in flight is applied now.
     *
     * @param announce whether payment must be told ({@code order.payment_unsettled}); not for VOIDED money, which payment already holds the fact of
     */
    @Transactional
    public void unsettled(UUID confirmationId, String reason, boolean announce) {
        Settlement s = lockedInFlight(confirmationId);
        if (s == null) {
            return;
        }
        Instant now = now();
        jdbc.update("UPDATE order_settlement SET state = 'UNSETTLED', reason = ?, settled_at = ?, next_attempt_at = NULL WHERE confirmation_id = ?", reason,
                Timestamp.from(now), confirmationId);
        Order order = repository.findById(s.orderId()).orElse(null);
        if (order != null && confirmationId.equals(order.settlingConfirmationId())) {
            order.endSettlement();
            boolean waiting = order.status() == OrderStatus.CREATED || order.status() == OrderStatus.PAYMENT_SUBMITTED;
            // The settlement works with another hold than the order's own once the order's was found gone and given back: ending without paying then
            // leaves a live order whose pieces nobody holds, which is no order at all.
            boolean holdGone = s.reservationId() != null && !s.reservationId().equals(order.reservationId());
            boolean ends = waiting && ("PIECE_SOLD".equals(reason)
                    || holdGone
                    || "EXPIRED".equals(s.deferredEnd())
                    || ("REJECTED".equals(s.deferredEnd()) && order.status() == OrderStatus.PAYMENT_SUBMITTED));
            if (ends) {
                OrderStatus previous = order.status();
                order.expire();
                events.moved(order, previous, ACTOR);
                log.info("Order id={} EXPIRED when its settlement ended unsettled ({})", order.id(), reason);
            }
        }
        if (announce) {
            events.paymentUnsettled(s.orderId(), confirmationId, reason, s.currency(), now, ACTOR);
        }
        log.info("Confirmation {} ended UNSETTLED: {}", confirmationId, reason);
    }

    /** Only a box is missing: the sale waits (the money is not given back for a box), and the retry job keeps asking. */
    @Transactional
    public void blocked(UUID confirmationId, Duration retryAfter) {
        Settlement s = lockedInFlight(confirmationId);
        if (s == null) {
            return;
        }
        jdbc.update("UPDATE order_settlement SET state = 'BLOCKED_BOX', attempts = attempts + 1, next_attempt_at = ? WHERE confirmation_id = ?",
                Timestamp.from(now().plus(retryAfter)), confirmationId);
        log.warn("Confirmation {} of order {} is waiting for a BOX (attempt {}): staff must receive packaging", confirmationId, s.orderId(), s.attempts() + 1);
    }

    /** Inventory did not answer (or answered something that clears by itself): ask again later, the same call, backing off. */
    @Transactional
    public void retryLater(UUID confirmationId, Duration retryAfter) {
        Settlement s = lockedInFlight(confirmationId);
        if (s == null) {
            return;
        }
        jdbc.update("UPDATE order_settlement SET attempts = attempts + 1, next_attempt_at = ? WHERE confirmation_id = ?",
                Timestamp.from(now().plus(retryAfter)), confirmationId);
    }
}
