package com.oriah.notification.delivery;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Whether the mail server itself is in trouble, kept in the one {@code dispatch_state} row so that every instance knows it on every pass and a
 * restart does not forget it.
 *
 * <p>"The server's trouble" is a failure that would meet the next message too: no connection, a refused sign-in, a refused sender, a used-up
 * quota. One mailbox that is full or does not exist is NOT that, and never touches this.
 *
 * <p>After such a failure nothing is handed to the server for a quiet time that doubles with each failure in a row (one minute, two, four ...
 * up to a maximum), so a wrong password is tried a few times an hour, not twelve times a minute. The first message a server takes clears it.
 */
@Component
public class DispatchHealth {

    /**
     * @param failingSince when the server first failed since it last accepted anything; null when it is working
     * @param failures     failures in a row
     * @param quietUntil   nothing is handed over before this moment; null when there is no quiet time
     */
    public record State(Instant failingSince, int failures, Instant quietUntil) {

        public boolean quietAt(Instant now) {
            return quietUntil != null && quietUntil.isAfter(now);
        }
    }

    private final JdbcTemplate jdbc;

    public DispatchHealth(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public State state() {
        return jdbc.queryForObject("SELECT server_failing_since, server_failures, quiet_until FROM dispatch_state WHERE id = 1",
                (row, n) -> new State(instant(row.getTimestamp(1)), row.getInt(2), instant(row.getTimestamp(3))));
    }

    /** The mail server could not be used. One statement: counts the failure and sets the quiet time from the new count. */
    public void serverFailed(Instant now, Duration quiet, Duration quietMax) {
        jdbc.update("""
                UPDATE dispatch_state
                   SET server_failing_since = COALESCE(server_failing_since, ?),
                       server_failures = server_failures + 1,
                       quiet_until = ?::timestamptz + LEAST(? * power(2, LEAST(server_failures, 20)), ?) * interval '1 millisecond'
                 WHERE id = 1""", Timestamp.from(now), Timestamp.from(now), quiet.toMillis(), quietMax.toMillis());
    }

    /** The mail server took a message (or answered a probe): it is working. */
    public void serverWorked() {
        jdbc.update("""
                UPDATE dispatch_state SET server_failing_since = NULL, server_failures = 0, quiet_until = NULL
                 WHERE id = 1 AND server_failing_since IS NOT NULL""");
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
