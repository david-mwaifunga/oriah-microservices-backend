package com.oriah.events;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

/**
 * Makes the database refuse to COMMIT any transaction that moved the relay
 * cursor, while it is armed: a deferred constraint trigger only runs at
 * commit, after the relay has already sent its messages. That is exactly
 * "the broker acknowledged, then we crashed before recording it" -- done
 * by the real database, not by mocking the relay.
 *
 * <p>The trigger is dropped on close, so the shared test database is left
 * as it was.
 */
final class CommitTrap implements AutoCloseable {

    private final DataSource dataSource;

    private CommitTrap(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    static CommitTrap armOnCursorUpdate(DataSource dataSource) throws SQLException {
        execute(dataSource, """
                CREATE OR REPLACE FUNCTION test_commit_trap() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated crash at commit';
                END;
                $$;
                CREATE CONSTRAINT TRIGGER test_commit_trap
                    AFTER UPDATE ON outbox_relay_cursor
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION test_commit_trap();""");
        return new CommitTrap(dataSource);
    }

    @Override
    public void close() throws SQLException {
        execute(dataSource, """
                DROP TRIGGER IF EXISTS test_commit_trap ON outbox_relay_cursor;
                DROP FUNCTION IF EXISTS test_commit_trap();""");
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
