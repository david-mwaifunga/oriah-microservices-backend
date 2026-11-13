package com.oriah.order.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two lists the people who confirm money work from (Phase 7), read straight from order's own tables: who SAYS they have paid and is waiting to be
 * matched against the bank or the provider, and money that has not paid its sale. A person's screen, not an event: always the state NOW.
 *
 * <p>Capped at {@value #LIMIT} rows each: a longer list is a sign something needs fixing, not a page to scroll.
 */
@Service
@Transactional(readOnly = true)
public class SalesQueues {

    static final int LIMIT = 100;
    static final Set<String> SETTLEMENT_FILTERS = Set.of("IN_FLIGHT", "BLOCKED_BOX", "UNSETTLED", "VOIDED");

    /** One order staff may be asked to match money against. */
    public record AwaitingPayment(UUID orderId, String customer, String status, BigDecimal total, String currency, String claimMethod, String claimReference,
                                  Instant claimSubmittedAt, Instant holdExpiresAt, Instant placedAt) {
    }

    /**
     * One confirmed payment that has not simply paid its sale. {@code stuck}: in flight for longer than the stuck threshold. {@code boxVariantIds}:
     * for a sale waiting for a box, the box (or boxes) it was held with, so staff know what to receive.
     */
    public record SettlementRow(UUID confirmationId, UUID orderId, String state, String reason, String method, String reference, BigDecimal gross,
                                String currency, Instant arrivedAt, Instant receivedAt, int attempts, Instant nextAttemptAt, Instant voidedAt, boolean stuck,
                                List<UUID> boxVariantIds) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration stuckAfter;

    public SalesQueues(JdbcTemplate jdbc, Clock clock, @Value("${oriah.sales.settlement-stuck-after:PT5M}") Duration stuckAfter) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.stuckAfter = stuckAfter;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /**
     * Orders whose shopper said they paid and are waiting to be matched, soonest to lapse first. With {@code orderId}, instead, that one order if it can
     * still take money (waiting, claimed, or lapsed), so staff can confirm money for a customer who never typed a reference.
     */
    public List<AwaitingPayment> awaitingPayment(UUID orderId) {
        String select = "SELECT id, keycloak_id, status, total, currency, claim_method, claim_reference, claim_submitted_at, hold_expires_at, placed_at FROM orders ";
        if (orderId != null) {
            return jdbc.query(select + """
                    WHERE id = ? AND status IN ('CREATED', 'PAYMENT_SUBMITTED', 'EXPIRED') AND settling_confirmation_id IS NULL
                      AND hold_state IS NOT NULL AND hold_state <> 'PENDING'""", (rs, n) -> awaiting(rs), orderId);
        }
        return jdbc.query(select + """
                WHERE status = 'PAYMENT_SUBMITTED' AND settling_confirmation_id IS NULL
                ORDER BY hold_expires_at ASC NULLS LAST, placed_at LIMIT ?""", (rs, n) -> awaiting(rs), LIMIT);
    }

    private static AwaitingPayment awaiting(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AwaitingPayment(rs.getObject("id", UUID.class), rs.getString("keycloak_id"), rs.getString("status"), rs.getBigDecimal("total"),
                rs.getString("currency"), rs.getString("claim_method"), rs.getString("claim_reference"), instant(rs.getTimestamp("claim_submitted_at")),
                instant(rs.getTimestamp("hold_expires_at")), instant(rs.getTimestamp("placed_at")));
    }

    /**
     * Money that has not paid its sale: in flight, unsettled (with the reason), or voided. Newest first.
     *
     * @param state IN_FLIGHT, BLOCKED_BOX, UNSETTLED or VOIDED; null for all of them
     * @throws IllegalArgumentException the filter is none of those
     */
    public List<SettlementRow> settlements(String state) {
        if (state != null && !SETTLEMENT_FILTERS.contains(state)) {
            throw new IllegalArgumentException("state must be one of " + String.join(", ", SETTLEMENT_FILTERS) + ", was '" + state + "'");
        }
        String where = switch (state == null ? "" : state) {
            case "IN_FLIGHT" -> "s.state IN ('RECEIVED', 'CONSUMING', 'BLOCKED_BOX')";
            case "BLOCKED_BOX" -> "s.state = 'BLOCKED_BOX'";
            case "UNSETTLED" -> "s.state = 'UNSETTLED'";
            case "VOIDED" -> "s.voided_at IS NOT NULL";
            default -> "(s.state IN ('RECEIVED', 'CONSUMING', 'BLOCKED_BOX', 'UNSETTLED') OR s.voided_at IS NOT NULL)";
        };
        Instant stuckBefore = clock.instant().minus(stuckAfter);
        return jdbc.query("SELECT s.* FROM order_settlement s WHERE " + where + " ORDER BY s.received_at DESC LIMIT ?", (rs, n) -> {
            String rowState = rs.getString("state");
            boolean inFlight = rowState.equals("RECEIVED") || rowState.equals("CONSUMING") || rowState.equals("BLOCKED_BOX");
            Instant received = instant(rs.getTimestamp("received_at"));
            UUID order = rs.getObject("order_id", UUID.class);
            List<UUID> boxes = rowState.equals("BLOCKED_BOX")
                    ? jdbc.queryForList("SELECT DISTINCT box_variant_id FROM order_items WHERE order_id = ? AND box_variant_id IS NOT NULL", UUID.class, order)
                    : List.of();
            return new SettlementRow(rs.getObject("confirmation_id", UUID.class), order, rowState, rs.getString("reason"), rs.getString("method"),
                    rs.getString("reference"), rs.getBigDecimal("gross"), rs.getString("currency"), instant(rs.getTimestamp("arrived_at")), received,
                    rs.getInt("attempts"), instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("voided_at")),
                    inFlight && received.isBefore(stuckBefore), boxes);
        }, LIMIT);
    }
}
