package com.oriah.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

/**
 * The machinery behind {@link OutboxSchemaGuard} and {@link InboxSchemaGuard}:
 * compare one table in the connection's current schema with the agreed
 * shape, and -- for an append-only table -- try to change it and expect to
 * be refused.
 *
 * <p>Everything is reported as plain sentences ("outbox: column payload is
 * jsonb not null, expected json not null") so a failing service test says
 * exactly what to fix in its migration.
 */
final class SchemaGuardSupport {

    static final String REFUSE = "; an append-only table must refuse it";

    /**
     * The agreed shape of one table.
     *
     * @param columns     column name to its description, e.g. "varchar(64) not null"
     * @param constraints each constraint as Postgres prints it, e.g. "UNIQUE (event_id)"
     * @param probeInsert for an append-only table, an INSERT of one harmless
     *                    row naming every NOT NULL column (so it works even
     *                    when a default is missing); null for a mutable table
     * @param probeWhere  picks out that row again
     */
    record TableSpec(String name, Map<String, String> columns, Set<String> constraints,
                     String probeInsert, String probeWhere) {

        boolean appendOnly() {
            return probeInsert != null;
        }
    }

    private SchemaGuardSupport() {
    }

    static Map<String, String> columns(String... nameThenDescription) {
        Map<String, String> columns = new LinkedHashMap<>();
        for (int i = 0; i < nameThenDescription.length; i += 2) {
            columns.put(nameThenDescription[i], nameThenDescription[i + 1]);
        }
        return columns;
    }

    static List<String> check(DataSource dataSource, TableSpec spec) {
        try (Connection connection = dataSource.getConnection()) {
            if (!exists(connection, spec.name())) {
                return List.of(spec.name() + ": table is missing");
            }
            List<String> problems = new ArrayList<>();
            problems.addAll(columnProblems(connection, spec));
            problems.addAll(constraintProblems(connection, spec));
            if (spec.appendOnly()) {
                problems.addAll(appendOnlyProblems(connection, spec));
            }
            return problems;
        } catch (SQLException e) {
            throw new IllegalStateException("could not inspect " + spec.name(), e);
        }
    }

