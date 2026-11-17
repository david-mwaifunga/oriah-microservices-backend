package com.oriah.notification;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The one clean-up every database test starts with. Test classes share a database for as long as their Spring context lives, and some of what
 * notification remembers outlasts a single message (an order's receipt number, for one), so each test begins from nothing.
 *
 * <p>It puts the clock back FIRST: a test that moved it must not decide whether the next test's messages are "too late".
 * Each table joins this list in the slice that creates it.
 */
public final class TestData {

    private TestData() {
    }

    public static void clean(JdbcTemplate jdbc, MutableClock clock) {
        clock.reset();
        jdbc.update("DELETE FROM below_cost_digest_piece");
        jdbc.update("DELETE FROM notification_log");
        jdbc.update("DELETE FROM staff_alert");
        jdbc.update("DELETE FROM order_ref");
        jdbc.update("DELETE FROM confirmation_watch");
        jdbc.update("DELETE FROM refund_watch_payout");
        jdbc.update("DELETE FROM refund_watch");
        jdbc.update("DELETE FROM recipient_contact");
        jdbc.update("UPDATE below_cost_poll SET leased_until = NULL, last_attempt_at = NULL, last_success_at = NULL, down_since = NULL, last_failure = NULL");
        jdbc.update("UPDATE dispatch_state SET server_failing_since = NULL, server_failures = 0, quiet_until = NULL");
    }
}
