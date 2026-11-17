package com.oriah.order.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The safety net of a correction (Phase 12, plan 4.2): asks inventory again for every paid cancellation and return that is still in flight and whose
 * time has come. The request that recorded it asks inventory at once; when the answer settled nothing, the correction waits with a time to try again
 * (15 s, then longer, up to five minutes), and this job is what tries. It also finds any that were never asked at all (this service stopped between
 * writing the correction and asking inventory). And it asks again for a returned piece whose outcome inventory has not recorded (section 4.6).
 *
 * <p>One pass takes a bounded batch, each correction in turn, each in transactions of its own; one that fails does not hold the others up. Once a pass
 * it says how many are STUCK (in flight for longer than {@code oriah.sales.correction-stuck-after}): an order nobody can pack, ship or correct until
 * someone looks. That is an ERROR in the log, the gauge {@code order.corrections.stuck}, and {@code stuck: true} in the staff list.
 *
 * <p>Run by {@code CorrectionJobSchedule}, on a thread of its own. Idempotent and safe on every instance.
 */
@Service
@Slf4j
public class CorrectionJob {

    private static final int BATCH = 50;
    /** A correction never asked of inventory (this service stopped first) is picked up after this long. */
    private static final Duration NEVER_ASKED_AFTER = Duration.ofSeconds(15);

    private final JdbcTemplate jdbc;
    private final CorrectionService corrections;
    private final AssessmentService assessments;
    private final ReturnPieces pieces;
    private final Clock clock;
    private final Duration stuckAfter;
    private final AtomicLong stuckNow = new AtomicLong();

    public CorrectionJob(JdbcTemplate jdbc, CorrectionService corrections, AssessmentService assessments, ReturnPieces pieces, Clock clock,
                         @Value("${oriah.sales.correction-stuck-after:PT5M}") Duration stuckAfter, ObjectProvider<MeterRegistry> meters) {
        this.jdbc = jdbc;
        this.corrections = corrections;
        this.assessments = assessments;
        this.pieces = pieces;
        this.clock = clock;
        this.stuckAfter = stuckAfter;
        meters.ifAvailable(registry -> Gauge.builder("order.corrections.stuck", stuckNow, AtomicLong::doubleValue)
                .description("Paid cancellations and returns that have waited on inventory for longer than the stuck threshold").register(registry));
    }

    public void run() {
        Instant now = clock.instant();
        List<UUID> due = jdbc.queryForList("""
                SELECT id FROM order_correction
                 WHERE state = 'REQUESTED'
                   AND ((next_attempt_at IS NOT NULL AND next_attempt_at <= ?) OR (next_attempt_at IS NULL AND recorded_at <= ?))
                 ORDER BY recorded_at LIMIT ?""", UUID.class, Timestamp.from(now), Timestamp.from(now.minus(NEVER_ASKED_AFTER)), BATCH);
        for (UUID correction : due) {
            try {
                corrections.resume(correction);
            } catch (RuntimeException e) {
                log.warn("Correction {} could not be driven this pass: {}", correction, e.getClass().getSimpleName());
            }
        }
        // A returned piece whose outcome inventory has not recorded yet (it had not heard of the marked-down listing, or did not answer).
        for (UUID piece : pieces.due(now, now.minus(NEVER_ASKED_AFTER), BATCH)) {
            try {
                assessments.resume(piece);
            } catch (RuntimeException e) {
                log.warn("Returned piece {} could not be driven this pass: {}", piece, e.getClass().getSimpleName());
            }
        }
        long stuck = stuck(clock.instant());
        stuckNow.set(stuck);
        if (stuck > 0) {
            log.error("{} correction(s) of a paid sale have been waiting on inventory for more than {}: their orders cannot be packed, shipped or corrected "
                    + "until someone looks (GET /api/v1/admin/orders/corrections?state=REQUESTED)", stuck, stuckAfter);
        }
    }

    /** How many corrections have been in flight for longer than the stuck threshold. */
    public long stuck(Instant now) {
        return jdbc.queryForObject("SELECT count(*) FROM order_correction WHERE state = 'REQUESTED' AND recorded_at < ?", Long.class,
                Timestamp.from(now.minus(stuckAfter)));
    }

    /** Whether a correction recorded at this instant and still in flight counts as stuck now. */
    public boolean isStuck(Instant recordedAt) {
        return recordedAt.isBefore(clock.instant().minus(stuckAfter));
    }
}
