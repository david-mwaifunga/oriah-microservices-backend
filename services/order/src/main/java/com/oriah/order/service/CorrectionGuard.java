package com.oriah.order.service;

import com.oriah.order.exception.SalesProblem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Refuses to touch an order while a correction of it is with inventory (Phase 12, plan 4.2). A paid cancellation or a return is asked of inventory
 * between two short transactions; until inventory's answer settles it, the order must not be packed, shipped, handed over, re-boxed, re-addressed or
 * corrected again, or the parcel could leave while the books say its pieces went back to the shelf.
 *
 * <p>Called by everything that changes a paid order, inside its own transaction and AFTER taking the order's lock ({@link OrderLocks}), so a correction
 * cannot be recorded between the check and the change. A bean of its own (and not a method of {@link Corrections}) so that the box swap, the dispatch
 * steps and the corrections can all use it without depending on each other.
 */
@Component
public class CorrectionGuard {

    private final JdbcTemplate jdbc;

    public CorrectionGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws SalesProblem 409 {@code correction-in-progress}, naming the correction that must be settled first */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNoneInFlight(UUID orderId) {
        List<Map<String, Object>> inFlight = jdbc.queryForList(
                "SELECT id, kind, recorded_at FROM order_correction WHERE order_id = ? AND state = 'REQUESTED'", orderId);
        if (!inFlight.isEmpty()) {
            Map<String, Object> correction = inFlight.get(0);
            String what = "CANCELLATION".equals(correction.get("kind")) ? "a cancellation" : "a return";
            throw new SalesProblem(HttpStatus.CONFLICT, "correction-in-progress", what + " of this order was asked of the stock system and has not been "
                    + "settled yet: repeat it to finish it (it is also retried by itself) before anything else is done to the order")
                    .with("correctionId", correction.get("id").toString()).with("kind", correction.get("kind"))
                    .with("recordedAt", ((java.sql.Timestamp) correction.get("recorded_at")).toInstant().toString());
        }
    }

    /** Whether every piece this order sold has come back (returns that were not refused), so there is nothing left to deliver. */
    public boolean everyPieceCameBack(UUID orderId) {
        Integer sold = jdbc.queryForObject("SELECT COALESCE(sum(quantity), 0) FROM order_items WHERE order_id = ?", Integer.class, orderId);
        Integer back = jdbc.queryForObject("""
                SELECT COALESCE(sum(l.quantity), 0) FROM order_correction_line l JOIN order_correction c ON c.id = l.correction_id
                 WHERE c.order_id = ? AND c.kind = 'RETURN' AND c.state <> 'REFUSED'""", Integer.class, orderId);
        return sold != null && sold > 0 && back != null && back >= sold;
    }
}
