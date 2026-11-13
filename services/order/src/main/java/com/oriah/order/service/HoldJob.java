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
 * Finishes what a request left half done (design FINAL B1 and B2). One pass does two things, each order in a transaction of its own so one that
 * fails does not hold the others up:
 *
 * <ul>
 *   <li><b>Pending extensions.</b> A shopper's payment claim is recorded first and the stretch of the hold asked of inventory after; when inventory
 *       did not answer, the extension is still pending. The job asks again (inventory's extend is idempotent), and when it is done the claim is
 *       announced. If inventory says the hold is gone, the order EXPIRES.</li>
 *   <li><b>Stale placements.</b> An order written PENDING whose hold never completed (inventory did not answer, or this service stopped) is, after a
 *       minute, given back at inventory and DELETED: it was never announced, so it never existed, and finishing it now would announce an order its
 *       shopper was never told they had. Deleted only after the release is answered (or inventory says it never held it): while a hold may exist, the
 *       order stays, and the next pass tries again.</li>
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

    public HoldJob(JdbcTemplate jdbc, OrderRepository repository, OrderHolds holds, OrderClaims claims, OrderWriter writer, Clock clock,
                   @Value("${oriah.sales.pending-grace:PT1M}") Duration pendingGrace) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.holds = holds;
        this.claims = claims;
        this.writer = writer;
        this.clock = clock;
        this.pendingGrace = pendingGrace;
    }

    public void run() {
        finishExtensions();
        giveBackStalePlacements();
    }

    private void finishExtensions() {
        List<UUID> pending = jdbc.queryForList(
                "SELECT id FROM orders WHERE hold_extension_pending AND status = 'PAYMENT_SUBMITTED' ORDER BY claim_submitted_at LIMIT ?", UUID.class, BATCH);
        for (UUID id : pending) {
            try {
                Order order = repository.findById(id).orElse(null);
                if (order == null || !order.holdExtensionPending()) {
                    continue;
                }
                try {
                    claims.extended(id, holds.extend(order), ACTOR);
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
                holds.release(order[1]);
                writer.discardPending(order[0]);
            } catch (RuntimeException e) {
                log.warn("Order {}'s unfinished placement could not be given back this pass: {}", order[0], e.getMessage());
            }
        }
    }
}
