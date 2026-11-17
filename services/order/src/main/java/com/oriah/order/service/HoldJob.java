package com.oriah.order.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.oriah.order.model.Order;
import com.oriah.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Finishes what a request left half done (design FINAL B1 and B2), and ends what an event should have ended. One pass does three things, each order in a
 * transaction of its own so one that fails does not hold the others up (the third, a lapsed hold whose event never arrived, is described on
 * {@link #endLapsedHolds}):
 *
 * <ul>
 *   <li><b>Pending extensions.</b> A shopper's payment claim is recorded first and the stretch of the hold asked of inventory after; when inventory
 *       did not answer, the extension is still pending. The job asks again (inventory's extend is idempotent), and when it is done the claim is
 *       announced. If inventory says the hold is gone, the order EXPIRES.</li>
 *   <li><b>Stale placements.</b> An order written PENDING whose hold never completed (inventory did not answer, or this service stopped) is, after a
 *       minute, DELETED and its hold given back at inventory: it was never announced, so it never existed, and finishing it now would announce an order
 *       its shopper was never told they had. Deleted FIRST (and only while it is still unfinished), released after: a hold that cannot be given back
 *       lapses by itself in its ten minutes, which does less harm than releasing the hold of an order a repeat has just finished.</li>
 * </ul>
 *
 * <p>Run by {@code HoldJobSchedule} every few seconds, on a thread of its own. Idempotent, and safe on every instance: each step re-reads the order
 * and acts only while it still needs it.
 */
@Service
@Slf4j
public class HoldJob {

    /** What one pass takes at most of each kind: a backlog is worked off over several passes, never in one long one. */
    private static final int BATCH = 50;
    private static final String ACTOR = "system:hold-job";

    private final JdbcTemplate jdbc;
    private final OrderRepository repository;
    private final OrderHolds holds;
    private final OrderClaims claims;
    private final OrderWriter writer;
    private final Clock clock;
    private final Duration pendingGrace;
    private final OrderEnds ends;
    private final Duration lapseGrace;

    private final ProviderPaymentHolds providerPayments;
    private final ProviderPaymentHoldService providerPaymentHolds;

    public HoldJob(JdbcTemplate jdbc, OrderRepository repository, OrderHolds holds, OrderClaims claims, OrderWriter writer, Clock clock,
                   @Value("${oriah.sales.pending-grace:PT1M}") Duration pendingGrace, OrderEnds ends,
                   @Value("${oriah.sales.lapse-grace:PT2M}") Duration lapseGrace, ProviderPaymentHolds providerPayments,
                   ProviderPaymentHoldService providerPaymentHolds) {
        this.providerPayments = providerPayments;
        this.providerPaymentHolds = providerPaymentHolds;
        this.jdbc = jdbc;
        this.repository = repository;
        this.holds = holds;
        this.claims = claims;
        this.writer = writer;
        this.clock = clock;
        this.pendingGrace = pendingGrace;
        this.ends = ends;
        this.lapseGrace = lapseGrace;
    }

    public void run() {
        finishExtensions();
        giveBackStalePlacements();
        endLapsedHolds();
    }

    /**
     * An order whose hold ran out a while ago and was never ended: inventory's expiry event is how an order normally ends, and one that is lost (parked
     * after its deliveries failed) would leave the order live for ever, counted against the shopper's limit and answered as "still yours". The grace keeps
     * the job from racing the event on its normal way.
     */
    private void endLapsedHolds() {
        Instant cutoff = clock.instant().minus(lapseGrace);
        List<UUID> lapsed = jdbc.queryForList(
                "SELECT id FROM orders WHERE hold_state = 'HELD' AND status IN ('CREATED', 'PAYMENT_SUBMITTED') AND settling_confirmation_id IS NULL "
                        + "AND NOT hold_extension_pending AND hold_expires_at < ? ORDER BY hold_expires_at LIMIT ?", UUID.class, Timestamp.from(cutoff), BATCH);
        for (UUID id : lapsed) {
            try {
                ends.lapsedByClock(id, cutoff);
            } catch (RuntimeException e) {
                log.warn("Order {}'s lapsed hold could not be ended this pass: {}", id, e.getMessage());
            }
        }
    }

    private void finishExtensions() {
        List<UUID> pending = jdbc.queryForList(
                "SELECT id FROM orders WHERE hold_extension_pending AND status IN ('PAYMENT_SUBMITTED', 'CREATED') "
                        + "ORDER BY COALESCE(claim_submitted_at, placed_at) LIMIT ?", UUID.class, BATCH);
        for (UUID id : pending) {
            try {
                Order order = repository.findById(id).orElse(null);
                if (order == null || !order.holdExtensionPending()) {
                    continue;
                }
                try {
                    if (order.status() == com.oriah.order.model.OrderStatus.CREATED) {
                        // A payment through ZynlePay was taken and its stretch never made (Phase 13). No claim exists, so none is announced: the
                        // hold is stretched and that is all.
                        providerPayments.extendedLater(id, holds.extend(order, providerPaymentHolds.until()));
                    } else {
                        claims.extended(id, holds.extend(order), ACTOR);
                    }
                } catch (OrderHolds.HoldGoneException gone) {
                    claims.lapsed(id, ACTOR);
                }
            } catch (RuntimeException e) {
                log.warn("Order {}'s pending hold extension could not be finished this pass: {}", id, e.getMessage());
            }
        }
    }

    private void giveBackStalePlacements() {
        Instant cutoff = clock.instant().minus(pendingGrace);
        List<UUID[]> stale = jdbc.query(
                "SELECT id, reservation_id FROM orders WHERE hold_state = 'PENDING' AND placed_at < ? ORDER BY placed_at LIMIT ?",
                (rs, n) -> new UUID[] {rs.getObject("id", UUID.class), rs.getObject("reservation_id", UUID.class)}, Timestamp.from(cutoff), BATCH);
        for (UUID[] order : stale) {
            try {
                // Claimed FIRST: removed in one step that checks it is still unfinished. If the shopper's repeat finished it in the meantime the claim finds
                // it HELD (or loses on the row's version) and the job gives nothing back; releasing first could take the hold of an order that is being
                // announced at that very moment.
                if (!writer.claimStalePending(order[0], cutoff)) {
                    continue;
                }
            } catch (RuntimeException e) {
                log.info("Order {}'s unfinished placement was not given up this pass (a repeat is probably finishing it): {}", order[0], e.getMessage());
                continue;
            }
            try {
                holds.release(order[1]);
            } catch (RuntimeException e) {
                // The order is gone, and a hold that is not given back lapses by itself in its ten minutes: far less harm than releasing a live one.
                log.warn("The hold {} of a removed unfinished placement could not be given back; it lapses by itself: {}", order[1], e.getMessage());
            }
        }
    }
}
