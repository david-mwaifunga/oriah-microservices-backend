package com.oriah.procurement;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Clearing the database between tests.
 *
 * <p>The tests share one Postgres (every context here imports the same Testcontainers
 * configuration), and a supplier cannot be deleted while a purchase order names it. So a
 * test that wants "no suppliers" first empties everything that hangs off them. The
 * purchasing tables are TRUNCATEd, not DELETEd: a received or cancelled order is frozen
 * by a row trigger, and TRUNCATE is the one statement that trigger does not see.
 *
 * <p>The event log (outbox, relay cursor, processed_event) is NEVER touched: it is
 * append-only and the database refuses it. A test that reads events reads only its own
 * rows, by aggregate id.
 */
public final class ProcurementTestData {

    private ProcurementTestData() {
    }

    /** Everything a test may have made, suppliers included, except the event log. */
    public static void wipe(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE purchase_order, purchase_order_line CASCADE");
        jdbc.update("DELETE FROM suppliers");
    }
}
