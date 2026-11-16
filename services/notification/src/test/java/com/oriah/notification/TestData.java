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
        jdbc.update("DELETE FROM notification_log");
        jdbc.update("DELETE FROM order_ref");
    }
}