    static long count(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("could not run " + sql, e);
        }
    }

    /**
     * The options of the sequence behind an identity or serial column, as
     * "START n MINVALUE n INCREMENT n CACHE n [NO] CYCLE"; null when the
     * column has no sequence (or the table is missing -- the column checks
     * report that).
     */
    static String sequenceOptions(DataSource dataSource, String table, String column) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT s.seqstart, s.seqmin, s.seqincrement, s.seqcache, s.seqcycle
                     FROM pg_sequence s
                     WHERE s.seqrelid = pg_get_serial_sequence(quote_ident(current_schema()) || '.' || ?, ?)::regclass""")) {
            if (!exists(connection, table)) {
                return null;
            }
            query.setString(1, table);
            query.setString(2, column);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return "START " + rows.getLong("seqstart") + " MINVALUE " + rows.getLong("seqmin")
                        + " INCREMENT " + rows.getLong("seqincrement") + " CACHE " + rows.getLong("seqcache")
                        + (rows.getBoolean("seqcycle") ? " CYCLE" : " NO CYCLE");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not inspect the sequence of " + table + "." + column, e);
        }
    }

    private static boolean exists(Connection connection, String table) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name = ?""")) {
            query.setString(1, table);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1) > 0;
            }
        }
    }

    private static List<String> columnProblems(Connection connection, TableSpec spec) throws SQLException {
        Map<String, String> actual = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT column_name, data_type, character_maximum_length, is_nullable,
                       column_default, is_identity, identity_generation
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ?
                ORDER BY ordinal_position""")) {
            query.setString(1, spec.name());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    actual.put(rows.getString("column_name"), describe(rows));
                }
            }
        }
        List<String> problems = new ArrayList<>();
        spec.columns().forEach((column, expected) -> {
            String found = actual.get(column);
            if (found == null) {
                problems.add(spec.name() + ": column " + column + " is missing, expected " + expected);
            } else if (!found.equals(expected)) {
                problems.add(spec.name() + ": column " + column + " is " + found + ", expected " + expected);
            }
        });
        actual.forEach((column, found) -> {
            if (!spec.columns().containsKey(column)) {
                problems.add(spec.name() + ": unexpected column " + column + " " + found);
            }
        });
        return problems;
    }

    /** One column in the same words the specs use: type, then not null, default, identity. */
    private static String describe(ResultSet column) throws SQLException {
        String type = switch (column.getString("data_type")) {
            case "character varying" -> "varchar(" + column.getInt("character_maximum_length") + ")";
            case "timestamp with time zone" -> "timestamptz";
            case "integer" -> "int";
            default -> column.getString("data_type");
        };
        StringBuilder description = new StringBuilder(type);
        if ("NO".equals(column.getString("is_nullable"))) {
            description.append(" not null");
        }
        if (column.getString("column_default") != null) {
            description.append(" default ").append(column.getString("column_default"));
        }
        if ("YES".equals(column.getString("is_identity"))) {
            description.append(" identity ").append(column.getString("identity_generation").toLowerCase());
        }
        return description.toString();
    }

    private static List<String> constraintProblems(Connection connection, TableSpec spec) throws SQLException {
        Set<String> actual = new LinkedHashSet<>();
        // contype 'n' is a NOT NULL constraint, which Postgres 18 lists here;
        // nullability is already checked column by column.
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = current_schema() AND t.relname = ? AND c.contype <> 'n'""")) {
            query.setString(1, spec.name());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    actual.add(rows.getString(1));
                }
            }
        }
        List<String> problems = new ArrayList<>();
        for (String expected : spec.constraints()) {
            if (!actual.contains(expected)) {
                problems.add(spec.name() + ": missing constraint " + expected);
            }
        }
        for (String found : actual) {
            if (!spec.constraints().contains(found)) {
                problems.add(spec.name() + ": unexpected constraint " + found);
            }
        }
        return problems;
    }

    /**
     * Inserts a probe row and tries UPDATE, DELETE and TRUNCATE, each behind a
     * savepoint, inside one transaction that is always rolled back -- so the
     * guard is safe even against a table that wrongly allows them. An UPDATE
     * or DELETE that touches no row would never fire a row trigger, which is
     * why the probe row is needed at all.
     */
    private static List<String> appendOnlyProblems(Connection connection, TableSpec spec) throws SQLException {
        String table = spec.name();
        // Any column will do for the UPDATE except an identity column:
        // Postgres refuses to set one of those before any trigger runs, which
        // would hide whether the trigger is there.
        String anyColumn = spec.columns().entrySet().stream()
                .filter(column -> !column.getValue().contains("identity"))
                .findFirst().orElseThrow().getKey();
        Map<String, String> attempts = new LinkedHashMap<>();
        attempts.put("UPDATE", "UPDATE " + table + " SET " + anyColumn + " = " + anyColumn
                + " WHERE " + spec.probeWhere());
        attempts.put("DELETE", "DELETE FROM " + table + " WHERE " + spec.probeWhere());
        attempts.put("TRUNCATE", "TRUNCATE " + table);

        List<String> problems = new ArrayList<>();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            try {
                statement.execute(spec.probeInsert());
            } catch (SQLException e) {
                problems.add(table + ": could not insert a probe row: " + e.getMessage());
                return problems;
            }
            for (Map.Entry<String, String> attempt : attempts.entrySet()) {
                Savepoint before = connection.setSavepoint();
                try {
                    statement.execute(attempt.getValue());
                    problems.add(table + ": " + attempt.getKey() + " was allowed" + REFUSE);
                } catch (SQLException e) {
                    if (e.getMessage() == null || !e.getMessage().contains("is append-only")) {
                        problems.add(table + ": " + attempt.getKey() + " failed, but not because the table "
                                + "is append-only: " + e.getMessage());
                    }
                } finally {
                    connection.rollback(before);
                }
            }
            return problems;
        } finally {
            connection.rollback();
            connection.setAutoCommit(autoCommit);
        }
    }
}
