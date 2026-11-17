package com.oriah.order.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * What payment did about the refunds order announced (Phase 12; order V17): a payout, a payout cancelled, a debt closed because the money never
 * arrived. Kept as they arrive, append-only, so that "is a refund of this order still owed?" has an answer here whatever order the facts came in. A
 * debt's id IS the correction's id.
 *
 * <p>Order does nothing else with them: what is owed was decided when the correction was recorded, and paying it is payment's.
 */
@Component
public class RefundFacts {

    private static final String INSERT = """
            INSERT INTO order_refund_fact (correction_id, kind, payout_id, occurred_at, recorded_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING""";

    private final JdbcTemplate jdbc;

    public RefundFacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void paidOut(UUID correctionId, UUID payoutId, Instant paidAt) {
        write(correctionId, "PAID_OUT", payoutId, paidAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void payoutVoided(UUID correctionId, UUID payoutId, Instant voidedAt) {
        write(correctionId, "PAYOUT_VOIDED", payoutId, voidedAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void closed(UUID correctionId, Instant closedAt) {
        write(correctionId, "CLOSED", null, closedAt);
    }

    /**
     * Whether any finished correction of this order still owes the customer money: it owes something, payment has not closed the debt, and no payout
     * of it stands (a payout that was cancelled afterwards does not count).
     */
    public boolean stillOwes(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM order_correction c
                     WHERE c.order_id = ? AND c.state = 'DONE' AND c.owed_gross > 0
                       AND NOT EXISTS (SELECT 1 FROM order_refund_fact f WHERE f.correction_id = c.id AND f.kind = 'CLOSED')
                       AND NOT EXISTS (SELECT 1 FROM order_refund_fact p WHERE p.correction_id = c.id AND p.kind = 'PAID_OUT'
                                          AND NOT EXISTS (SELECT 1 FROM order_refund_fact v WHERE v.correction_id = c.id AND v.kind = 'PAYOUT_VOIDED'
                                                             AND v.payout_id = p.payout_id)))""", Boolean.class, orderId));
    }

    private void write(UUID correctionId, String kind, UUID payoutId, Instant occurredAt) {
        jdbc.update(INSERT, correctionId, kind, payoutId, Timestamp.from(occurredAt), Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }
}
