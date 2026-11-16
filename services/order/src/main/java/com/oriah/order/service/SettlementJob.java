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

import lombok.extern.slf4j.Slf4j;

/**
 * The safety net of the settlement saga (Phase 7): asks again for every settlement whose time has come. The consumer drives a confirmation the moment
 * its money arrives; when inventory did not answer, the settlement waits with a time to try again (15 s, then longer, up to five minutes), and this job
 * is what tries. It also finds any that were never driven at all (this service stopped between recording the money and asking inventory).
 *
 * <p>One pass takes a bounded batch, each settlement in turn, each in transactions of its own; one that fails does not hold the others up. It also
 * says, once a pass, how many settlements are STUCK (in flight for longer than {@code oriah.sales.settlement-stuck-after}): money that staff confirmed
 * and that has paid nothing. That is an ERROR in the log and a number staff can see in their queue (the email to staff is notification's, raised when payment announces the money a second time).
 *
 * <p>Run by {@code SettlementJobSchedule}, on a thread of its own. Idempotent and safe on every instance.
 */
@Service
@Slf4j
public class SettlementJob {

    private static final int BATCH = 50;
    /** A settlement never driven by the consumer (this service stopped first) is picked up after this long. */
    private static final Duration NEVER_DRIVEN_AFTER = Duration.ofSeconds(15);

    private final JdbcTemplate jdbc;
    private final SettlementDriver driver;
    private final Clock clock;
    private final Duration stuckAfter;

    public SettlementJob(JdbcTemplate jdbc, SettlementDriver driver, Clock clock,
                         @Value("${oriah.sales.settlement-stuck-after:PT5M}") Duration stuckAfter) {
        this.jdbc = jdbc;
        this.driver = driver;
        this.clock = clock;
        this.stuckAfter = stuckAfter;
    }

    public void run() {
        Instant now = clock.instant();
        List<UUID> due = jdbc.queryForList("""
                SELECT confirmation_id FROM order_settlement
                 WHERE state IN ('RECEIVED', 'CONSUMING', 'BLOCKED_BOX')
                   AND ((next_attempt_at IS NOT NULL AND next_attempt_at <= ?) OR (next_attempt_at IS NULL AND received_at <= ?))
                 ORDER BY received_at LIMIT ?""", UUID.class, Timestamp.from(now), Timestamp.from(now.minus(NEVER_DRIVEN_AFTER)), BATCH);
        for (UUID confirmation : due) {
            try {
                driver.drive(confirmation);
            } catch (RuntimeException e) {
                log.warn("Settlement {} could not be driven this pass: {}", confirmation, e.getMessage());
            }
        }
        long stuck = stuck(now);
        if (stuck > 0) {
            log.error("{} confirmed payment(s) have been waiting to pay their sale for more than {}: staff must look at them", stuck, stuckAfter);
        }
    }

    /** How many confirmed payments have been in flight for longer than the stuck threshold (a gauge for dashboards; the alert to staff is notification's own, from payment's second announcement). */
    public long stuck(Instant now) {
        return jdbc.queryForObject("SELECT count(*) FROM order_settlement WHERE state IN ('RECEIVED', 'CONSUMING', 'BLOCKED_BOX') AND received_at < ?",
                Long.class, Timestamp.from(now.minus(stuckAfter)));
    }
}
